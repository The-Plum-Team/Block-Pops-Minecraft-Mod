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
import org.jetbrains.annotations.Nullable;

public class BoxBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    // Tighter hitbox for the core box (simplified, no directional variation)
    // Width: 8 units, Height: 11 units, Depth: 8 units
    private static final VoxelShape SHAPE = Block.box(
        4,    // minX - 8 units width centered at X=8
        0,    // minY - starts at ground
        4,    // minZ - 8 units depth centered at Z=8
        12,   // maxX
        11,   // maxY - height of core box
        12    // maxZ
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

        // Apply hitbox offsets from block entity if available
        if (level.getBlockEntity(pos) instanceof BoxBlockEntity boxBlockEntity) {
            double localOffsetX = boxBlockEntity.getHitboxOffsetX(); // Right offset (perpendicular to facing)
            double localOffsetY = boxBlockEntity.getHitboxOffsetY(); // Up offset
            double localOffsetZ = boxBlockEntity.getHitboxOffsetZ(); // Forward offset (along facing)

            // Only apply offset if at least one is non-zero (performance optimization)
            if (localOffsetX != 0.0 || localOffsetY != 0.0 || localOffsetZ != 0.0) {
                // Transform local offsets to world offsets based on facing direction
                Direction facing = state.getValue(FACING);
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

                return baseShape.move(worldOffsetX, localOffsetY, worldOffsetZ);
            }
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
                    boxBlockEntity.getLogoPositionX(),
                    boxBlockEntity.getLogoPositionY(),
                    boxBlockEntity.getLogoPositionZ(),
                    boxBlockEntity.getLogoScaleX(),
                    boxBlockEntity.getLogoScaleY()
                ));
                return InteractionResult.SUCCESS;
            }
        }

        // Regular right-click for opening/closing the box
        if (!level.isClientSide) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof BoxBlockEntity boxBlockEntity) {
                ItemStack heldItem = player.getItemInHand(hand);

                // If the box is open
                if (boxBlockEntity.isOpen()) {
                    // Empty hand extracts the figure
                    if (heldItem.isEmpty() && boxBlockEntity.hasFigure() && !boxBlockEntity.isFigureExtracted()) {
                        // Create a figure block item with the figure data
                        ItemStack figureBlockItem = new ItemStack(ModItems.FIGURE_BLOCK_ITEM.get());
                        CompoundTag blockEntityTag = new CompoundTag();
                        blockEntityTag.putString("FigureId", boxBlockEntity.getFigureId());
                        blockEntityTag.putString("CollectionId", boxBlockEntity.getCollectionId());
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
    public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level, BlockPos pos, Player player) {
        ItemStack stack = super.getCloneItemStack(state, target, level, pos, player);
        // Save block entity data to the ItemStack so figure data is preserved
        if (level.getBlockEntity(pos) instanceof BoxBlockEntity boxBlockEntity) {
            boxBlockEntity.saveToItem(stack);
        }
        return stack;
    }
}
