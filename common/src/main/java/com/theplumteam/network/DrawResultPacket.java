package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.gui.FigureDraw;
import com.theplumteam.util.ResourceLocations;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-to-client packet naming the figure a token drew, so the machine's screen can play the
 * draw out and stop on it. It is sent before the figure is unlocked, so the screen still shows
 * a new figure as undiscovered while the draw runs.
 */
public class DrawResultPacket {
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "draw_result");

    private final String collectionId;
    private final String figureId;
    private final boolean isNew;

    public DrawResultPacket(String collectionId, String figureId, boolean isNew) {
        this.collectionId = collectionId;
        this.figureId = figureId;
        this.isNew = isNew;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(collectionId);
        buffer.writeUtf(figureId);
        buffer.writeBoolean(isNew);
        return buffer;
    }

    public static DrawResultPacket decode(FriendlyByteBuf buffer) {
        return new DrawResultPacket(buffer.readUtf(), buffer.readUtf(), buffer.readBoolean());
    }

    public static void handleClient(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        DrawResultPacket packet = decode(buf);
        context.queue(() -> FigureDraw.onResult(packet.collectionId, packet.figureId, packet.isNew));
    }

    public static void sendToPlayer(ServerPlayer player, String collectionId, String figureId, boolean isNew) {
        PacketNetworking.sendToPlayer(player, ID, new DrawResultPacket(collectionId, figureId, isNew).encode());
    }
}
