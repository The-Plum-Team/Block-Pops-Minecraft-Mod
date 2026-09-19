package com.theplumteam.network;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.BoxBlockEntity;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Packet sent from client to server to update figure position, hitbox, and logo settings.
 * Used by the FigurePositionScreen (dev mode only).
 */
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

    /**
     * Encode the packet to a buffer for sending to the server
     */
    public FriendlyByteBuf encode() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBlockPos(pos);
        buffer.writeDouble(offsetX);
        buffer.writeDouble(offsetY);
        buffer.writeDouble(offsetZ);
        buffer.writeDouble(scale);
        buffer.writeDouble(hitboxOffsetX);
        buffer.writeDouble(hitboxOffsetY);
        buffer.writeDouble(hitboxOffsetZ);
        buffer.writeDouble(hitboxScaleX);
        buffer.writeDouble(hitboxScaleY);
        buffer.writeDouble(hitboxScaleZ);
        // Write logo config (nullable fields)
        buffer.writeBoolean(logoPositionX != null);
        if (logoPositionX != null) buffer.writeDouble(logoPositionX);
        buffer.writeBoolean(logoPositionY != null);
        if (logoPositionY != null) buffer.writeDouble(logoPositionY);
        buffer.writeBoolean(logoPositionZ != null);
        if (logoPositionZ != null) buffer.writeDouble(logoPositionZ);
        buffer.writeBoolean(logoScaleX != null);
        if (logoScaleX != null) buffer.writeDouble(logoScaleX);
        buffer.writeBoolean(logoScaleY != null);
        if (logoScaleY != null) buffer.writeDouble(logoScaleY);
        buffer.writeBoolean(logoScaleZ != null);
        if (logoScaleZ != null) buffer.writeDouble(logoScaleZ);
        return buffer;
    }

    /**
     * Decode a packet from a buffer
     */
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
        return new FigurePositionPacket(pos, offsetX, offsetY, offsetZ, scale,
                                       hitboxOffsetX, hitboxOffsetY, hitboxOffsetZ,
                                       hitboxScaleX, hitboxScaleY, hitboxScaleZ,
                                       logoPositionX, logoPositionY, logoPositionZ,
                                       logoScaleX, logoScaleY, logoScaleZ);
    }

    /**
     * Handle the packet on the server side
     */
    public static void handleServer(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        FigurePositionPacket packet = decode(buf);

        BlockPopsMod.logDebug("Received FigurePositionPacket on server - Position: {}", packet.pos);

        context.queue(() -> {
            if (context.getPlayer() instanceof ServerPlayer player) {
                BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);
                if (blockEntity instanceof BoxBlockEntity boxBlockEntity) {
                    BlockPopsMod.logDebug("Setting figure offset, scale, hitbox offset, hitbox scale, and logo config");
                    boxBlockEntity.setFigureOffset(packet.offsetX, packet.offsetY, packet.offsetZ);
                    boxBlockEntity.setFigureScale(packet.scale);
                    boxBlockEntity.setHitboxOffset(packet.hitboxOffsetX, packet.hitboxOffsetY, packet.hitboxOffsetZ);
                    boxBlockEntity.setHitboxScale(packet.hitboxScaleX, packet.hitboxScaleY, packet.hitboxScaleZ);
                    boxBlockEntity.setLogoPosition(packet.logoPositionX, packet.logoPositionY, packet.logoPositionZ);
                    boxBlockEntity.setLogoScale(packet.logoScaleX, packet.logoScaleY, packet.logoScaleZ);
                    boxBlockEntity.setChanged();
                    BlockPopsMod.logDebug("Figure position updated successfully");
                } else {
                    LOGGER.warn("BlockEntity at {} is not a BoxBlockEntity", packet.pos);
                }
            }
        });
    }

    /**
     * Send this packet to the server
     */
    public void sendToServer() {
        PacketNetworking.sendToServer(ModNetworking.FIGURE_POSITION, this::encode);
    }
}
