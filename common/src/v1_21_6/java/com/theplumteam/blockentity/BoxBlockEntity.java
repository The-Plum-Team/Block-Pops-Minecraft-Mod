package com.theplumteam.blockentity;

import com.mojang.serialization.Codec;
import com.theplumteam.block.PopBlockColor;
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
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import org.jetbrains.annotations.Nullable;

public class BoxBlockEntity extends BlockEntity implements GeoBlockEntity {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoxBlockEntity.class);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private static final RawAnimation IDLE_ANIMATION = RawAnimation.begin().thenLoop("animation.box_block.idle");
    private static final RawAnimation OPEN_ANIMATION = RawAnimation.begin().thenPlay("animation.box_block.open").thenLoop("animation.box_block.open_state");
    private static final RawAnimation OPEN_STATE_ANIMATION = RawAnimation.begin().thenLoop("animation.box_block.open_state");
    private static final RawAnimation CLOSE_ANIMATION = RawAnimation.begin().thenPlay("animation.box_block.close").thenLoop("animation.box_block.idle");

    // Box state
    private boolean isOpen = false;
    // Track the previous state to detect transitions
    private transient boolean wasOpen = false;
    // Track if we're currently playing a transition animation
    private transient boolean isTransitioning = false;
    // Track remaining ticks for the transition
    private transient int transitionTicks = 0;
    // Duration of transition animation: 0.625s at 1.2x speed = ~10 ticks
    private static final int TRANSITION_DURATION = 11;

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
        controllers.add(new AnimationController<>("box_controller", 0, state -> {
            // Skip animations for UI rendering (widget entities use Y=256 or BlockPos.ZERO)
            if (getBlockPos().equals(BlockPos.ZERO) || getBlockPos().getY() == 256) {
                return PlayState.STOP;
            }

            // If we're currently transitioning, let the animation continue
            if (isTransitioning) {
                // Check if the transition animation has finished
                // The chained animation will automatically continue to the looping part
                return PlayState.CONTINUE;
            }

            // Detect state changes
            if (isOpen != wasOpen) {
                wasOpen = isOpen;
                isTransitioning = true;
                transitionTicks = TRANSITION_DURATION;
                // State changed - play transition animation (which is chained to loop the final state)
                if (isOpen) {
                    return state.setAndContinue(OPEN_ANIMATION);
                } else {
                    return state.setAndContinue(CLOSE_ANIMATION);
                }
            }

            // No state change and not transitioning - maintain current state
            if (isOpen) {
                return state.setAndContinue(OPEN_STATE_ANIMATION);
            } else {
                return state.setAndContinue(IDLE_ANIMATION);
            }
        })
                .setAnimationSpeed(1.2));

        // Controller for figure pose animations (5 ticks = 0.25 seconds transition)
        controllers.add(new AnimationController<>("figure_pose_controller", 5, state -> {
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

    public void setHideLogo(boolean hideLogo) {
        this.hideLogo = hideLogo;
    }

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
        FigureDefinition def = getFigureDefinition();
        if (def != null && def.isPoseLocked()) return;
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
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putBoolean("IsOpen", isOpen);
        output.putString("FigureId", figureId);
        output.putBoolean("IsFigureExtracted", isFigureExtracted);
        output.putInt("AlternativeSkinIndex", alternativeSkinIndex);
        output.putInt("PoseIndex", poseIndex);
        output.storeNullable("SkinSnapshot", Codec.STRING, skinSnapshot);
        output.storeNullable("QuickSkinId", Codec.STRING, quickSkinId);
        output.storeNullable("CollectionId", Codec.STRING, collectionIdOverride);
        output.storeNullable("Color", Codec.STRING, colorOverride);
        output.putDouble("FigureOffsetX", figureOffsetX);
        output.putDouble("FigureOffsetY", figureOffsetY);
        output.putDouble("FigureOffsetZ", figureOffsetZ);
        output.putDouble("FigureScale", figureScale);
        output.putDouble("HitboxOffsetX", hitboxOffsetX);
        output.putDouble("HitboxOffsetY", hitboxOffsetY);
        output.putDouble("HitboxOffsetZ", hitboxOffsetZ);
        output.putDouble("HitboxScaleX", hitboxScaleX);
        output.putDouble("HitboxScaleY", hitboxScaleY);
        output.putDouble("HitboxScaleZ", hitboxScaleZ);
        output.storeNullable("LogoPositionX", Codec.DOUBLE, logoPositionX);
        output.storeNullable("LogoPositionY", Codec.DOUBLE, logoPositionY);
        output.storeNullable("LogoPositionZ", Codec.DOUBLE, logoPositionZ);
        output.storeNullable("LogoScaleX", Codec.DOUBLE, logoScaleX);
        output.storeNullable("LogoScaleY", Codec.DOUBLE, logoScaleY);
        output.putBoolean("HideLogo", hideLogo);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.isOpen = input.getBooleanOr("IsOpen", false);
        this.figureId = input.getStringOr("FigureId", this.figureId);
        this.isFigureExtracted = input.getBooleanOr("IsFigureExtracted", false);
        this.alternativeSkinIndex = input.getIntOr("AlternativeSkinIndex", 0);
        this.poseIndex = input.getIntOr("PoseIndex", 0);
        this.skinSnapshot = input.getString("SkinSnapshot").orElse(null);
        this.quickSkinId = input.getString("QuickSkinId").orElse(null);
        this.collectionIdOverride = input.getString("CollectionId").orElse(null);
        this.colorOverride = input.getString("Color").orElse(null);
        this.figureOffsetX = input.getDoubleOr("FigureOffsetX", this.figureOffsetX);
        this.figureOffsetY = input.getDoubleOr("FigureOffsetY", this.figureOffsetY);
        this.figureOffsetZ = input.getDoubleOr("FigureOffsetZ", this.figureOffsetZ);
        this.figureScale = input.getDoubleOr("FigureScale", this.figureScale);
        this.hitboxOffsetX = input.getDoubleOr("HitboxOffsetX", this.hitboxOffsetX);
        this.hitboxOffsetY = input.getDoubleOr("HitboxOffsetY", this.hitboxOffsetY);
        this.hitboxOffsetZ = input.getDoubleOr("HitboxOffsetZ", this.hitboxOffsetZ);
        this.hitboxScaleX = input.getDoubleOr("HitboxScaleX", this.hitboxScaleX);
        this.hitboxScaleY = input.getDoubleOr("HitboxScaleY", this.hitboxScaleY);
        this.hitboxScaleZ = input.getDoubleOr("HitboxScaleZ", this.hitboxScaleZ);
        this.logoPositionX = input.read("LogoPositionX", Codec.DOUBLE).orElse(null);
        this.logoPositionY = input.read("LogoPositionY", Codec.DOUBLE).orElse(null);
        this.logoPositionZ = input.read("LogoPositionZ", Codec.DOUBLE).orElse(null);
        this.logoScaleX = input.read("LogoScaleX", Codec.DOUBLE).orElse(null);
        this.logoScaleY = input.read("LogoScaleY", Codec.DOUBLE).orElse(null);
        this.hideLogo = input.getBooleanOr("HideLogo", false);
        applyDefinitionDefaults();
    }

    private void applyDefinitionDefaults() {
        FigureDefinition def = getFigureDefinition();
        if (def != null) {
            if (def.getOffsetX() != 0.0f && this.figureOffsetX == -0.53) {
                this.figureOffsetX = def.getOffsetX();
            }
            if (def.getOffsetZ() != 0.0f && this.figureOffsetZ == -0.55) {
                this.figureOffsetZ = def.getOffsetZ();
            }
        }
    }

    /**
     * Public method to load NBT data when rendering items.
     * This is used by BoxBlockItemRenderer which cannot access protected loadAdditional.
     */
    public void loadFromItemNbt(CompoundTag tag) {
        this.isOpen = tag.getBooleanOr("IsOpen", false);
        this.figureId = tag.getStringOr("FigureId", this.figureId);
        this.isFigureExtracted = tag.getBooleanOr("IsFigureExtracted", false);
        this.alternativeSkinIndex = tag.getIntOr("AlternativeSkinIndex", 0);
        this.poseIndex = tag.getIntOr("PoseIndex", 0);
        this.skinSnapshot = tag.getString("SkinSnapshot").orElse(null);
        this.quickSkinId = tag.getString("QuickSkinId").orElse(null);
        this.collectionIdOverride = tag.getString("CollectionId").orElse(null);
        this.colorOverride = tag.getString("Color").orElse(null);
        this.figureOffsetX = tag.getDoubleOr("FigureOffsetX", this.figureOffsetX);
        this.figureOffsetY = tag.getDoubleOr("FigureOffsetY", this.figureOffsetY);
        this.figureOffsetZ = tag.getDoubleOr("FigureOffsetZ", this.figureOffsetZ);
        this.figureScale = tag.getDoubleOr("FigureScale", this.figureScale);
        this.hideLogo = tag.getBooleanOr("HideLogo", false);
        applyDefinitionDefaults();
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
        tag.putBoolean("IsOpen", false);
        // Required in 1.21+ - block entity type ID must be present
        tag.putString("id", "blockpops:box_block");
        stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag));
    }

    public static <T extends BlockEntity> void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
        if (level.isClientSide && blockEntity instanceof BoxBlockEntity boxBlockEntity) {
            // Decrement the transition timer
            if (boxBlockEntity.transitionTicks > 0) {
                boxBlockEntity.transitionTicks--;
                if (boxBlockEntity.transitionTicks == 0) {
                    boxBlockEntity.isTransitioning = false;
                }
            }
        }
    }
}
