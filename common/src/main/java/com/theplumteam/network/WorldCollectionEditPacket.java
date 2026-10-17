package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.server.worldcollections.WorldCollections;
import com.theplumteam.server.worldcollections.WorldRateLimits;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.util.ServerLevels;
import com.theplumteam.worldcontent.Sha256;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldContentStatus;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-to-server packet with one edit an operator made to the World collections. The
 * server checks the edit and the record it produces as it checks any other change, and
 * answers in a {@link WorldCollectionStatusPacket}. Files the edit names must have been
 * uploaded first.
 */
public class WorldCollectionEditPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldCollectionEditPacket.class);
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "world_collection_edit");

    private final int requestId;
    private final WorldCollectionEdit edit;

    public WorldCollectionEditPacket(int requestId, WorldCollectionEdit edit) {
        this.requestId = requestId;
        this.edit = edit;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(requestId);
        buffer.writeByte(edit.action().id());
        if (edit instanceof WorldCollectionEdit.Create create) {
            buffer.writeUtf(create.slug(), WorldPacketFields.MAX_SLUG);
            buffer.writeUtf(create.name(), WorldPacketFields.MAX_NAME);
        } else if (edit instanceof WorldCollectionEdit.SetCollection set) {
            buffer.writeUtf(set.id(), WorldPacketFields.MAX_ID);
            buffer.writeUtf(set.name(), WorldPacketFields.MAX_NAME);
            buffer.writeUtf(set.author(), WorldPacketFields.MAX_AUTHOR);
            buffer.writeByte(set.red());
            buffer.writeByte(set.green());
            buffer.writeByte(set.blue());
            buffer.writeByte(set.boxMode());
            buffer.writeUtf(set.box(), WorldPacketFields.MAX_SHA);
            buffer.writeUtf(set.logo(), WorldPacketFields.MAX_SHA);
        } else if (edit instanceof WorldCollectionEdit.SetArchived set) {
            buffer.writeUtf(set.id(), WorldPacketFields.MAX_ID);
            buffer.writeBoolean(set.archived());
        } else if (edit instanceof WorldCollectionEdit.PutFigure put) {
            buffer.writeUtf(put.id(), WorldPacketFields.MAX_ID);
            buffer.writeUtf(put.figure(), WorldPacketFields.MAX_SLUG);
            buffer.writeUtf(put.name(), WorldPacketFields.MAX_NAME);
            buffer.writeByte(put.kind());
            buffer.writeUtf(put.texture(), WorldPacketFields.MAX_SHA);
            buffer.writeUtf(put.model(), WorldPacketFields.MAX_SHA);
            buffer.writeUtf(put.animation(), WorldPacketFields.MAX_SHA);
            buffer.writeByte(put.scaleIndex());
        } else if (edit instanceof WorldCollectionEdit.SetFigureEnabled set) {
            buffer.writeUtf(set.id(), WorldPacketFields.MAX_ID);
            buffer.writeUtf(set.figure(), WorldPacketFields.MAX_SLUG);
            buffer.writeBoolean(set.enabled());
        }
        return buffer;
    }

    public static WorldCollectionEditPacket decode(FriendlyByteBuf buffer) {
        int requestId = buffer.readVarInt();
        WorldCollectionEdit.Action action = WorldCollectionEdit.Action.byId(buffer.readUnsignedByte());
        if (action == null) {
            throw new IllegalArgumentException("Unknown World collection edit");
        }
        WorldCollectionEdit edit = switch (action) {
            case CREATE -> new WorldCollectionEdit.Create(buffer.readUtf(WorldPacketFields.MAX_SLUG),
                    buffer.readUtf(WorldPacketFields.MAX_NAME));
            case SET_COLLECTION -> {
                String id = buffer.readUtf(WorldPacketFields.MAX_ID);
                String name = buffer.readUtf(WorldPacketFields.MAX_NAME);
                String author = buffer.readUtf(WorldPacketFields.MAX_AUTHOR);
                int red = buffer.readUnsignedByte();
                int green = buffer.readUnsignedByte();
                int blue = buffer.readUnsignedByte();
                int boxMode = buffer.readUnsignedByte();
                String box = buffer.readUtf(WorldPacketFields.MAX_SHA);
                String logo = WorldPacketFields.readOptionalSha(buffer);
                checkBox(boxMode, box);
                yield new WorldCollectionEdit.SetCollection(id, name, author, red, green, blue, boxMode, box, logo);
            }
            case SET_ARCHIVED -> new WorldCollectionEdit.SetArchived(buffer.readUtf(WorldPacketFields.MAX_ID),
                    buffer.readBoolean());
            case PUT_FIGURE -> {
                String id = buffer.readUtf(WorldPacketFields.MAX_ID);
                String figure = buffer.readUtf(WorldPacketFields.MAX_SLUG);
                String name = buffer.readUtf(WorldPacketFields.MAX_NAME);
                int kind = buffer.readUnsignedByte();
                String texture = WorldPacketFields.readSha(buffer);
                String model = WorldPacketFields.readOptionalSha(buffer);
                String animation = WorldPacketFields.readOptionalSha(buffer);
                int scaleIndex = buffer.readUnsignedByte();
                yield new WorldCollectionEdit.PutFigure(id, figure, name, kind, texture, model, animation, scaleIndex);
            }
            case SET_FIGURE_ENABLED -> new WorldCollectionEdit.SetFigureEnabled(
                    buffer.readUtf(WorldPacketFields.MAX_ID), buffer.readUtf(WorldPacketFields.MAX_SLUG),
                    buffer.readBoolean());
        };
        return new WorldCollectionEditPacket(requestId, edit);
    }

    /** The box field is a sha for a texture, a colour name for a colour, and empty for none. */
    private static void checkBox(int boxMode, String box) {
        boolean ok = switch (boxMode) {
            case WorldCollectionEdit.BOX_NONE -> box.isEmpty();
            case WorldCollectionEdit.BOX_TEXTURE -> Sha256.isValidHex(box);
            case WorldCollectionEdit.BOX_COLOR -> !box.isEmpty() && box.length() <= WorldPacketFields.MAX_COLOR;
            default -> false;
        };
        if (!ok) {
            throw new IllegalArgumentException("World collection edit carries an invalid box");
        }
    }

    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        WorldCollectionEditPacket packet = decode(buf);
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer player)) {
                return;
            }
            WorldContentStatus status;
            if (!ServerLevels.hasCommandLevel(player, 2)) {
                LOGGER.warn("Player {} tried to edit World collections without permission",
                        player.getName().getString());
                status = WorldContentStatus.of(WorldContentStatus.Result.NOT_ALLOWED);
            } else if (!WorldRateLimits.tryEdit(player.getUUID())) {
                status = WorldContentStatus.of(WorldContentStatus.Result.BUSY, "Too many edits; wait a moment");
            } else {
                status = WorldCollections.edit(packet.edit, player.getUUID());
                if (status.result() == WorldContentStatus.Result.OK) {
                    BlockPopsMod.logDebug("Player {} made World collection edit {}",
                            player.getName().getString(), packet.edit.action());
                }
            }
            WorldCollectionStatusPacket.send(player, packet.requestId, status, 0, "");
        });
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
