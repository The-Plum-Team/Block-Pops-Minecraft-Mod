package com.theplumteam.blockentity;

import com.theplumteam.block.BoxBlock;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureDefinition;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

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
    private String skinSnapshot = null; // Saved skin snapshot URL for player figures

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
            // This prevents "Unable to find animation" warnings in inventory/GUI
            // The visual state is determined by the model's default pose
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

        // Note: Figure animations are handled by the separate figure renderer
        // No controller needed here since we're using a separate GeoBlockRenderer for the figure
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    /**
     * Gets the collection ID stored in NBT.
     * Collection is now determined by NBT data, not by block type.
     */
    public String getCollectionId() {
        // Return the stored collection ID (may be null/empty)
        if (collectionIdOverride != null && !collectionIdOverride.isEmpty()) {
            return collectionIdOverride;
        }
        // If no collection is set, return default collection
        return CollectionRegistry.getDefaultCollection()
                .map(collection -> collection.getId())
                .orElse("");
    }

    /**
     * Gets the color stored in NBT for this box.
     * @return The PopBlockColor, or null if not set
     */
    @Nullable
    public com.theplumteam.block.PopBlockColor getColor() {
        if (colorOverride != null && !colorOverride.isEmpty()) {
            try {
                return com.theplumteam.block.PopBlockColor.valueOf(colorOverride.toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Gets the ID of the figure currently in this box
     */
    public String getFigureId() {
        return figureId;
    }

    /**
     * Sets which figure is in this box (by figure ID within the collection)
     */
    public void setFigureId(String figureId) {
        this.figureId = figureId != null ? figureId : "";
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * Gets the full FigureDefinition for the current figure
     */
    public FigureDefinition getFigureDefinition() {
        if (figureId.isEmpty()) {
            return null;
        }
        return CollectionRegistry.getFigure(getCollectionId(), figureId).orElse(null);
    }

    /**
     * Checks if this box currently contains a figure
     */
    public boolean hasFigure() {
        return !figureId.isEmpty() && getFigureDefinition() != null;
    }

    /**
     * Checks if the figure has been extracted from the box
     */
    public boolean isFigureExtracted() {
        return isFigureExtracted;
    }

    /**
     * Sets whether the figure has been extracted from the box
     */
    public void setFigureExtracted(boolean extracted) {
        this.isFigureExtracted = extracted;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public double getFigureOffsetX() {
        return figureOffsetX;
    }

    public double getFigureOffsetY() {
        return figureOffsetY;
    }

    public double getFigureOffsetZ() {
        return figureOffsetZ;
    }

    public double getFigureScale() {
        return figureScale;
    }

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

    public double getHitboxOffsetX() {
        return hitboxOffsetX;
    }

    public double getHitboxOffsetY() {
        return hitboxOffsetY;
    }

    public double getHitboxOffsetZ() {
        return hitboxOffsetZ;
    }

    public double getHitboxScaleX() {
        return hitboxScaleX;
    }

    public double getHitboxScaleY() {
        return hitboxScaleY;
    }

    public double getHitboxScaleZ() {
        return hitboxScaleZ;
    }

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

    public Double getLogoPositionX() {
        return logoPositionX;
    }

    public Double getLogoPositionY() {
        return logoPositionY;
    }

    public Double getLogoPositionZ() {
        return logoPositionZ;
    }

    public Double getLogoScaleX() {
        return logoScaleX;
    }

    public Double getLogoScaleY() {
        return logoScaleY;
    }

    public Double getLogoScaleZ() {
        return logoScaleZ;
    }

    public boolean isHideLogo() {
        return hideLogo;
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

    /**
     * Sets the collection ID override (used for dynamic collections like world_players)
     */
    public void setCollectionIdOverride(String collectionId) {
        this.collectionIdOverride = collectionId;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("IsOpen", isOpen);
        tag.putString("FigureId", figureId);
        tag.putBoolean("IsFigureExtracted", isFigureExtracted);
        tag.putInt("AlternativeSkinIndex", alternativeSkinIndex);
        if (skinSnapshot != null) {
            tag.putString("SkinSnapshot", skinSnapshot);
        }
        if (collectionIdOverride != null) {
            tag.putString("CollectionId", collectionIdOverride);
        }
        if (colorOverride != null) {
            tag.putString("Color", colorOverride);
        }
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
        if (logoPositionX != null) {
            tag.putDouble("LogoPositionX", logoPositionX);
        }
        if (logoPositionY != null) {
            tag.putDouble("LogoPositionY", logoPositionY);
        }
        if (logoPositionZ != null) {
            tag.putDouble("LogoPositionZ", logoPositionZ);
        }
        if (logoScaleX != null) {
            tag.putDouble("LogoScaleX", logoScaleX);
        }
        if (logoScaleY != null) {
            tag.putDouble("LogoScaleY", logoScaleY);
        }
        tag.putBoolean("HideLogo", hideLogo);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        // Always set values explicitly to avoid state pollution
        this.isOpen = tag.contains("IsOpen") ? tag.getBoolean("IsOpen") : false;

        if (tag.contains("FigureId")) {
            this.figureId = tag.getString("FigureId");
        }
        if (tag.contains("IsFigureExtracted")) {
            this.isFigureExtracted = tag.getBoolean("IsFigureExtracted");
        }
        if (tag.contains("AlternativeSkinIndex")) {
            this.alternativeSkinIndex = tag.getInt("AlternativeSkinIndex");
        }
        if (tag.contains("SkinSnapshot", 8)) { // 8 is Tag.TAG_STRING
            this.skinSnapshot = tag.getString("SkinSnapshot");
        } else {
            this.skinSnapshot = null;
        }
        if (tag.contains("CollectionId")) {
            this.collectionIdOverride = tag.getString("CollectionId");
        }
        if (tag.contains("Color")) {
            this.colorOverride = tag.getString("Color");
        }
        if (tag.contains("FigureOffsetX")) {
            this.figureOffsetX = tag.getDouble("FigureOffsetX");
        }
        if (tag.contains("FigureOffsetY")) {
            this.figureOffsetY = tag.getDouble("FigureOffsetY");
        }
        if (tag.contains("FigureOffsetZ")) {
            this.figureOffsetZ = tag.getDouble("FigureOffsetZ");
        }
        if (tag.contains("FigureScale")) {
            this.figureScale = tag.getDouble("FigureScale");
        }
        if (tag.contains("HitboxOffsetX")) {
            this.hitboxOffsetX = tag.getDouble("HitboxOffsetX");
        }
        if (tag.contains("HitboxOffsetY")) {
            this.hitboxOffsetY = tag.getDouble("HitboxOffsetY");
        }
        if (tag.contains("HitboxOffsetZ")) {
            this.hitboxOffsetZ = tag.getDouble("HitboxOffsetZ");
        }
        if (tag.contains("HitboxScaleX")) {
            this.hitboxScaleX = tag.getDouble("HitboxScaleX");
        }
        if (tag.contains("HitboxScaleY")) {
            this.hitboxScaleY = tag.getDouble("HitboxScaleY");
        }
        if (tag.contains("HitboxScaleZ")) {
            this.hitboxScaleZ = tag.getDouble("HitboxScaleZ");
        }
        if (tag.contains("LogoPositionX")) {
            this.logoPositionX = tag.getDouble("LogoPositionX");
        } else {
            this.logoPositionX = null;
        }
        if (tag.contains("LogoPositionY")) {
            this.logoPositionY = tag.getDouble("LogoPositionY");
        } else {
            this.logoPositionY = null;
        }
        if (tag.contains("LogoPositionZ")) {
            this.logoPositionZ = tag.getDouble("LogoPositionZ");
        } else {
            this.logoPositionZ = null;
        }
        if (tag.contains("LogoScaleX")) {
            this.logoScaleX = tag.getDouble("LogoScaleX");
        } else {
            this.logoScaleX = null;
        }
        if (tag.contains("LogoScaleY")) {
            this.logoScaleY = tag.getDouble("LogoScaleY");
        } else {
            this.logoScaleY = null;
        }
        if (tag.contains("HideLogo")) {
            this.hideLogo = tag.getBoolean("HideLogo");
        }
    }

    // ===== CHUNK LOAD SYNCHRONIZATION =====
    // getUpdateTag() and handleUpdateTag() are used when chunks are loaded
    // NOTE: getUpdateTag() is ALSO used by getUpdatePacket() for real-time sync!
    // These ensure the client has the correct data when entering the area

    @Override
    public CompoundTag getUpdateTag() {
        // This is sent to the client when the chunk loads AND for real-time updates
        // ClientboundBlockEntityDataPacket.create(this) internally calls this method
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        // This is received on the client during chunk load
        load(tag);
    }

    // ===== REAL-TIME SYNCHRONIZATION =====
    // getUpdatePacket() and onDataPacket() are used for real-time updates
    // These are triggered by level.sendBlockUpdated() and deliver changes immediately

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        // Creates a packet for real-time synchronization
        // Called on the server when level.sendBlockUpdated() is invoked
        // ClientboundBlockEntityDataPacket.create(this) calls getUpdateTag() to get the data
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        // Receives the packet on the client for real-time updates
        // This is what actually makes the changes appear immediately
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            load(tag);
            // Request a render update so the changes are visible immediately
            if (level != null && level.isClientSide) {
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            }
        }
    }

    /**
     * Saves this block entity's data to an ItemStack
     */
    public void saveToItem(net.minecraft.world.item.ItemStack stack) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        // Always drop the box in closed state
        tag.putBoolean("IsOpen", false);
        stack.addTagElement("BlockEntityTag", tag);
    }

    /**
     * Gets the current alternative skin index
     */
    public int getAlternativeSkinIndex() {
        return alternativeSkinIndex;
    }

    /**
     * Gets the saved skin snapshot URL for this figure.
     * @return The skin snapshot URL, or null if not set.
     */
    public String getSkinSnapshot() {
        return skinSnapshot;
    }

    /**
     * Sets the skin snapshot URL for this figure.
     */
    public void setSkinSnapshot(String skinSnapshot) {
        this.skinSnapshot = skinSnapshot;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * Cycles to the next alternative skin
     */
    public void cycleAlternativeSkin() {
        FigureDefinition def = getFigureDefinition();
        if (def == null || !def.hasAlternatives()) {
            return; // No figure or no alternatives to cycle.
        }

        int totalSkins = 1 + def.getAlternatives().size(); // 1 for the default skin
        this.alternativeSkinIndex = (this.alternativeSkinIndex + 1) % totalSkins;

        // Mark for saving and send an update to the client.
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * Checks if the box is currently open
     */
    public boolean isOpen() {
        return isOpen;
    }

    /**
     * Toggles the box between open and closed states
     */
    public void toggleOpen() {
        if (level != null && !level.isClientSide) {
            isOpen = !isOpen;

            // Trigger the appropriate animation
            if (isOpen) {
                triggerAnim("box_controller", "open");
            } else {
                triggerAnim("box_controller", "close");
            }

            setChanged();
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * Triggers the box opening animation (legacy method for compatibility)
     * @deprecated Use toggleOpen() instead
     */
    @Deprecated
    public void triggerOpenAnimation() {
        if (level != null && !level.isClientSide) {
            if (!isOpen) {
                toggleOpen();
            }
        }
    }

    public static <T extends BlockEntity> void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
        if (level.isClientSide && blockEntity instanceof BoxBlockEntity boxBlockEntity) {
            // Animation ticking handled automatically by GeckoLib
        }
    }
}
