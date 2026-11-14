package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.remote.player.PlayerIds;
import com.theplumteam.server.CustomCollections;
import com.theplumteam.server.PlayerCollections;
import com.theplumteam.server.PlayerMessages;
import com.theplumteam.server.ServerProfiles;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.util.ServerLevels;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client-to-server packet in which whoever manages a world's codes moves a player collection
 * to its newest revision, sets whether it follows new revisions by itself, or has the server
 * check for changes now: what the commands {@code update}, {@code follow} and {@code refresh}
 * do, from the settings. The server checks who asks, as it does for the commands.
 */
public class PlayerCollectionActionPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "player_collection_action");

    /** What is asked. The order is the wire's: a value is only ever added at the end. */
    public enum Action { UPDATE, FOLLOW, UNFOLLOW, REFRESH }

    private final Action action;
    private final String code;

    /**
     * @param code the collection's code; empty for {@link Action#REFRESH}, which is about all of them
     */
    public PlayerCollectionActionPacket(Action action, String code) {
        if (action == null || (action == Action.REFRESH ? !code.isEmpty() : !PlayerIds.isPlayerCode(code))) {
            throw new IllegalArgumentException("Invalid player collection action");
        }
        this.action = action;
        this.code = code;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeByte(action.ordinal());
        buffer.writeUtf(code, PlayerIds.CODE_LENGTH);
        return buffer;
    }

    public static PlayerCollectionActionPacket decode(FriendlyByteBuf buffer) {
        int ordinal = buffer.readByte();
        Action[] actions = Action.values();
        if (ordinal < 0 || ordinal >= actions.length) {
            throw new IllegalArgumentException("Unknown player collection action: " + ordinal);
        }
        return new PlayerCollectionActionPacket(actions[ordinal], buffer.readUtf(PlayerIds.CODE_LENGTH));
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        PlayerCollectionActionPacket packet = decode(buf);
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player)) {
                return;
            }
            if (!ServerProfiles.managesCodes(player)) {
                BlockPopsMod.LOGGER.warn("Player {} tried to change player collections without permission",
                        player.getName().getString());
                return;
            }
            MinecraftServer server = ServerLevels.serverOf(player);
            switch (packet.action) {
                case UPDATE -> PlayerCollections.update(server, packet.code,
                        result -> tell(player, PlayerMessages.of(result)));
                case FOLLOW, UNFOLLOW -> PlayerCollections.follow(server, packet.code, packet.action == Action.FOLLOW,
                        result -> tell(player, PlayerMessages.of(result)));
                case REFRESH -> PlayerCollections.refresh(server, ran -> {
                    if (ran) {
                        CustomCollections.refreshNow(server);
                    }
                    tell(player, new PlayerMessages.Reply(
                            ran ? PlayerMessages.REFRESHED.with() : PlayerMessages.REFRESH_WAIT.with(), ran));
                });
            }
        });
    }

    private static void tell(ServerPlayer player, PlayerMessages.Reply reply) {
        player.sendSystemMessage(reply.success() ? reply.text() : reply.text().copy().withStyle(ChatFormatting.RED));
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
