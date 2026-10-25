package com.theplumteam.network;

import com.theplumteam.worldcontent.Sha256;
import com.theplumteam.worldcontent.WorldContentIds;
import com.theplumteam.worldcontent.WorldContentLimits;
import com.theplumteam.worldcontent.WorldContentStatus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The bounds every World collection packet writes and reads its fields with, so both
 * sides agree on them and no field falls back to the 32767-character default.
 */
final class WorldPacketFields {
    static final int MAX_SLUG = 24;
    static final int MAX_ID = WorldContentIds.COLLECTION_PREFIX.length() + MAX_SLUG;
    static final int MAX_NAME = WorldContentLimits.MAX_NAME_LENGTH;
    static final int MAX_AUTHOR = WorldContentLimits.MAX_AUTHOR_LENGTH;
    static final int MAX_SHA = 64;
    static final int MAX_COLOR = 16;
    static final int MAX_DETAIL = WorldContentStatus.MAX_DETAIL;
    static final int MAX_AUTHOR_URL = WorldContentLimits.MAX_AUTHOR_URL_LENGTH;
    /** A datapack namespace and pack path, as {@code WorldCollectionImporter} accepts them. */
    static final int MAX_NAMESPACE = 64;
    static final int MAX_PACK_PATH = 32;

    private WorldPacketFields() {
    }

    /** Whether the player's client can receive a server-to-client World collection packet. */
    static boolean canReceive(ServerPlayer player, ResourceLocation id) {
        return PacketNetworking.canPlayerReceive(player, id);
    }

    /** Reads a sha that must be present, refusing anything that is not 64 lowercase hex digits. */
    static String readSha(FriendlyByteBuf buffer) {
        String sha = buffer.readUtf(MAX_SHA);
        if (!Sha256.isValidHex(sha)) {
            throw new IllegalArgumentException("World collection packet carries an invalid SHA-256");
        }
        return sha;
    }

    /** Reads a sha that may be empty for none. */
    static String readOptionalSha(FriendlyByteBuf buffer) {
        String sha = buffer.readUtf(MAX_SHA);
        if (!sha.isEmpty() && !Sha256.isValidHex(sha)) {
            throw new IllegalArgumentException("World collection packet carries an invalid SHA-256");
        }
        return sha;
    }

    static int bounded(int count, int min, int max, String what) {
        if (count < min || count > max) {
            throw new IllegalArgumentException("World collection packet " + what + " out of range: " + count);
        }
        return count;
    }
}
