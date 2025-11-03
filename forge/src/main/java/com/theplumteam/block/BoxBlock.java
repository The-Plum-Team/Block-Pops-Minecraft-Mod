package com.theplumteam.block;

import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.gui.FigurePositionScreen;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

public class BoxBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    // Hitbox matching the actual box model size
    // Width: 10 units, Height: 14 units, Depth: 10 units
    private static final VoxelShape SHAPE = Block.box(
        3,    // minX - 10 units width centered
        0,    // minY - starts at ground
        3,    // minZ - 10 units depth centered
        13,   // maxX
        14,   // maxY - full height of box
        13    // maxZ
    );

    private final String collectionId;
    private final PopBlockColor color; // Optional: only used for default collection

    public BoxBlock(Properties properties, String collectionId) {
        this(properties, collectionId, null);
    }

    public BoxBlock(Properties properties, String collectionId, PopBlockColor color) {
        super(properties);
        this.collectionId = collectionId;
        this.color = color;
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public String getCollectionId() {
        return collectionId;
    }

    @Nullable
    public PopBlockColor getColor() {
        return color;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape baseShape = SHAPE;

        // Apply hitbox offsets and scale from block entity if available
        if (level.getBlockEntity(pos) instanceof BoxBlockEntity boxBlockEntity) {
            double localOffsetX = boxBlockEntity.getHitboxOffsetX(); // Right offset (perpendicular to facing)
            double localOffsetY = boxBlockEntity.getHitboxOffsetY(); // Up offset
            double localOffsetZ = boxBlockEntity.getHitboxOffsetZ(); // Forward offset (along facing)
            double hitboxScaleX = boxBlockEntity.getHitboxScaleX();  // X axis scale (right/left)
            double hitboxScaleY = boxBlockEntity.getHitboxScaleY();  // Y axis scale (up/down)
            double hitboxScaleZ = boxBlockEntity.getHitboxScaleZ();  // Z axis scale (forward/back)

            // Get facing direction for rotation
            Direction facing = state.getValue(FACING);

            // Apply scale if any axis is not 1.0 (default)
            VoxelShape scaledShape = baseShape;
            if (hitboxScaleX != 1.0 || hitboxScaleY != 1.0 || hitboxScaleZ != 1.0) {
                // Calculate the center of the shape (8, 7, 8 for the base shape)
                double centerX = 8.0;
                double centerY = 7.0;
                double centerZ = 8.0;

                // For NORTH facing, use scales directly (this is the "reference" orientation)
                // For other facings, we need to swap X and Z scales appropriately
                double effectiveScaleX = hitboxScaleX;
                double effectiveScaleZ = hitboxScaleZ;

                // Swap X and Z scales for EAST/WEST facings since the block is rotated 90°
                if (facing == Direction.EAST || facing == Direction.WEST) {
                    effectiveScaleX = hitboxScaleZ; // What was forward/back is now left/right
                    effectiveScaleZ = hitboxScaleX; // What was left/right is now forward/back
                }

                // Scale from the center on each axis independently
                double minX = centerX + (3.0 - centerX) * effectiveScaleX;
                double minY = 0.0; // Keep base on the ground
                double minZ = centerZ + (3.0 - centerZ) * effectiveScaleZ;
                double maxX = centerX + (13.0 - centerX) * effectiveScaleX;
                double maxY = 14.0 * hitboxScaleY;
                double maxZ = centerZ + (13.0 - centerZ) * effectiveScaleZ;

                scaledShape = Block.box(minX, minY, minZ, maxX, maxY, maxZ);
            }

            // Only apply offset if at least one is non-zero (performance optimization)
            if (localOffsetX != 0.0 || localOffsetY != 0.0 || localOffsetZ != 0.0) {
                // Transform local offsets to world offsets based on facing direction
                double worldOffsetX = 0;
                double worldOffsetZ = 0;

                switch (facing) {
                    case NORTH: // Facing -Z
                        worldOffsetX = localOffsetX;   // Right is +X
                        worldOffsetZ = -localOffsetZ;  // Forward is -Z
                        break;
                    case SOUTH: // Facing +Z
                        worldOffsetX = -localOffsetX;  // Right is -X
                        worldOffsetZ = localOffsetZ;   // Forward is +Z
                        break;
                    case EAST:  // Facing +X
                        worldOffsetX = localOffsetZ;   // Forward is +X
                        worldOffsetZ = localOffsetX;   // Right is +Z
                        break;
                    case WEST:  // Facing -X
                        worldOffsetX = -localOffsetZ;  // Forward is -X
                        worldOffsetZ = -localOffsetX;  // Right is -Z
                        break;
                }

                return scaledShape.move(worldOffsetX, localOffsetY, worldOffsetZ);
            }

            return scaledShape;
        }

        return baseShape;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BoxBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return level.isClientSide ? createTickerHelper(blockEntityType, ModBlockEntities.BOX_BLOCK.get(), BoxBlockEntity::tick) : null;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack heldItem = player.getItemInHand(hand);

        // Right-click with stick to cycle alternative skins
        if (heldItem.is(net.minecraft.world.item.Items.STICK)) {
            if (!level.isClientSide()) {
                if (level.getBlockEntity(pos) instanceof BoxBlockEntity boxBlockEntity && boxBlockEntity.hasFigure()) {
                    boxBlockEntity.cycleAlternativeSkin();
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        // Shift-right-click to open adjustment screen
        if (level.isClientSide && player.isShiftKeyDown()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof BoxBlockEntity boxBlockEntity) {
                // Open the figure position adjustment screen
                Minecraft.getInstance().setScreen(new FigurePositionScreen(
                    pos,
                    boxBlockEntity.getFigureOffsetX(),
                    boxBlockEntity.getFigureOffsetY(),
                    boxBlockEntity.getFigureOffsetZ(),
                    boxBlockEntity.getFigureScale(),
                    boxBlockEntity.getHitboxOffsetX(),
                    boxBlockEntity.getHitboxOffsetY(),
                    boxBlockEntity.getHitboxOffsetZ(),
                    boxBlockEntity.getHitboxScaleX(),
                    boxBlockEntity.getHitboxScaleY(),
                    boxBlockEntity.getHitboxScaleZ(),
                    boxBlockEntity.getLogoPositionX(),
                    boxBlockEntity.getLogoPositionY(),
                    boxBlockEntity.getLogoPositionZ(),
                    boxBlockEntity.getLogoScaleX(),
                    boxBlockEntity.getLogoScaleY(),
                    boxBlockEntity.getLogoScaleZ()
                ));
                return InteractionResult.SUCCESS;
            }
        }

        // Regular right-click for opening/closing the box
        if (!level.isClientSide) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof BoxBlockEntity boxBlockEntity) {
                // If the box is open
                if (boxBlockEntity.isOpen()) {
                    // Empty hand extracts the figure
                    if (heldItem.isEmpty() && boxBlockEntity.hasFigure() && !boxBlockEntity.isFigureExtracted()) {
                        // Create a figure block item with the figure data
                        ItemStack figureBlockItem = new ItemStack(ModItems.FIGURE_BLOCK_ITEM.get());
                        CompoundTag blockEntityTag = new CompoundTag();
                        blockEntityTag.putString("FigureId", boxBlockEntity.getFigureId());
                        blockEntityTag.putString("CollectionId", boxBlockEntity.getCollectionId());
                        blockEntityTag.putInt("AlternativeSkinIndex", boxBlockEntity.getAlternativeSkinIndex());
                        // Copy figure positioning data
                        blockEntityTag.putDouble("FigureOffsetX", boxBlockEntity.getFigureOffsetX());
                        blockEntityTag.putDouble("FigureOffsetY", boxBlockEntity.getFigureOffsetY());
                        blockEntityTag.putDouble("FigureOffsetZ", boxBlockEntity.getFigureOffsetZ());
                        blockEntityTag.putDouble("FigureScale", boxBlockEntity.getFigureScale());
                        figureBlockItem.addTagElement("BlockEntityTag", blockEntityTag);

                        // Give the player the figure block item
                        if (!player.getInventory().add(figureBlockItem)) {
                            // If inventory is full, drop it
                            player.drop(figureBlockItem, false);
                        }

                        // Mark the figure as extracted
                        boxBlockEntity.setFigureExtracted(true);
                        return InteractionResult.SUCCESS;
                    }
                    // Holding a figure block - try to put it back in the box
                    else if (heldItem.getItem() == ModItems.FIGURE_BLOCK_ITEM.get() && boxBlockEntity.isFigureExtracted()) {
                        // Check if the figure matches this box
                        CompoundTag blockEntityTag = heldItem.getTagElement("BlockEntityTag");
                        if (blockEntityTag != null) {
                            String heldFigureId = blockEntityTag.getString("FigureId");
                            String heldCollectionId = blockEntityTag.getString("CollectionId");

                            // Verify it's the same figure that was in this box
                            if (heldFigureId.equals(boxBlockEntity.getFigureId()) &&
                                heldCollectionId.equals(boxBlockEntity.getCollectionId())) {

                                // Put the figure back in the box
                                boxBlockEntity.setFigureExtracted(false);

                                // Close the box
                                boxBlockEntity.toggleOpen();

                                // Remove one figure item from player's hand
                                heldItem.shrink(1);

                                return InteractionResult.SUCCESS;
                            }
                        }
                    }
                    // Slime ball closes the box
                    else if (heldItem.getItem() == net.minecraft.world.item.Items.SLIME_BALL) {
                        boxBlockEntity.toggleOpen();
                        return InteractionResult.SUCCESS;
                    }
                }
                // If the box is closed, only scissors can open it
                else if (heldItem.getItem() == net.minecraft.world.item.Items.SHEARS) {
                    boxBlockEntity.toggleOpen();
                    return InteractionResult.SUCCESS;
                }
            }
        }

        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Face the player when placed
        Direction playerFacing = context.getHorizontalDirection();
        Direction blockFacing = playerFacing.getOpposite();
        return this.defaultBlockState().setValue(FACING, blockFacing);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // Drop the box block item with NBT data preserved
        if (!level.isClientSide) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof BoxBlockEntity boxBlockEntity) {
                // Determine which item to drop based on color or collectionId
                ItemStack dropStack;
                if (color != null) {
                    dropStack = new ItemStack(ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(color).get());
                } else if (collectionId != null) {
                    dropStack = new ItemStack(ModItems.BOX_BLOCK_ITEMS.get(collectionId).get());
                } else {
                    // Fallback to the block's item (shouldn't happen in normal gameplay)
                    dropStack = new ItemStack(this.asItem());
                }

                // Save the block entity data to the item
                boxBlockEntity.saveToItem(dropStack);

                // Drop the item
                popResource(level, pos, dropStack);
            }
        }

        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level, BlockPos pos, Player player) {
        ItemStack stack = super.getCloneItemStack(state, target, level, pos, player);
        // Save block entity data to the ItemStack so figure data is preserved
        if (level.getBlockEntity(pos) instanceof BoxBlockEntity boxBlockEntity) {
            boxBlockEntity.saveToItem(stack);
        }
        return stack;
    }
}
