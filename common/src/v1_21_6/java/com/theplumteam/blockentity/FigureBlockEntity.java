package com.theplumteam.blockentity;

import com.mojang.serialization.Codec;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.manager.AnimatableManager;
import software.bernie.geckolib.animatable.processing.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

public class FigureBlockEntity extends BlockEntity implements GeoBlockEntity {
    private static final Logger LOGGER = LoggerFactory.getLogger(FigureBlockEntity.class);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // Figure data
    private String figureId = "";
    private String collectionId = "";
    private int alternativeSkinIndex = 0; // 0 is default, 1+ are from the alternatives list
    private int poseIndex = 0; // 0 = standing, 1 = sitting
    private String skinSnapshot = null; // Saved skin snapshot URL for player figures (Mojang)
    private String quickSkinId = null; // Saved Quick Skin ID

    // Figure positioning - matches BoxBlockEntity for consistent display
    private double figureOffsetX = -0.60;
    private double figureOffsetY = 0.01;
    private double figureOffsetZ = -0.55;
    private double figureScale = 1.0;

    public FigureBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.FIGURE_BLOCK.get(), pos, blockState);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Animation controller for figure poses (5 ticks = 0.25 seconds transition)
        controllers.add(new AnimationController<>("pose_controller", 5, state -> {
            if (this.poseIndex == 1) {
                // "Pose_Sit" must match the name inside the pose animation file exactly
                return state.setAndContinue(RawAnimation.begin().thenLoop("Pose_Sit"));
            }
            // Default pose (standing) - play the static standing pose
            return state.setAndContinue(RawAnimation.begin().thenLoop("Pose_Stand"));
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    public String getCollectionId() { return collectionId; }
    public void setCollectionId(String collectionId) {
        this.collectionId = collectionId != null ? collectionId : "";
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    public String getFigureId() { return figureId; }
    public void setFigureId(String figureId) {
        this.figureId = figureId != null ? figureId : "";
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    public FigureDefinition getFigureDefinition() {
        if (figureId.isEmpty() || collectionId.isEmpty()) return null;
        return CollectionRegistry.getFigure(collectionId, figureId).orElse(null);
    }

    public boolean hasFigure() {
        return !figureId.isEmpty() && !collectionId.isEmpty() && getFigureDefinition() != null;
    }

    public double getFigureOffsetX() { return figureOffsetX; }
    public double getFigureOffsetY() { return figureOffsetY; }
    public double getFigureOffsetZ() { return figureOffsetZ; }
    public double getFigureScale() { return figureScale; }

    public void setFigureOffset(double x, double y, double z) {
        this.figureOffsetX = x;
        this.figureOffsetY = y;
        this.figureOffsetZ = z;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    public void setFigureScale(double scale) {
        this.figureScale = scale;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    public int getAlternativeSkinIndex() { return alternativeSkinIndex; }

    public String getSkinSnapshot() { return skinSnapshot; }
    public void setSkinSnapshot(String skinSnapshot) {
        this.skinSnapshot = skinSnapshot;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    public String getQuickSkinId() { return quickSkinId; }
    public void setQuickSkinId(String quickSkinId) {
        this.quickSkinId = quickSkinId;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    public void cycleAlternativeSkin() {
        FigureDefinition def = getFigureDefinition();
        if (def == null || !def.hasAlternatives()) return;
        int totalSkins = 1 + def.getAlternatives().size();
        this.alternativeSkinIndex = (this.alternativeSkinIndex + 1) % totalSkins;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    public int getPoseIndex() {
        return poseIndex;
    }

    public void cyclePose() {
        FigureDefinition def = getFigureDefinition();
        if (def != null && def.isPoseLocked()) return;
        // Toggles between 0 (Standing) and 1 (Sitting)
        this.poseIndex = (this.poseIndex + 1) % 2;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("FigureId", figureId);
        output.putString("CollectionId", collectionId);
        output.putInt("AlternativeSkinIndex", alternativeSkinIndex);
        output.putInt("PoseIndex", poseIndex);
        output.storeNullable("SkinSnapshot", Codec.STRING, skinSnapshot);
        output.storeNullable("QuickSkinId", Codec.STRING, quickSkinId);
        output.putDouble("FigureOffsetX", figureOffsetX);
        output.putDouble("FigureOffsetY", figureOffsetY);
        output.putDouble("FigureOffsetZ", figureOffsetZ);
        output.putDouble("FigureScale", figureScale);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.figureId = input.getStringOr("FigureId", this.figureId);
        this.collectionId = input.getStringOr("CollectionId", this.collectionId);
        this.alternativeSkinIndex = input.getIntOr("AlternativeSkinIndex", 0);
        this.poseIndex = input.getIntOr("PoseIndex", 0);
        this.skinSnapshot = input.getString("SkinSnapshot").orElse(null);
        this.quickSkinId = input.getString("QuickSkinId").orElse(null);
        this.figureOffsetX = input.getDoubleOr("FigureOffsetX", this.figureOffsetX);
        this.figureOffsetY = input.getDoubleOr("FigureOffsetY", this.figureOffsetY);
        this.figureOffsetZ = input.getDoubleOr("FigureOffsetZ", this.figureOffsetZ);
        this.figureScale = input.getDoubleOr("FigureScale", this.figureScale);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }

    // NeoForge-specific method - no @Override in common
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));
    }

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

    public void saveToItem(net.minecraft.world.item.ItemStack stack) {
        CompoundTag tag;
        if (level != null) {
            tag = this.saveCustomOnly(level.registryAccess());
        } else {
            tag = new CompoundTag();
        }
        // Required in 1.21+ - block entity type ID must be present
        tag.putString("id", "blockpops:figure_block");
        stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag));
    }

    /**
     * Load NBT data from ItemStack for rendering purposes.
     * This is a public helper since loadAdditional is protected.
     */
    public void loadFromItemNbt(CompoundTag tag) {
        this.figureId = tag.getStringOr("FigureId", this.figureId);
        this.collectionId = tag.getStringOr("CollectionId", this.collectionId);
        this.alternativeSkinIndex = tag.getIntOr("AlternativeSkinIndex", 0);
        this.poseIndex = tag.getIntOr("PoseIndex", 0);
        this.skinSnapshot = tag.getString("SkinSnapshot").orElse(null);
        this.quickSkinId = tag.getString("QuickSkinId").orElse(null);
        this.figureOffsetX = tag.getDoubleOr("FigureOffsetX", this.figureOffsetX);
        this.figureOffsetY = tag.getDoubleOr("FigureOffsetY", this.figureOffsetY);
        this.figureOffsetZ = tag.getDoubleOr("FigureOffsetZ", this.figureOffsetZ);
        this.figureScale = tag.getDoubleOr("FigureScale", this.figureScale);
    }

    public static <T extends BlockEntity> void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
        if (level.isClientSide && blockEntity instanceof FigureBlockEntity figureBlockEntity) {
            // Animation ticking handled automatically by GeckoLib
        }
    }
}
