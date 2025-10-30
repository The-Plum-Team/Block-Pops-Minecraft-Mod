package com.theplumteam.network;

import com.theplumteam.blockentity.ClawMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

public class ClawMachineCollectionPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClawMachineCollectionPacket.class);

    private final BlockPos pos;
    private final String collectionId;

    public ClawMachineCollectionPacket(BlockPos pos, String collectionId) {
        this.pos = pos;
        this.collectionId = collectionId;
    }

    public static void encode(ClawMachineCollectionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos);
        buffer.writeUtf(packet.collectionId);
    }

    public static ClawMachineCollectionPacket decode(FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        String collectionId = buffer.readUtf();
        return new ClawMachineCollectionPacket(pos, collectionId);
    }

    public static void handle(ClawMachineCollectionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        LOGGER.info("Received packet on server - Position: {}, Collection ID: {}",
                    packet.pos, packet.collectionId);
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                LOGGER.info("Player is not null: {}", player.getName().getString());
                BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);
                LOGGER.info("BlockEntity at {}: {}", packet.pos, blockEntity);
                if (blockEntity instanceof ClawMachineBlockEntity clawMachineBlockEntity) {
                    LOGGER.info("Setting collection ID on ClawMachineBlockEntity");
                    clawMachineBlockEntity.setCollectionId(packet.collectionId);
                    // Notify clients of the change
                    player.level().sendBlockUpdated(packet.pos,
                        blockEntity.getBlockState(),
                        blockEntity.getBlockState(), 3);
                    LOGGER.info("Collection ID updated successfully");
                } else {
                    LOGGER.warn("BlockEntity is not a ClawMachineBlockEntity!");
                }
            } else {
                LOGGER.warn("Player is null in packet handler!");
            }
        });
        context.setPacketHandled(true);
    }
}
