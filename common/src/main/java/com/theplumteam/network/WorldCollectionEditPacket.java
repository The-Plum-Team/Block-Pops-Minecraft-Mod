package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.server.worldcollections.WorldCollectionImporter;
import com.theplumteam.server.worldcollections.WorldCollections;
import com.theplumteam.server.worldcollections.WorldRateLimits;
import com.theplumteam.util.ResourceLocations;
import com.theplumteam.util.ServerLevels;
import com.theplumteam.worldcontent.Sha256;
import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentLimits;
import com.theplumteam.worldcontent.WorldContentStatus;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Client-to-server packet with one edit an operator made to the World collections, or a
 * batch of them that the server saves together or not at all. The server checks the edits
 * and the records they produce as it checks any other change, and answers once in a
 * {@link WorldCollectionStatusPacket}. Files the edits name must have been uploaded first,
 * except for an import, which reads them from the server's own datapacks.
 */
public class WorldCollectionEditPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldCollectionEditPacket.class);
    public static final ResourceLocation ID = ResourceLocations.of(BlockPopsMod.MOD_ID, "world_collection_edit");

    /**
     * The largest packet the client sends, safely below the 32767 bytes a client-to-server
     * payload may hold on every supported version.
     */
    public static final int MAX_CLIENT_BYTES = 30_000;

    private final int requestId;
    private final WorldCollectionEdit edit;

    public WorldCollectionEditPacket(int requestId, WorldCollectionEdit edit) {
        this.requestId = requestId;
        this.edit = edit;
    }

    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(requestId);
        encodeOne(buffer, edit, false);
        return buffer;
    }

    /**
     * How many bytes the packet carrying the edit takes, which the client keeps at or below
     * {@link #MAX_CLIENT_BYTES} by splitting its batches.
     */
    public static int encodedSize(WorldCollectionEdit edit) {
        FriendlyByteBuf buffer = new WorldCollectionEditPacket(Integer.MAX_VALUE, edit).encode();
        try {
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    /** Writes one edit: its action byte, then its fields; a batch writes its edits the same way. */
    private static void encodeOne(FriendlyByteBuf buffer, WorldCollectionEdit edit, boolean nested) {
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
        } else if (edit instanceof WorldCollectionEdit.CreateDraft create) {
            buffer.writeUtf(create.slug(), WorldPacketFields.MAX_SLUG);
            buffer.writeUtf(create.name(), WorldPacketFields.MAX_NAME);
        } else if (edit instanceof WorldCollectionEdit.Publish publish) {
            buffer.writeUtf(publish.id(), WorldPacketFields.MAX_ID);
        } else if (edit instanceof WorldCollectionEdit.ReorderFigures reorder) {
            buffer.writeUtf(reorder.id(), WorldPacketFields.MAX_ID);
            buffer.writeVarInt(reorder.order().size());
            for (String figure : reorder.order()) {
                buffer.writeUtf(figure, WorldPacketFields.MAX_SLUG);
            }
        } else if (edit instanceof WorldCollectionEdit.RemoveFigure remove) {
            buffer.writeUtf(remove.id(), WorldPacketFields.MAX_ID);
            buffer.writeUtf(remove.figure(), WorldPacketFields.MAX_SLUG);
        } else if (edit instanceof WorldCollectionEdit.SetCollectionExtras set) {
            buffer.writeUtf(set.id(), WorldPacketFields.MAX_ID);
            buffer.writeUtf(set.authorUrl(), WorldPacketFields.MAX_AUTHOR_URL);
            WorldCollectionRecord.LogoLayout layout = set.logoLayout();
            buffer.writeByte(layout != null ? 1 : 0);
            if (layout != null) {
                buffer.writeShort(inRange(layout.scalePct(), Short.MIN_VALUE, Short.MAX_VALUE));
                buffer.writeByte(inRange(layout.offsetX(), Byte.MIN_VALUE, Byte.MAX_VALUE));
                buffer.writeByte(inRange(layout.offsetY(), Byte.MIN_VALUE, Byte.MAX_VALUE));
            }
            buffer.writeBoolean(set.plush());
            WorldCollectionRecord.RarityWeights weights = set.rarityWeights();
            buffer.writeByte(weights != null ? 1 : 0);
            if (weights != null) {
                buffer.writeVarInt(weights.common());
                buffer.writeVarInt(weights.rare());
                buffer.writeVarInt(weights.epic());
                buffer.writeVarInt(weights.legendary());
            }
        } else if (edit instanceof WorldCollectionEdit.SetFigureExtras set) {
            buffer.writeUtf(set.id(), WorldPacketFields.MAX_ID);
            buffer.writeUtf(set.figure(), WorldPacketFields.MAX_SLUG);
            buffer.writeByte(set.rarity().ordinal());
            buffer.writeByte(inRange(set.variants().size(), 0, WorldContentLimits.MAX_VARIANTS_PER_FIGURE));
            for (WorldCollectionRecord.Variant variant : set.variants()) {
                buffer.writeUtf(variant.name(), WorldPacketFields.MAX_NAME);
                buffer.writeUtf(variant.texture(), WorldPacketFields.MAX_SHA);
            }
            buffer.writeByte(inRange(set.faceMode(), WorldCollectionEdit.FACE_DEFAULT, WorldCollectionEdit.FACE_SHOW_UV));
            if (set.faceMode() == WorldCollectionEdit.FACE_SHOW_UV) {
                WorldCollectionRecord.Uv uv = Objects.requireNonNull(set.faceUv(), "A face shown from a region needs it");
                buffer.writeShort(inRange(uv.u(), Short.MIN_VALUE, Short.MAX_VALUE));
                buffer.writeShort(inRange(uv.v(), Short.MIN_VALUE, Short.MAX_VALUE));
                buffer.writeShort(inRange(uv.w(), Short.MIN_VALUE, Short.MAX_VALUE));
                buffer.writeShort(inRange(uv.h(), Short.MIN_VALUE, Short.MAX_VALUE));
            }
        } else if (edit instanceof WorldCollectionEdit.Duplicate duplicate) {
            standAlone(edit.action(), nested);
            buffer.writeUtf(duplicate.sourceId(), WorldPacketFields.MAX_ID);
            buffer.writeUtf(duplicate.slug(), WorldPacketFields.MAX_SLUG);
            buffer.writeUtf(duplicate.name(), WorldPacketFields.MAX_NAME);
        } else if (edit instanceof WorldCollectionEdit.Purge purge) {
            standAlone(edit.action(), nested);
            buffer.writeUtf(purge.id(), WorldPacketFields.MAX_ID);
            buffer.writeUtf(purge.idAgain(), WorldPacketFields.MAX_ID);
        } else if (edit instanceof WorldCollectionEdit.ImportPack pack) {
            standAlone(edit.action(), nested);
            buffer.writeUtf(pack.slug(), WorldPacketFields.MAX_SLUG);
            buffer.writeUtf(pack.namespace(), WorldPacketFields.MAX_NAMESPACE);
            buffer.writeUtf(pack.path(), WorldPacketFields.MAX_PACK_PATH);
            buffer.writeBoolean(pack.asDraft());
        } else if (edit instanceof WorldCollectionEdit.Batch batch) {
            standAlone(edit.action(), nested);
            buffer.writeVarInt(batch.edits().size());
            for (WorldCollectionEdit inner : batch.edits()) {
                encodeOne(buffer, inner, true);
            }
        }
    }

    public static WorldCollectionEditPacket decode(FriendlyByteBuf buffer) {
        int requestId = buffer.readVarInt();
        return new WorldCollectionEditPacket(requestId, decodeOne(buffer, false));
    }

    /** Reads one edit, refusing a batch inside a batch and every count or field out of bounds. */
    private static WorldCollectionEdit decodeOne(FriendlyByteBuf buffer, boolean nested) {
        WorldCollectionEdit.Action action = WorldCollectionEdit.Action.byId(buffer.readUnsignedByte());
        if (action == null) {
            throw new IllegalArgumentException("Unknown World collection edit");
        }
        return switch (action) {
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
            case CREATE_DRAFT -> new WorldCollectionEdit.CreateDraft(buffer.readUtf(WorldPacketFields.MAX_SLUG),
                    buffer.readUtf(WorldPacketFields.MAX_NAME));
            case PUBLISH -> new WorldCollectionEdit.Publish(buffer.readUtf(WorldPacketFields.MAX_ID));
            case REORDER_FIGURES -> {
                String id = buffer.readUtf(WorldPacketFields.MAX_ID);
                int count = readCount(buffer, WorldContentLimits.MAX_FIGURES, "figures to order");
                List<String> order = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    order.add(buffer.readUtf(WorldPacketFields.MAX_SLUG));
                }
                yield new WorldCollectionEdit.ReorderFigures(id, order);
            }
            case REMOVE_FIGURE -> new WorldCollectionEdit.RemoveFigure(buffer.readUtf(WorldPacketFields.MAX_ID),
                    buffer.readUtf(WorldPacketFields.MAX_SLUG));
            case SET_COLLECTION_EXTRAS -> {
                String id = buffer.readUtf(WorldPacketFields.MAX_ID);
                String authorUrl = buffer.readUtf(WorldPacketFields.MAX_AUTHOR_URL);
                WorldCollectionRecord.LogoLayout layout = null;
                if (readFlag(buffer)) {
                    layout = new WorldCollectionRecord.LogoLayout(buffer.readShort(), buffer.readByte(), buffer.readByte());
                }
                boolean plush = buffer.readBoolean();
                WorldCollectionRecord.RarityWeights weights = null;
                if (readFlag(buffer)) {
                    weights = new WorldCollectionRecord.RarityWeights(buffer.readVarInt(), buffer.readVarInt(),
                            buffer.readVarInt(), buffer.readVarInt());
                }
                yield new WorldCollectionEdit.SetCollectionExtras(id, authorUrl, layout, plush, weights);
            }
            case SET_FIGURE_EXTRAS -> {
                String id = buffer.readUtf(WorldPacketFields.MAX_ID);
                String figure = buffer.readUtf(WorldPacketFields.MAX_SLUG);
                int rarity = buffer.readUnsignedByte();
                if (rarity >= WorldCollectionRecord.Rarity.values().length) {
                    throw new IllegalArgumentException("World collection edit carries an unknown rarity");
                }
                int count = WorldPacketFields.bounded(buffer.readUnsignedByte(), 0,
                        WorldContentLimits.MAX_VARIANTS_PER_FIGURE, "variants");
                List<WorldCollectionRecord.Variant> variants = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    variants.add(new WorldCollectionRecord.Variant(buffer.readUtf(WorldPacketFields.MAX_NAME),
                            WorldPacketFields.readSha(buffer)));
                }
                int faceMode = WorldPacketFields.bounded(buffer.readUnsignedByte(), WorldCollectionEdit.FACE_DEFAULT,
                        WorldCollectionEdit.FACE_SHOW_UV, "box face");
                WorldCollectionRecord.Uv uv = null;
                if (faceMode == WorldCollectionEdit.FACE_SHOW_UV) {
                    uv = new WorldCollectionRecord.Uv(buffer.readShort(), buffer.readShort(), buffer.readShort(),
                            buffer.readShort());
                }
                yield new WorldCollectionEdit.SetFigureExtras(id, figure, WorldCollectionRecord.Rarity.values()[rarity],
                        variants, faceMode, uv);
            }
            case DUPLICATE -> {
                standAlone(action, nested);
                yield new WorldCollectionEdit.Duplicate(buffer.readUtf(WorldPacketFields.MAX_ID),
                        buffer.readUtf(WorldPacketFields.MAX_SLUG), buffer.readUtf(WorldPacketFields.MAX_NAME));
            }
            case PURGE -> {
                standAlone(action, nested);
                yield new WorldCollectionEdit.Purge(buffer.readUtf(WorldPacketFields.MAX_ID),
                        buffer.readUtf(WorldPacketFields.MAX_ID));
            }
            case IMPORT_PACK -> {
                standAlone(action, nested);
                yield new WorldCollectionEdit.ImportPack(buffer.readUtf(WorldPacketFields.MAX_SLUG),
                        buffer.readUtf(WorldPacketFields.MAX_NAMESPACE), buffer.readUtf(WorldPacketFields.MAX_PACK_PATH),
                        buffer.readBoolean());
            }
            case BATCH -> {
                standAlone(action, nested);
                int count = readCount(buffer, WorldCollectionEdit.MAX_BATCH, "edits in a batch");
                List<WorldCollectionEdit> edits = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    edits.add(decodeOne(buffer, true));
                }
                yield new WorldCollectionEdit.Batch(edits);
            }
        };
    }

    /** Refuses an edit that stands alone, such as a batch, inside a batch. */
    private static void standAlone(WorldCollectionEdit.Action action, boolean nested) {
        if (nested) {
            throw new IllegalArgumentException("A batch of World collection edits cannot hold " + action);
        }
    }

    /** Reads a byte that must be 0 or 1. */
    private static boolean readFlag(FriendlyByteBuf buffer) {
        return WorldPacketFields.bounded(buffer.readUnsignedByte(), 0, 1, "flag") == 1;
    }

    /** A value the client is about to write in a field that cannot hold any other. */
    private static int inRange(int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("World collection edit field out of range: " + value);
        }
        return value;
    }

    /** Reads a count of 1 to max, refusing any other before anything is allocated for it. */
    private static int readCount(FriendlyByteBuf buffer, int max, String what) {
        int count = buffer.readVarInt();
        if (count < 1 || count > max) {
            throw new IllegalArgumentException("World collection edit carries " + count + " " + what
                    + ", not 1 to " + max);
        }
        return count;
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
            } else if (packet.edit instanceof WorldCollectionEdit.ImportPack pack) {
                status = importPack(player, pack);
            } else if (!WorldRateLimits.tryEdit(player.getUUID(), WorldRateLimits.editCost(packet.edit))) {
                status = WorldContentStatus.of(WorldContentStatus.Result.BUSY, "Too many edits; wait a moment");
            } else {
                status = WorldCollections.edit(packet.edit, player.getUUID());
            }
            if (status.result() == WorldContentStatus.Result.OK) {
                BlockPopsMod.logDebug("Player {} made World collection edit {}",
                        player.getName().getString(), packet.edit.action());
            }
            WorldCollectionStatusPacket.send(player, packet.requestId, status, 0, "");
        });
    }

    /**
     * Imports a datapack for an operator. It needs the server's resources, which an edit
     * does not, and has its own rate limit, which a degraded store or a malformed name does
     * not spend.
     */
    private static WorldContentStatus importPack(ServerPlayer player, WorldCollectionEdit.ImportPack pack) {
        if (WorldCollections.isDegraded()) {
            return WorldContentStatus.of(WorldContentStatus.Result.DEGRADED);
        }
        // A mistyped name is refused before it spends one of the few imports a minute
        if (!WorldContentIds.isSlug(pack.slug())) {
            return WorldContentStatus.of(WorldContentStatus.Result.INVALID, "Slug must be 1 to 24 of a-z, 0-9 and _");
        }
        if (!WorldCollectionImporter.isValidSource(pack.namespace(), pack.path())) {
            return WorldContentStatus.of(WorldContentStatus.Result.INVALID,
                    "A pack is named namespace:path, with a path of 1 to 32 of a-z, 0-9 and _");
        }
        if (!WorldRateLimits.tryImport(player.getUUID())) {
            return WorldContentStatus.of(WorldContentStatus.Result.BUSY, "At most "
                    + WorldContentLimits.IMPORTS_PER_MINUTE + " imports a minute; wait a moment");
        }
        return WorldCollections.importPack(ServerLevels.serverOf(player).getResourceManager(), pack.slug(),
                pack.namespace(), pack.path(), player.getUUID(), pack.asDraft());
    }

    public void sendToServer() {
        PacketNetworking.sendToServer(ID, this::encode);
    }
}
