package com.theplumteam.network;

import com.theplumteam.worldcontent.WorldCollectionEdit;
import com.theplumteam.worldcontent.WorldCollectionRecord;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SetHolographic on the wire: action 15, appended after every older action, and a strict flag. */
class WorldCollectionEditPacketTest {
    private static byte[] bytes(FriendlyByteBuf buffer) {
        try {
            byte[] out = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), out);
            return out;
        } finally {
            buffer.release();
        }
    }

    private static byte[] encoded(int requestId, WorldCollectionEdit edit) {
        return bytes(new WorldCollectionEditPacket(requestId, edit).encode());
    }

    // Decodes the bytes and encodes the packet they make again
    private static byte[] roundTrip(byte[] wire) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
        try {
            WorldCollectionEditPacket packet = WorldCollectionEditPacket.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return bytes(packet.encode());
        } finally {
            buffer.release();
        }
    }

    @Test
    void setHolographicIsTheFifteenthActionAppendedAfterTheOthers() {
        assertEquals(15, WorldCollectionEdit.Action.SET_HOLOGRAPHIC.id());
        assertSame(WorldCollectionEdit.Action.SET_HOLOGRAPHIC, WorldCollectionEdit.Action.byId(15));
        assertEquals(WorldCollectionEdit.Action.SET_HOLOGRAPHIC,
                new WorldCollectionEdit.SetHolographic("w-col", false).action());
        assertEquals(WorldCollectionEdit.Action.values().length - 1,
                WorldCollectionEdit.Action.SET_HOLOGRAPHIC.ordinal());
        assertFalse(WorldCollectionEdit.standsAlone(new WorldCollectionEdit.SetHolographic("w-col", false)));
    }

    @Test
    void setHolographicCarriesTheIdAndAFlag() {
        for (boolean holographic : new boolean[] {false, true}) {
            byte[] wire = encoded(7, new WorldCollectionEdit.SetHolographic("w-col", holographic));
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(wire));
            try {
                assertEquals(7, buffer.readVarInt());
                assertEquals(15, buffer.readUnsignedByte());
                assertEquals("w-col", buffer.readUtf(64));
                assertEquals(holographic ? 1 : 0, buffer.readUnsignedByte());
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
            assertArrayEquals(wire, roundTrip(wire));
        }
    }

    @Test
    void setHolographicTravelsInABatchWithTheOtherExtras() {
        WorldCollectionEdit batch = new WorldCollectionEdit.Batch(List.of(
                new WorldCollectionEdit.SetCollectionExtras("w-col", "https://example.com/me",
                        new WorldCollectionRecord.LogoLayout(120, 4, -2), true, null),
                new WorldCollectionEdit.SetHolographic("w-col", false),
                new WorldCollectionEdit.Publish("w-col")));
        byte[] wire = encoded(9, batch);
        assertArrayEquals(wire, roundTrip(wire));
        assertTrue(WorldCollectionEditPacket.encodedSize(batch) <= WorldCollectionEditPacket.MAX_CLIENT_BYTES);
    }

    @Test
    void aFlagOtherThanZeroOrOneIsRefused() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(1);
            buffer.writeByte(15);
            buffer.writeUtf("w-col", 64);
            buffer.writeByte(2);
            assertThrows(IllegalArgumentException.class, () -> WorldCollectionEditPacket.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
