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
    private final double hitboxScaleX;
    private final double hitboxScaleY;
    private final double hitboxScaleZ;
    private final Double logoPositionX;
    private final Double logoPositionY;
    private final Double logoPositionZ;
    private final Double logoScaleX;
    private final Double logoScaleY;
    private final Double logoScaleZ;

    public FigurePositionPacket(BlockPos pos, double offsetX, double offsetY, double offsetZ, double scale,
                                double hitboxOffsetX, double hitboxOffsetY, double hitboxOffsetZ,
                                double hitboxScaleX, double hitboxScaleY, double hitboxScaleZ,
                                Double logoPositionX, Double logoPositionY, Double logoPositionZ,
                                Double logoScaleX, Double logoScaleY, Double logoScaleZ) {
        this.pos = pos;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.scale = scale;
        this.hitboxOffsetX = hitboxOffsetX;
        this.hitboxOffsetY = hitboxOffsetY;
        this.hitboxOffsetZ = hitboxOffsetZ;
        this.hitboxScaleX = hitboxScaleX;
        this.hitboxScaleY = hitboxScaleY;
        this.hitboxScaleZ = hitboxScaleZ;
        this.logoPositionX = logoPositionX;
        this.logoPositionY = logoPositionY;
        this.logoPositionZ = logoPositionZ;
        this.logoScaleX = logoScaleX;
        this.logoScaleY = logoScaleY;
        this.logoScaleZ = logoScaleZ;
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
        buffer.writeDouble(packet.hitboxScaleX);
        buffer.writeDouble(packet.hitboxScaleY);
        buffer.writeDouble(packet.hitboxScaleZ);
        // Write logo config (nullable fields)
        buffer.writeBoolean(packet.logoPositionX != null);
        if (packet.logoPositionX != null) buffer.writeDouble(packet.logoPositionX);
        buffer.writeBoolean(packet.logoPositionY != null);
        if (packet.logoPositionY != null) buffer.writeDouble(packet.logoPositionY);
        buffer.writeBoolean(packet.logoPositionZ != null);
        if (packet.logoPositionZ != null) buffer.writeDouble(packet.logoPositionZ);
        buffer.writeBoolean(packet.logoScaleX != null);
        if (packet.logoScaleX != null) buffer.writeDouble(packet.logoScaleX);
        buffer.writeBoolean(packet.logoScaleY != null);
        if (packet.logoScaleY != null) buffer.writeDouble(packet.logoScaleY);
        buffer.writeBoolean(packet.logoScaleZ != null);
        if (packet.logoScaleZ != null) buffer.writeDouble(packet.logoScaleZ);
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
        double hitboxScaleX = buffer.readDouble();
        double hitboxScaleY = buffer.readDouble();
        double hitboxScaleZ = buffer.readDouble();
        // Read logo config (nullable fields)
        Double logoPositionX = buffer.readBoolean() ? buffer.readDouble() : null;
        Double logoPositionY = buffer.readBoolean() ? buffer.readDouble() : null;
        Double logoPositionZ = buffer.readBoolean() ? buffer.readDouble() : null;
        Double logoScaleX = buffer.readBoolean() ? buffer.readDouble() : null;
        Double logoScaleY = buffer.readBoolean() ? buffer.readDouble() : null;
        Double logoScaleZ = buffer.readBoolean() ? buffer.readDouble() : null;
        return new FigurePositionPacket(pos, offsetX, offsetY, offsetZ, scale, hitboxOffsetX, hitboxOffsetY, hitboxOffsetZ,
                                       hitboxScaleX, hitboxScaleY, hitboxScaleZ,
                                       logoPositionX, logoPositionY, logoPositionZ, logoScaleX, logoScaleY, logoScaleZ);
    }

    public static void handle(FigurePositionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        LOGGER.info("Received packet on server - Position: {}, Offsets: X={}, Y={}, Z={}, Scale={}, HitboxOffsets: X={}, Y={}, Z={}, HitboxScale: X={}, Y={}, Z={}",
                    packet.pos, packet.offsetX, packet.offsetY, packet.offsetZ, packet.scale,
                    packet.hitboxOffsetX, packet.hitboxOffsetY, packet.hitboxOffsetZ,
                    packet.hitboxScaleX, packet.hitboxScaleY, packet.hitboxScaleZ);
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                LOGGER.info("Player is not null: {}", player.getName().getString());
                BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);
                LOGGER.info("BlockEntity at {}: {}", packet.pos, blockEntity);
                if (blockEntity instanceof BoxBlockEntity boxBlockEntity) {
                    LOGGER.info("Setting figure offset, scale, hitbox offset, hitbox scale, and logo config on BoxBlockEntity");
                    boxBlockEntity.setFigureOffset(packet.offsetX, packet.offsetY, packet.offsetZ);
                    boxBlockEntity.setFigureScale(packet.scale);
                    boxBlockEntity.setHitboxOffset(packet.hitboxOffsetX, packet.hitboxOffsetY, packet.hitboxOffsetZ);
                    boxBlockEntity.setHitboxScale(packet.hitboxScaleX, packet.hitboxScaleY, packet.hitboxScaleZ);
                    boxBlockEntity.setLogoPosition(packet.logoPositionX, packet.logoPositionY, packet.logoPositionZ);
                    boxBlockEntity.setLogoScale(packet.logoScaleX, packet.logoScaleY, packet.logoScaleZ);
                    boxBlockEntity.setChanged();
                    LOGGER.info("Figure, hitbox offsets, hitbox scale, and logo config updated successfully");
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
