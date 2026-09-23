package com.theplumteam.blockentity;

import com.theplumteam.util.E2EDeterminism;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.util.TagReads;
import com.theplumteam.util.FieldSink;
import com.theplumteam.util.FieldSource;
//? if >=1.21.6 {
/*import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
*///? }
//? if >=1.21 {
/*import com.theplumteam.item.BlockEntityItemData;
import net.minecraft.core.HolderLookup;
*///? }
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.bernie.geckolib.animatable.GeoBlockEntity;
//? if >=1.21.5 {
/*import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.manager.AnimatableManager;
import software.bernie.geckolib.animatable.processing.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
*///? } elif >=1.21 {
/*import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
*///? } else {
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
//? }
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
        controllers.add(
        //? if >=1.21.5 {
        /*new AnimationController<>("pose_controller", 5, state -> {
        *///? } else {
        new AnimationController<>(this, "pose_controller", 5, state -> {
        //? }
            if (this.poseIndex == 1) {
                // "Pose_Sit" must match the name inside the pose animation file exactly
                return state.setAndContinue(RawAnimation.begin().thenLoop("Pose_Sit"));
            }
            // Default pose (standing) - play the static standing pose
            return state.setAndContinue(RawAnimation.begin().thenLoop("Pose_Stand"));
        }));
    }

    // The packaged E2E photographs the idle animation at one fixed frame.
    @Override
    public double getTick(Object blockEntity) {
        return E2EDeterminism.animationTick(GeoBlockEntity.super.getTick(blockEntity));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    public String getCollectionId() { return collectionId; }
    public void setCollectionId(String collectionId) {
        this.collectionId = collectionId != null ? collectionId : "";
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    public String getFigureId() { return figureId; }
    public void setFigureId(String figureId) {
        this.figureId = figureId != null ? figureId : "";
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
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
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    public void setFigureScale(double scale) {
        this.figureScale = scale;
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    public int getAlternativeSkinIndex() { return alternativeSkinIndex; }

    public String getSkinSnapshot() { return skinSnapshot; }
    public void setSkinSnapshot(String skinSnapshot) {
        this.skinSnapshot = skinSnapshot;
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    public String getQuickSkinId() { return quickSkinId; }
    public void setQuickSkinId(String quickSkinId) {
        this.quickSkinId = quickSkinId;
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    public void cycleAlternativeSkin() {
        FigureDefinition def = getFigureDefinition();
        if (def == null || !def.hasAlternatives()) return;
        int totalSkins = 1 + def.getAlternatives().size();
        this.alternativeSkinIndex = (this.alternativeSkinIndex + 1) % totalSkins;
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
    }

    public int getPoseIndex() {
        return poseIndex;
    }

    public void cyclePose() {
        // Toggles between 0 (Standing) and 1 (Sitting)
        this.poseIndex = (this.poseIndex + 1) % 2;
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
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
        sink.putString("FigureId", figureId);
        sink.putString("CollectionId", collectionId);
        sink.putInt("AlternativeSkinIndex", alternativeSkinIndex);
        sink.putInt("PoseIndex", poseIndex);
        if (skinSnapshot != null) sink.putString("SkinSnapshot", skinSnapshot);
        if (quickSkinId != null) sink.putString("QuickSkinId", quickSkinId);
        sink.putDouble("FigureOffsetX", figureOffsetX);
        sink.putDouble("FigureOffsetY", figureOffsetY);
        sink.putDouble("FigureOffsetZ", figureOffsetZ);
        sink.putDouble("FigureScale", figureScale);
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
        loadForItemRendering(tag);
    }
    *///? } else {
    public void load(CompoundTag tag) {
        super.load(tag);
        loadForItemRendering(tag);
    }
    //? }

    /** Reads the same fields from the raw tag an item stack carries. */
    public void loadForItemRendering(CompoundTag tag) {
        loadFields(new FieldSource(tag));
    }

    private void loadFields(FieldSource source) {
        this.figureId = source.getString("FigureId", this.figureId);
        this.collectionId = source.getString("CollectionId", this.collectionId);
        this.alternativeSkinIndex = source.getInt("AlternativeSkinIndex", this.alternativeSkinIndex);
        this.poseIndex = source.getInt("PoseIndex", this.poseIndex);
        this.skinSnapshot = source.getStringOrNull("SkinSnapshot");
        this.quickSkinId = source.getStringOrNull("QuickSkinId");
        this.figureOffsetX = source.getDouble("FigureOffsetX", this.figureOffsetX);
        this.figureOffsetY = source.getDouble("FigureOffsetY", this.figureOffsetY);
        this.figureOffsetZ = source.getDouble("FigureOffsetZ", this.figureOffsetZ);
        this.figureScale = source.getDouble("FigureScale", this.figureScale);
    }

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
        loadForItemRendering(tag);
    *///? } elif >=1.21 {
    /*public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    *///? } else {
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    //? }
    }

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
            /*loadForItemRendering(tag);
            *///? } else {
            load(tag);
            //? }
            if (level != null && level.isClientSide()) {
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            }
        }
    }

    //? if >=1.21 {
    /*// 1.21.2 removed BlockEntity.saveToItem, so this is a local helper there.
    public void saveToItem(net.minecraft.world.item.ItemStack stack, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveFields(new FieldSink(tag));
    *///? } else {
    public void saveToItem(net.minecraft.world.item.ItemStack stack) {
        CompoundTag tag = new CompoundTag();
        saveFields(new FieldSink(tag));
    //? }
        //? if >=1.21 {
        /*BlockEntityItemData.write(stack, tag, "blockpops:figure_block");
        *///? } else {
        stack.addTagElement("BlockEntityTag", tag);
        //? }
    }

    public static <T extends BlockEntity> void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
        if (level.isClientSide() && blockEntity instanceof FigureBlockEntity figureBlockEntity) {
            // Animation ticking handled automatically by GeckoLib
        }
    }
}
