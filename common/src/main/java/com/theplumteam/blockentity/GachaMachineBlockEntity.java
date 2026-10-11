package com.theplumteam.blockentity;

import com.theplumteam.registry.ModBlockEntities;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
 * Carries the gacha machine's GeckoLib model and the plush collection it is set to.
 * It has no animation yet.
 */
public class GachaMachineBlockEntity extends BlockEntity implements GeoBlockEntity {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private String collectionId = "";

    public GachaMachineBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.GACHA_MACHINE_BLOCK.get(), pos, blockState);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
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
        sink.putString("CollectionId", collectionId);
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
        this.collectionId = source.getString("CollectionId", this.collectionId);
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

    // ===== COLLECTION MANAGEMENT =====
    public String getCollectionId() {
        return collectionId;
    }

    public void setCollectionId(String collectionId) {
        this.collectionId = collectionId;
        setChanged();
    }
}
