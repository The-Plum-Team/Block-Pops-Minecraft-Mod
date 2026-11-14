package com.theplumteam.network;

import com.theplumteam.server.CustomCollections;
import com.theplumteam.server.PlayerCollectionSettings;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Player collections on the wire: pointers and a signed head, and nothing that is not one. */
class SyncPlayerCollectionsPacketTest {
    private static final String ID = "p_k3v7q2m5x4ab";
    private static final String CODE = "maple-orbit-42";
    private static final String SHA = "ab".repeat(32);
    private static final byte[] HEAD = "{\"kid\":\"o1\"}".getBytes(StandardCharsets.UTF_8);

    private static SyncPlayerCollectionsPacket decode(FriendlyByteBuf buffer) {
        try {
            SyncPlayerCollectionsPacket packet = SyncPlayerCollectionsPacket.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return packet;
        } finally {
            buffer.release();
        }
    }

    private static FriendlyByteBuf wire(String id, String code, int rev, String sha, byte[] head) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(1);
        buffer.writeUtf(id);
        buffer.writeUtf(code);
        buffer.writeVarInt(rev);
        buffer.writeUtf(sha);
        buffer.writeByteArray(head);
        return buffer;
    }

    @Test
    void anEntryIsAPointerAndTheHeadAsItWasPublished() {
        SyncPlayerCollectionsPacket.Entry entry = new SyncPlayerCollectionsPacket.Entry(ID, CODE, 5, SHA, HEAD);
        SyncPlayerCollectionsPacket read = decode(new SyncPlayerCollectionsPacket(List.of(entry)).encode());
        assertEquals(1, read.entries().size());
        SyncPlayerCollectionsPacket.Entry back = read.entries().get(0);
        assertEquals(List.of(ID, CODE, 5, SHA), List.of(back.id(), back.code(), back.rev(), back.indexSha256()));
        assertArrayEquals(HEAD, back.head());
        assertTrue(decode(new SyncPlayerCollectionsPacket(List.of()).encode()).entries().isEmpty());
        assertEquals(1, decode(wire(ID, CODE, 5, SHA, HEAD)).entries().size());
    }

    @Test
    void whatIsNotAWellFormedPointerIsRefusedWhole() {
        assertThrows(RuntimeException.class, () -> decode(wire("w-mine", CODE, 5, SHA, HEAD)));
        assertThrows(RuntimeException.class, () -> decode(wire("p_k3v7q2m5x4a9", CODE, 5, SHA, HEAD)));
        assertThrows(RuntimeException.class, () -> decode(wire(ID, "willow-media", 5, SHA, HEAD)));
        assertThrows(RuntimeException.class, () -> decode(wire(ID, "../../manifest", 5, SHA, HEAD)));
        assertThrows(RuntimeException.class, () -> decode(wire(ID, CODE, 0, SHA, HEAD)));
        assertThrows(RuntimeException.class, () -> decode(wire(ID, CODE, 5, SHA.toUpperCase(java.util.Locale.ROOT), HEAD)));
        assertThrows(RuntimeException.class, () -> decode(wire(ID, CODE, 5, SHA, new byte[0])));
        assertThrows(RuntimeException.class, () -> decode(wire(ID, CODE, 5, SHA, new byte[4097])));

        // The same collection twice, or more than a world may have
        SyncPlayerCollectionsPacket.Entry entry = new SyncPlayerCollectionsPacket.Entry(ID, CODE, 5, SHA, HEAD);
        assertThrows(IllegalArgumentException.class, () -> new SyncPlayerCollectionsPacket(List.of(entry, entry)));
        FriendlyByteBuf crowded = new FriendlyByteBuf(Unpooled.buffer());
        crowded.writeVarInt(PlayerCollectionSettings.MAX_ENTRIES + 1);
        assertThrows(RuntimeException.class, () -> decode(crowded));
    }

    @Test
    void aFullWorldStaysFarBelowWhatOnePacketCarries() {
        List<SyncPlayerCollectionsPacket.Entry> entries = new ArrayList<>();
        for (int i = 0; i < PlayerCollectionSettings.MAX_ENTRIES; i++) {
            String id = "p_aaaaaaaaaa" + (char) ('a' + i % 26) + (i < 26 ? 'a' : 'b');
            entries.add(new SyncPlayerCollectionsPacket.Entry(id, CODE, 1_000_000, SHA, new byte[4096]));
        }
        FriendlyByteBuf buffer = new SyncPlayerCollectionsPacket(entries).encode();
        try {
            assertTrue(buffer.readableBytes() < 140 * 1024, "bytes: " + buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void aClientFromBeforePlayerCollectionsIsToldAResultItCanRead() {
        // The first eight keep their places, which is what such a client reads them by
        CustomCollections.Result[] results = CustomCollections.Result.values();
        assertEquals(List.of("ADDED", "REMOVED", "UNKNOWN_CODE", "ALREADY_ENABLED", "NOT_ENABLED", "CLASHES", "FULL",
                "FAILED", "PLAYER_ADDED", "UNAVAILABLE", "PLAYER_OFF"),
                java.util.Arrays.stream(results).map(Enum::name).toList());
        for (CustomCollections.Result result : results) {
            assertTrue(result.forOlderClient().ordinal() < 8, result.name());
        }
        assertEquals(CustomCollections.Result.ADDED, CustomCollections.Result.PLAYER_ADDED.forOlderClient());
        assertEquals(CustomCollections.Result.FAILED, CustomCollections.Result.UNAVAILABLE.forOlderClient());
        assertEquals(CustomCollections.Result.FAILED, CustomCollections.Result.PLAYER_OFF.forOlderClient());
        assertEquals(CustomCollections.Result.FULL, CustomCollections.Result.FULL.forOlderClient());
    }
}
