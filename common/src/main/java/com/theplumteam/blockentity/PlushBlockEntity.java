package com.theplumteam.blockentity;

import com.theplumteam.util.FieldSink;
import com.theplumteam.util.FieldSource;
import net.minecraft.core.BlockPos;
//? if >=1.21.6 {
/*import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
*///? }
//? if >=1.21 {
/*import net.minecraft.core.HolderLookup;
*///? }
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoBlockEntity;
//? if >=1.21.5 {
/*import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.manager.AnimatableManager;
*///? } elif >=1.21 {
/*import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
*///? } else {
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
//? }
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Carries a plush's GeckoLib model. Each plush has its own block entity type, so the type
 * alone says which plush this is. The only state is the placement the dev-mode adjustment
 * screen edits: where the model sits, how large it is, and its hitbox.
 */
public class PlushBlockEntity extends BlockEntity implements GeoBlockEntity {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private double offsetX = 0.0;
    private double offsetY = 0.0;
    private double offsetZ = 0.0;
    private double scale = 1.0;
    private double hitboxOffsetX = 0.0;
    private double hitboxOffsetY = 0.0;
    private double hitboxOffsetZ = 0.0;
    private double hitboxScaleX = 1.0;
    private double hitboxScaleY = 1.0;
    private double hitboxScaleZ = 1.0;
    // Rebuilt whenever the hitbox changes; null while the hitbox is the block's own shape.
    @Nullable
    private VoxelShape adjustedShape;

    public PlushBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    public double getOffsetX() { return offsetX; }
    public double getOffsetY() { return offsetY; }
    public double getOffsetZ() { return offsetZ; }
    public double getScale() { return scale; }
    public double getHitboxOffsetX() { return hitboxOffsetX; }
    public double getHitboxOffsetY() { return hitboxOffsetY; }
    public double getHitboxOffsetZ() { return hitboxOffsetZ; }
    public double getHitboxScaleX() { return hitboxScaleX; }
    public double getHitboxScaleY() { return hitboxScaleY; }
    public double getHitboxScaleZ() { return hitboxScaleZ; }

    /** The hitbox the adjustment screen set, or null while it is untouched. */
    @Nullable
    public VoxelShape getAdjustedShape() {
        return adjustedShape;
    }

    public void setPlacement(double offsetX, double offsetY, double offsetZ, double scale,
                             double hitboxOffsetX, double hitboxOffsetY, double hitboxOffsetZ,
                             double hitboxScaleX, double hitboxScaleY, double hitboxScaleZ) {
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
        rebuildShape();
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    // The block's 12x14x12 box, scaled about the centre of its floor and then shifted.
    private void rebuildShape() {
        if (hitboxOffsetX == 0.0 && hitboxOffsetY == 0.0 && hitboxOffsetZ == 0.0
                && hitboxScaleX == 1.0 && hitboxScaleY == 1.0 && hitboxScaleZ == 1.0) {
            adjustedShape = null;
            return;
        }
        double halfX = 6.0 * hitboxScaleX;
        double halfZ = 6.0 * hitboxScaleZ;
        double shiftX = hitboxOffsetX * 16.0;
        double shiftY = hitboxOffsetY * 16.0;
        double shiftZ = hitboxOffsetZ * 16.0;
        adjustedShape = Block.box(8.0 - halfX + shiftX, shiftY, 8.0 - halfZ + shiftZ,
                8.0 + halfX + shiftX, 14.0 * hitboxScaleY + shiftY, 8.0 + halfZ + shiftZ);
    }

    @Override
    //? if >=1.21.6 {
    /*protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        saveFields(new FieldSink(output));
    }
    *///? } elif >=1.21 {
    /*protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        saveFields(new FieldSink(tag));
    }
    *///? } else {
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        saveFields(new FieldSink(tag));
    }
    //? }

    private void saveFields(FieldSink sink) {
        sink.putDouble("OffsetX", offsetX);
        sink.putDouble("OffsetY", offsetY);
        sink.putDouble("OffsetZ", offsetZ);
        sink.putDouble("Scale", scale);
        sink.putDouble("HitboxOffsetX", hitboxOffsetX);
        sink.putDouble("HitboxOffsetY", hitboxOffsetY);
        sink.putDouble("HitboxOffsetZ", hitboxOffsetZ);
        sink.putDouble("HitboxScaleX", hitboxScaleX);
        sink.putDouble("HitboxScaleY", hitboxScaleY);
        sink.putDouble("HitboxScaleZ", hitboxScaleZ);
    }

    @Override
    //? if >=1.21.6 {
    /*protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        loadFields(new FieldSource(input));
    }
    *///? } elif >=1.21 {
    /*protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        loadFromTag(tag);
    }
    *///? } else {
    public void load(CompoundTag tag) {
        super.load(tag);
        loadFromTag(tag);
    }
    //? }

    /** Reads the same fields from a raw tag, as an update packet carries them. */
    public void loadFromTag(CompoundTag tag) {
        loadFields(new FieldSource(tag));
    }

    private void loadFields(FieldSource source) {
        this.offsetX = source.getDouble("OffsetX", this.offsetX);
        this.offsetY = source.getDouble("OffsetY", this.offsetY);
        this.offsetZ = source.getDouble("OffsetZ", this.offsetZ);
        this.scale = source.getDouble("Scale", this.scale);
        this.hitboxOffsetX = source.getDouble("HitboxOffsetX", this.hitboxOffsetX);
        this.hitboxOffsetY = source.getDouble("HitboxOffsetY", this.hitboxOffsetY);
        this.hitboxOffsetZ = source.getDouble("HitboxOffsetZ", this.hitboxOffsetZ);
        this.hitboxScaleX = source.getDouble("HitboxScaleX", this.hitboxScaleX);
        this.hitboxScaleY = source.getDouble("HitboxScaleY", this.hitboxScaleY);
        this.hitboxScaleZ = source.getDouble("HitboxScaleZ", this.hitboxScaleZ);
        rebuildShape();
    }

    // ===== CHUNK LOAD SYNCHRONIZATION =====
    @Override
    //? if >=1.21.6 {
    /*public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        saveAdditional(output);
        tag.merge(output.buildResult());
    *///? } elif >=1.21 {
    /*public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
    *///? } else {
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
    //? }
        return tag;
    }

    // Forge-specific method - no @Override in common
    //? if >=1.21.6 {
    /*public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadFromTag(tag);
    *///? } elif >=1.21 {
    /*public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    *///? } else {
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    //? }
    }

    // ===== REAL-TIME SYNCHRONIZATION =====
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // Forge-specific method - no @Override in common
    //? if >=1.21 {
    /*public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
    *///? } else {
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
    //? }
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            //? if >=1.21 {
            /*loadFromTag(tag);
            *///? } else {
            load(tag);
            //? }
            if (level != null && level.isClientSide()) {
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            }
        }
    }
}
