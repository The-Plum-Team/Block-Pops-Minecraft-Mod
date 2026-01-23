package com.theplumteam.blockentity;

import com.theplumteam.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

public class ClawMachineBlockEntity extends BlockEntity implements GeoBlockEntity {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private static final RawAnimation IDLE_ANIMATION =
        RawAnimation.begin().thenLoop("animation.claw_machine_block.idle");

    private String collectionId = "";

    public ClawMachineBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.CLAW_MACHINE_BLOCK.get(), pos, blockState);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 0, state ->
            state.setAndContinue(IDLE_ANIMATION)
        ));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("CollectionId", collectionId);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("CollectionId")) {
            this.collectionId = tag.getString("CollectionId");
        }
    }

    // ===== CHUNK LOAD SYNCHRONIZATION =====
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    // Forge-specific method - no @Override in common
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    }

    // ===== REAL-TIME SYNCHRONIZATION =====
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // Forge-specific method - no @Override in common
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            load(tag);
            if (level != null && level.isClientSide) {
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            }
        }
    }

    public static <T extends BlockEntity> void tick(Level level, BlockPos pos,
                                                     BlockState state, T blockEntity) {
        if (level.isClientSide && blockEntity instanceof ClawMachineBlockEntity) {
            // GeckoLib handles animation ticking automatically
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
