package com.theplumteam.blockentity;

import com.theplumteam.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.manager.AnimatableManager;
import software.bernie.geckolib.animatable.processing.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
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
        controllers.add(new AnimationController<>("controller", 0, state ->
            state.setAndContinue(IDLE_ANIMATION)
        ));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("CollectionId", collectionId);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.collectionId = input.getStringOr("CollectionId", this.collectionId);
    }

    // ===== CHUNK LOAD SYNCHRONIZATION =====
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }

    // NeoForge-specific method - no @Override in common
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
    }

    // ===== REAL-TIME SYNCHRONIZATION =====
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // NeoForge-specific method - no @Override in common
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
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

    /**
     * Load NBT data from ItemStack for rendering purposes.
     * This is a public helper since loadAdditional is protected.
     */
    public void loadFromItemNbt(CompoundTag tag) {
        this.collectionId = tag.getStringOr("CollectionId", this.collectionId);
    }
}
