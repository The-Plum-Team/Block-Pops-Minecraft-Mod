package com.theplumteam.blockentity;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.registry.ModBlockEntities;
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
//? if >=1.21 {
/*import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.animation.PlayState;
*///? } else {
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
//? }
import software.bernie.geckolib.util.GeckoLibUtil;

import org.jetbrains.annotations.Nullable;

public class BoxBlockEntity extends BlockEntity implements GeoBlockEntity {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoxBlockEntity.class);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private static final RawAnimation IDLE_ANIMATION = RawAnimation.begin().thenLoop("animation.box_block.idle");
    private static final RawAnimation OPEN_ANIMATION = RawAnimation.begin().thenPlay("animation.box_block.open");
    private static final RawAnimation OPEN_STATE_ANIMATION = RawAnimation.begin().thenLoop("animation.box_block.open_state");
    private static final RawAnimation CLOSE_ANIMATION = RawAnimation.begin().thenPlay("animation.box_block.close");

    // Box state
    private boolean isOpen = false;

    // Collection and figure data
    private String figureId = ""; // Empty means no figure
    private String collectionIdOverride = null; // For dynamic collections using default box blocks
    private String colorOverride = null; // For color variant boxes
    private boolean isFigureExtracted = false; // Whether the figure has been taken out
    private int alternativeSkinIndex = 0; // 0 is default, 1+ are from the alternatives list
    private String skinSnapshot = null; // Saved skin snapshot URL for player figures (Mojang)
    private String quickSkinId = null; // Saved Quick Skin ID (for Quick Skin mod compatibility)

    // Figure pose index (0 = standing, 1 = sitting)
    private int poseIndex = 0;

    // Figure positioning - correct values found through testing
    private double figureOffsetX = -0.53;
    private double figureOffsetY = 0.01;
    private double figureOffsetZ = -0.55;
    private double figureScale = 1.0;

    // Hitbox offset - allows fine-tuning hitbox position
    private double hitboxOffsetX = 0.0;
    private double hitboxOffsetY = 0.006;
    private double hitboxOffsetZ = 0.0;

    // Hitbox scale - allows adjusting hitbox size on each axis
    private double hitboxScaleX = 1.10;
    private double hitboxScaleY = 1.00;
    private double hitboxScaleZ = 0.90;

    // Logo configuration - allows adjusting logo position and scale per box
    // null values mean use the collection's default configuration
    private Double logoPositionX = null;
    private Double logoPositionY = null;
    private Double logoPositionZ = null;
    private Double logoScaleX = null;
    private Double logoScaleY = null;
    private Double logoScaleZ = null;

    // Hide logo flag - used for UI displays like the color selection screen
    private boolean hideLogo = false;

    public BoxBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.BOX_BLOCK.get(), pos, blockState);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Controller for the box model animations with state-based logic
        controllers.add(new AnimationController<>(this, "box_controller", 0, state -> {
            // Skip animations for UI rendering (entities at BlockPos.ZERO)
            if (getBlockPos().equals(BlockPos.ZERO)) {
                return PlayState.STOP;
            }

            // If the box is open, play the open state animation (holds at final frame)
            if (isOpen) {
                return state.setAndContinue(OPEN_STATE_ANIMATION);
            }
            // If the box is closed, play the idle animation
            return state.setAndContinue(IDLE_ANIMATION);
        })
                .triggerableAnim("open", OPEN_ANIMATION)
                .triggerableAnim("close", CLOSE_ANIMATION)
                .setAnimationSpeed(1.2)); // 20% faster animations

        // Controller for figure pose animations (5 ticks = 0.25 seconds transition)
        controllers.add(new AnimationController<>(this, "figure_pose_controller", 5, state -> {
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

    /**
     * Gets the collection ID stored in NBT.
     */
    public String getCollectionId() {
        if (collectionIdOverride != null && !collectionIdOverride.isEmpty()) {
            return collectionIdOverride;
        }
        return CollectionRegistry.getDefaultCollection()
                .map(collection -> collection.getId())
                .orElse("");
    }

    @Nullable
    public PopBlockColor getColor() {
        if (colorOverride != null && !colorOverride.isEmpty()) {
            try {
                return PopBlockColor.valueOf(colorOverride.toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    public String getFigureId() {
        return figureId;
    }

    public void setFigureId(String figureId) {
        this.figureId = figureId != null ? figureId : "";
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public FigureDefinition getFigureDefinition() {
        if (figureId.isEmpty()) {
            return null;
        }
        return CollectionRegistry.getFigure(getCollectionId(), figureId).orElse(null);
    }

    public boolean hasFigure() {
        return !figureId.isEmpty() && getFigureDefinition() != null;
    }

    public boolean isFigureExtracted() {
        return isFigureExtracted;
    }

    public void setFigureExtracted(boolean extracted) {
        this.isFigureExtracted = extracted;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
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
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public void setFigureScale(double scale) {
        this.figureScale = scale;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public double getHitboxOffsetX() { return hitboxOffsetX; }
    public double getHitboxOffsetY() { return hitboxOffsetY; }
    public double getHitboxOffsetZ() { return hitboxOffsetZ; }
    public double getHitboxScaleX() { return hitboxScaleX; }
    public double getHitboxScaleY() { return hitboxScaleY; }
    public double getHitboxScaleZ() { return hitboxScaleZ; }

    public void setHitboxOffset(double x, double y, double z) {
        this.hitboxOffsetX = x;
        this.hitboxOffsetY = y;
        this.hitboxOffsetZ = z;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public void setHitboxScale(double scaleX, double scaleY, double scaleZ) {
        this.hitboxScaleX = scaleX;
        this.hitboxScaleY = scaleY;
        this.hitboxScaleZ = scaleZ;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public Double getLogoPositionX() { return logoPositionX; }
    public Double getLogoPositionY() { return logoPositionY; }
    public Double getLogoPositionZ() { return logoPositionZ; }
    public Double getLogoScaleX() { return logoScaleX; }
    public Double getLogoScaleY() { return logoScaleY; }
    public Double getLogoScaleZ() { return logoScaleZ; }
    public boolean isHideLogo() { return hideLogo; }

    public void setLogoPosition(Double x, Double y, Double z) {
        this.logoPositionX = x;
        this.logoPositionY = y;
        this.logoPositionZ = z;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public void setLogoScale(Double scaleX, Double scaleY, Double scaleZ) {
        this.logoScaleX = scaleX;
        this.logoScaleY = scaleY;
        this.logoScaleZ = scaleZ;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public void setCollectionIdOverride(String collectionId) {
        this.collectionIdOverride = collectionId;
        setChanged();
    }

    public void setColorOverride(String color) {
        this.colorOverride = color;
        setChanged();
    }

    public int getAlternativeSkinIndex() {
        return alternativeSkinIndex;
    }

    public String getSkinSnapshot() {
        return skinSnapshot;
    }

    public void setSkinSnapshot(String skinSnapshot) {
        this.skinSnapshot = skinSnapshot;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public String getQuickSkinId() {
        return quickSkinId;
    }

    public void setQuickSkinId(String quickSkinId) {
        this.quickSkinId = quickSkinId;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public void cycleAlternativeSkin() {
        FigureDefinition def = getFigureDefinition();
        if (def == null || !def.hasAlternatives()) {
            return;
        }
        int totalSkins = 1 + def.getAlternatives().size();
        this.alternativeSkinIndex = (this.alternativeSkinIndex + 1) % totalSkins;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public int getPoseIndex() {
        return poseIndex;
    }

    public void cyclePose() {
        // Toggles between 0 (Standing) and 1 (Sitting)
        this.poseIndex = (this.poseIndex + 1) % 2;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public boolean isOpen() {
        return isOpen;
    }

    public void toggleOpen() {
        if (level != null && !level.isClientSide) {
            isOpen = !isOpen;
            if (isOpen) {
                triggerAnim("box_controller", "open");
            } else {
                triggerAnim("box_controller", "close");
            }
            setChanged();
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    @Deprecated
    public void triggerOpenAnimation() {
        if (level != null && !level.isClientSide) {
            if (!isOpen) {
                toggleOpen();
            }
        }
    }

    @Override
    //? if >=1.21 {
    /*protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
    *///? } else {
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
    //? }
        tag.putBoolean("IsOpen", isOpen);
        tag.putString("FigureId", figureId);
        tag.putBoolean("IsFigureExtracted", isFigureExtracted);
        tag.putInt("AlternativeSkinIndex", alternativeSkinIndex);
        tag.putInt("PoseIndex", poseIndex);
        if (skinSnapshot != null) tag.putString("SkinSnapshot", skinSnapshot);
        if (quickSkinId != null) tag.putString("QuickSkinId", quickSkinId);
        if (collectionIdOverride != null) tag.putString("CollectionId", collectionIdOverride);
        if (colorOverride != null) tag.putString("Color", colorOverride);
        tag.putDouble("FigureOffsetX", figureOffsetX);
        tag.putDouble("FigureOffsetY", figureOffsetY);
        tag.putDouble("FigureOffsetZ", figureOffsetZ);
        tag.putDouble("FigureScale", figureScale);
        tag.putDouble("HitboxOffsetX", hitboxOffsetX);
        tag.putDouble("HitboxOffsetY", hitboxOffsetY);
        tag.putDouble("HitboxOffsetZ", hitboxOffsetZ);
        tag.putDouble("HitboxScaleX", hitboxScaleX);
        tag.putDouble("HitboxScaleY", hitboxScaleY);
        tag.putDouble("HitboxScaleZ", hitboxScaleZ);
        if (logoPositionX != null) tag.putDouble("LogoPositionX", logoPositionX);
        if (logoPositionY != null) tag.putDouble("LogoPositionY", logoPositionY);
        if (logoPositionZ != null) tag.putDouble("LogoPositionZ", logoPositionZ);
        if (logoScaleX != null) tag.putDouble("LogoScaleX", logoScaleX);
        if (logoScaleY != null) tag.putDouble("LogoScaleY", logoScaleY);
        tag.putBoolean("HideLogo", hideLogo);
    }

    @Override
    //? if >=1.21 {
    /*protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
    *///? } else {
    public void load(CompoundTag tag) {
        super.load(tag);
    //? }
    //? if >=1.21 {
    /*    loadForItemRendering(tag);
    }

    public void loadForItemRendering(CompoundTag tag) {
    *///? }
        this.isOpen = tag.contains("IsOpen") ? tag.getBoolean("IsOpen") : false;
        if (tag.contains("FigureId")) this.figureId = tag.getString("FigureId");
        if (tag.contains("IsFigureExtracted")) this.isFigureExtracted = tag.getBoolean("IsFigureExtracted");
        if (tag.contains("AlternativeSkinIndex")) this.alternativeSkinIndex = tag.getInt("AlternativeSkinIndex");
        if (tag.contains("PoseIndex")) this.poseIndex = tag.getInt("PoseIndex");
        this.skinSnapshot = tag.contains("SkinSnapshot", 8) ? tag.getString("SkinSnapshot") : null;
        this.quickSkinId = tag.contains("QuickSkinId", 8) ? tag.getString("QuickSkinId") : null;
        if (tag.contains("CollectionId")) this.collectionIdOverride = tag.getString("CollectionId");
        if (tag.contains("Color")) this.colorOverride = tag.getString("Color");
        if (tag.contains("FigureOffsetX")) this.figureOffsetX = tag.getDouble("FigureOffsetX");
        if (tag.contains("FigureOffsetY")) this.figureOffsetY = tag.getDouble("FigureOffsetY");
        if (tag.contains("FigureOffsetZ")) this.figureOffsetZ = tag.getDouble("FigureOffsetZ");
        if (tag.contains("FigureScale")) this.figureScale = tag.getDouble("FigureScale");
        if (tag.contains("HitboxOffsetX")) this.hitboxOffsetX = tag.getDouble("HitboxOffsetX");
        if (tag.contains("HitboxOffsetY")) this.hitboxOffsetY = tag.getDouble("HitboxOffsetY");
        if (tag.contains("HitboxOffsetZ")) this.hitboxOffsetZ = tag.getDouble("HitboxOffsetZ");
        if (tag.contains("HitboxScaleX")) this.hitboxScaleX = tag.getDouble("HitboxScaleX");
        if (tag.contains("HitboxScaleY")) this.hitboxScaleY = tag.getDouble("HitboxScaleY");
        if (tag.contains("HitboxScaleZ")) this.hitboxScaleZ = tag.getDouble("HitboxScaleZ");
        this.logoPositionX = tag.contains("LogoPositionX") ? tag.getDouble("LogoPositionX") : null;
        this.logoPositionY = tag.contains("LogoPositionY") ? tag.getDouble("LogoPositionY") : null;
        this.logoPositionZ = tag.contains("LogoPositionZ") ? tag.getDouble("LogoPositionZ") : null;
        this.logoScaleX = tag.contains("LogoScaleX") ? tag.getDouble("LogoScaleX") : null;
        this.logoScaleY = tag.contains("LogoScaleY") ? tag.getDouble("LogoScaleY") : null;
        if (tag.contains("HideLogo")) this.hideLogo = tag.getBoolean("HideLogo");
    }

    @Override
    //? if >=1.21 {
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
    //? if >=1.21 {
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
            /*loadAdditional(tag, registries);
            *///? } else {
            load(tag);
            //? }
            if (level != null && level.isClientSide) {
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            }
        }
    }

    //? if >=1.21 {
    /*// 1.21.2 removed BlockEntity.saveToItem, so this is a local helper there.
    public void saveToItem(net.minecraft.world.item.ItemStack stack, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
    *///? } else {
    public void saveToItem(net.minecraft.world.item.ItemStack stack) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
    //? }
        tag.putBoolean("IsOpen", false);
        //? if >=1.21 {
        /*BlockEntityItemData.write(stack, tag, "blockpops:box_block");
        *///? } else {
        stack.addTagElement("BlockEntityTag", tag);
        //? }
    }

    public static <T extends BlockEntity> void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
        if (level.isClientSide && blockEntity instanceof BoxBlockEntity boxBlockEntity) {
            // Animation ticking handled automatically by GeckoLib
        }
    }
}
