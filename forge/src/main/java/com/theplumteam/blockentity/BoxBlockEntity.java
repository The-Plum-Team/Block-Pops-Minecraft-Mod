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
import software.bernie.geckolib.util.GeckoLibUtil;

public class BoxBlockEntity extends BlockEntity implements GeoBlockEntity {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoxBlockEntity.class);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private static final RawAnimation BOX_ANIMATION = RawAnimation.begin().thenLoop("animation.box_block.idle");

    // Collection and figure data
    private String figureId = ""; // Empty means no figure
    private String collectionIdOverride = null; // For dynamic collections using default box blocks

    // Figure positioning - correct values found through testing
    private double figureOffsetX = -0.60;
    private double figureOffsetY = 0.01;
    private double figureOffsetZ = -0.55;
    private double figureScale = 1.0;

    // Hitbox offset - allows fine-tuning hitbox position
    private double hitboxOffsetX = -0.03;
    private double hitboxOffsetY = 0.00;
    private double hitboxOffsetZ = -0.06;

    // Logo configuration - allows adjusting logo position and scale per box
    // null values mean use the collection's default configuration
    private Double logoPositionX = null;
    private Double logoPositionY = null;
    private Double logoPositionZ = null;
    private Double logoScaleX = null;
    private Double logoScaleY = null;

    public BoxBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.BOX_BLOCK.get(), pos, blockState);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Controller for the box model animations
        controllers.add(new AnimationController<>(this, "box_controller", 0, state ->
            state.setAndContinue(BOX_ANIMATION)
        ));

        // Note: Figure animations are handled by the separate figure renderer
        // No controller needed here since we're using a separate GeoBlockRenderer for the figure
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    /**
     * Gets the collection ID from the block this entity belongs to.
     * If a collection ID override is set in NBT (for dynamic collections), that takes precedence.
     */
    public String getCollectionId() {
        // Check if there's an override from NBT (for dynamic collections like world_players)
        if (collectionIdOverride != null && !collectionIdOverride.isEmpty()) {
            return collectionIdOverride;
        }
        // Otherwise, get from the block
        if (getBlockState().getBlock() instanceof BoxBlock boxBlock) {
            String collectionId = boxBlock.getCollectionId();
            // Color variant boxes have null collection ID - return first available collection
            if (collectionId == null) {
                return CollectionRegistry.getDefaultCollection()
                    .map(collection -> collection.getId())
                    .orElse("");
            }
            return collectionId;
        }
        return "";
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

    public void setHitboxOffset(double x, double y, double z) {
        this.hitboxOffsetX = x;
        this.hitboxOffsetY = y;
        this.hitboxOffsetZ = z;
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

    public void setLogoPosition(Double x, Double y, Double z) {
        this.logoPositionX = x;
        this.logoPositionY = y;
        this.logoPositionZ = z;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public void setLogoScale(Double scaleX, Double scaleY) {
        this.logoScaleX = scaleX;
        this.logoScaleY = scaleY;
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
        tag.putString("FigureId", figureId);
        if (collectionIdOverride != null) {
            tag.putString("CollectionId", collectionIdOverride);
        }
        tag.putDouble("FigureOffsetX", figureOffsetX);
        tag.putDouble("FigureOffsetY", figureOffsetY);
        tag.putDouble("FigureOffsetZ", figureOffsetZ);
        tag.putDouble("FigureScale", figureScale);
        tag.putDouble("HitboxOffsetX", hitboxOffsetX);
        tag.putDouble("HitboxOffsetY", hitboxOffsetY);
        tag.putDouble("HitboxOffsetZ", hitboxOffsetZ);
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
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("FigureId")) {
            this.figureId = tag.getString("FigureId");
        }
        if (tag.contains("CollectionId")) {
            this.collectionIdOverride = tag.getString("CollectionId");
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
        stack.addTagElement("BlockEntityTag", tag);
    }

    public static <T extends BlockEntity> void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
        if (level.isClientSide && blockEntity instanceof BoxBlockEntity boxBlockEntity) {
            // Animation ticking handled automatically by GeckoLib
        }
    }
}
