package com.theplumteam.block;

import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.gui.FigurePositionScreen;
import com.theplumteam.registry.ModBlockEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import org.jetbrains.annotations.Nullable;

public class BoxBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    // Custom hitbox matching the main square of the box model
    // Box dimensions based on geo.json: approximately 9x14x11 units, centered
    // Each facing direction needs its own hitbox, moved 1 pixel forward in that direction

    // NORTH (facing -Z): Width (X) = 9 units, Depth (Z) = 11 units, shifted -1 in Z
    private static final VoxelShape SHAPE_NORTH = Block.box(
        3.5,  // minX - 9 units width centered at X=8
        0,    // minY - starts at ground
        1.5,  // minZ - moved forward (north = -Z)
        12.5, // maxX
        14,   // maxY - height of box
        12.5  // maxZ
    );

    // SOUTH (facing +Z): Width (X) = 9 units, Depth (Z) = 11 units, shifted +1 in Z
    private static final VoxelShape SHAPE_SOUTH = Block.box(
        3.5,  // minX - 9 units width centered at X=8
        0,    // minY - starts at ground
        3.5,  // minZ - moved forward (south = +Z)
        12.5, // maxX
        14,   // maxY - height of box
        14.5  // maxZ
    );

    // EAST (facing +X): Width (X) = 11 units, Depth (Z) = 9 units, shifted +1 in X
    private static final VoxelShape SHAPE_EAST = Block.box(
        3.5,  // minX - moved forward (east = +X)
        0,    // minY - starts at ground
        3.5,  // minZ - 9 units depth centered at Z=8
        14.5, // maxX
        14,   // maxY - height of box
        12.5  // maxZ
    );

    // WEST (facing -X): Width (X) = 11 units, Depth (Z) = 9 units, shifted -1 in X
    private static final VoxelShape SHAPE_WEST = Block.box(
        1.5,  // minX - moved forward (west = -X)
        0,    // minY - starts at ground
        3.5,  // minZ - 9 units depth centered at Z=8
        12.5, // maxX
        14,   // maxY - height of box
        12.5  // maxZ
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
        Direction facing = state.getValue(FACING);
        VoxelShape baseShape = switch (facing) {
            case NORTH -> SHAPE_NORTH;
            case SOUTH -> SHAPE_SOUTH;
            case EAST -> SHAPE_EAST;
            case WEST -> SHAPE_WEST;
            default -> SHAPE_NORTH; // Fallback, should never happen
        };

        // Apply hitbox offsets from block entity if available
        if (level.getBlockEntity(pos) instanceof BoxBlockEntity boxBlockEntity) {
            double localOffsetX = boxBlockEntity.getHitboxOffsetX(); // Right offset (perpendicular to facing)
            double localOffsetY = boxBlockEntity.getHitboxOffsetY(); // Up offset
            double localOffsetZ = boxBlockEntity.getHitboxOffsetZ(); // Forward offset (along facing)

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
                    boxBlockEntity.getHitboxOffsetZ()
                ));
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Rotate 90 degrees counter-clockwise from player's facing direction to face the player
        Direction playerFacing = context.getHorizontalDirection();
        Direction blockFacing = playerFacing.getCounterClockWise();
        return this.defaultBlockState().setValue(FACING, blockFacing);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}
