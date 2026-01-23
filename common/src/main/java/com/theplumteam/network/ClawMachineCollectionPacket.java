package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Packet sent from client to server to set the collection ID on a claw machine.
 */
public class ClawMachineCollectionPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClawMachineCollectionPacket.class);

    private final BlockPos pos;
    private final String collectionId;

    public ClawMachineCollectionPacket(BlockPos pos, String collectionId) {
        this.pos = pos;
        this.collectionId = collectionId;
    }

    /**
     * Encode the packet to a buffer for sending to the server
     */
    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBlockPos(pos);
        buffer.writeUtf(collectionId);
        return buffer;
    }

    /**
     * Decode a packet from a buffer
     */
    public static ClawMachineCollectionPacket decode(FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        String collectionId = buffer.readUtf();
        return new ClawMachineCollectionPacket(pos, collectionId);
    }

    /**
     * Handle the packet on the server side
     */
    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        ClawMachineCollectionPacket packet = decode(buf);

        BlockPopsMod.logDebug("Received ClawMachineCollectionPacket on server - Position: {}, Collection ID: {}",
                    packet.pos, packet.collectionId);

        context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);
                if (blockEntity instanceof ClawMachineBlockEntity clawMachineBlockEntity) {
                    BlockPopsMod.logDebug("Setting collection ID on ClawMachineBlockEntity");
                    clawMachineBlockEntity.setCollectionId(packet.collectionId);
                    // Notify clients of the change
                    player.level().sendBlockUpdated(packet.pos,
                        blockEntity.getBlockState(),
                        blockEntity.getBlockState(), 3);
                    BlockPopsMod.logDebug("Collection ID updated successfully");
                } else {
                    LOGGER.warn("BlockEntity at {} is not a ClawMachineBlockEntity", packet.pos);
                }
            }
        });
    }

    /**
     * Send this packet to the server
     */
    public void sendToServer() {
        NetworkManager.sendToServer(ModNetworking.CLAW_MACHINE_COLLECTION, encode());
    }
}
