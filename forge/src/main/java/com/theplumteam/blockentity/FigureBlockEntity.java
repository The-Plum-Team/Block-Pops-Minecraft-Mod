package com.theplumteam.blockentity;

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

public class FigureBlockEntity extends BlockEntity implements GeoBlockEntity {
    private static final Logger LOGGER = LoggerFactory.getLogger(FigureBlockEntity.class);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // Figure data
    private String figureId = "";
    private String collectionId = "";

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
        // No animation controller needed - figures use their own animations from FigureDefinition
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    /**
     * Gets the collection ID for this figure
     */
    public String getCollectionId() {
        return collectionId;
    }

    /**
     * Sets the collection ID for this figure
     */
    public void setCollectionId(String collectionId) {
        this.collectionId = collectionId != null ? collectionId : "";
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * Gets the ID of the figure
     */
    public String getFigureId() {
        return figureId;
    }

    /**
     * Sets which figure this is (by figure ID within the collection)
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
        if (figureId.isEmpty() || collectionId.isEmpty()) {
            return null;
        }
        return CollectionRegistry.getFigure(collectionId, figureId).orElse(null);
    }

    /**
     * Checks if this block currently has a valid figure
     */
    public boolean hasFigure() {
        return !figureId.isEmpty() && !collectionId.isEmpty() && getFigureDefinition() != null;
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

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("FigureId", figureId);
        tag.putString("CollectionId", collectionId);
        tag.putDouble("FigureOffsetX", figureOffsetX);
        tag.putDouble("FigureOffsetY", figureOffsetY);
        tag.putDouble("FigureOffsetZ", figureOffsetZ);
        tag.putDouble("FigureScale", figureScale);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("FigureId")) {
            this.figureId = tag.getString("FigureId");
        }
        if (tag.contains("CollectionId")) {
            this.collectionId = tag.getString("CollectionId");
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
    }

    // ===== CHUNK LOAD SYNCHRONIZATION =====
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    }

    // ===== REAL-TIME SYNCHRONIZATION =====
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            load(tag);
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
        if (level.isClientSide && blockEntity instanceof FigureBlockEntity figureBlockEntity) {
            // Animation ticking handled automatically by GeckoLib
        }
    }
}
