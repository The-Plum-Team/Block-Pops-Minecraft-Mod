package com.theplumteam.network;

import com.theplumteam.blockentity.BoxBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

public class FigurePositionPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(FigurePositionPacket.class);

    private final BlockPos pos;
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;
    private final double scale;
    private final double hitboxOffsetX;
    private final double hitboxOffsetY;
    private final double hitboxOffsetZ;

    public FigurePositionPacket(BlockPos pos, double offsetX, double offsetY, double offsetZ, double scale,
                                double hitboxOffsetX, double hitboxOffsetY, double hitboxOffsetZ) {
        this.pos = pos;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.scale = scale;
        this.hitboxOffsetX = hitboxOffsetX;
        this.hitboxOffsetY = hitboxOffsetY;
        this.hitboxOffsetZ = hitboxOffsetZ;
    }

    public static void encode(FigurePositionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos);
        buffer.writeDouble(packet.offsetX);
        buffer.writeDouble(packet.offsetY);
        buffer.writeDouble(packet.offsetZ);
        buffer.writeDouble(packet.scale);
        buffer.writeDouble(packet.hitboxOffsetX);
        buffer.writeDouble(packet.hitboxOffsetY);
        buffer.writeDouble(packet.hitboxOffsetZ);
    }

    public static FigurePositionPacket decode(FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        double offsetX = buffer.readDouble();
        double offsetY = buffer.readDouble();
        double offsetZ = buffer.readDouble();
        double scale = buffer.readDouble();
        double hitboxOffsetX = buffer.readDouble();
        double hitboxOffsetY = buffer.readDouble();
        double hitboxOffsetZ = buffer.readDouble();
        return new FigurePositionPacket(pos, offsetX, offsetY, offsetZ, scale, hitboxOffsetX, hitboxOffsetY, hitboxOffsetZ);
    }

    public static void handle(FigurePositionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        LOGGER.info("Received packet on server - Position: {}, Offsets: X={}, Y={}, Z={}, Scale={}, HitboxOffsets: X={}, Y={}, Z={}",
                    packet.pos, packet.offsetX, packet.offsetY, packet.offsetZ, packet.scale,
                    packet.hitboxOffsetX, packet.hitboxOffsetY, packet.hitboxOffsetZ);
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                LOGGER.info("Player is not null: {}", player.getName().getString());
                BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);
                LOGGER.info("BlockEntity at {}: {}", packet.pos, blockEntity);
                if (blockEntity instanceof BoxBlockEntity boxBlockEntity) {
                    LOGGER.info("Setting figure offset, scale, and hitbox offset on BoxBlockEntity");
                    boxBlockEntity.setFigureOffset(packet.offsetX, packet.offsetY, packet.offsetZ);
                    boxBlockEntity.setFigureScale(packet.scale);
                    boxBlockEntity.setHitboxOffset(packet.hitboxOffsetX, packet.hitboxOffsetY, packet.hitboxOffsetZ);
                    boxBlockEntity.setChanged();
                    LOGGER.info("Figure and hitbox offsets updated successfully");
                } else {
                    LOGGER.warn("BlockEntity is not a BoxBlockEntity!");
                }
            } else {
                LOGGER.warn("Player is null in packet handler!");
            }
        });
        context.setPacketHandled(true);
    }
}
