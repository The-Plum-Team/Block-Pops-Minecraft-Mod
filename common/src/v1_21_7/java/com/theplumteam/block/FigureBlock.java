package com.theplumteam.block;

import com.mojang.serialization.MapCodec;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.client.particle.BlockParticleHelper;
import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class FigureBlock extends BaseEntityBlock {
    public static final MapCodec<FigureBlock> CODEC = simpleCodec(FigureBlock::new);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    private static final VoxelShape SHAPE = Block.box(5, 0, 5, 11, 12, 11);

    public FigureBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FigureBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return level.isClientSide ? createTickerHelper(blockEntityType, ModBlockEntities.FIGURE_BLOCK.get(), FigureBlockEntity::tick) : null;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // Use INVISIBLE to prevent the block model from rendering
        // Only the GeckoLib block entity renderer should render
        return RenderShape.INVISIBLE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        // Right-click (no shift) to cycle alternative skins
        if (!player.isShiftKeyDown()) {
            if (!level.isClientSide()) {
                if (level.getBlockEntity(pos) instanceof FigureBlockEntity figureBlockEntity && figureBlockEntity.hasFigure()) {
                    figureBlockEntity.cycleAlternativeSkin();
                }
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction playerFacing = context.getHorizontalDirection();
        Direction blockFacing = playerFacing.getOpposite();
        return this.defaultBlockState().setValue(FACING, blockFacing);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        if (!level.isClientSide) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof FigureBlockEntity figureBlockEntity) {
                CustomData customData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
                if (customData != null) {
                    CompoundTag tag = customData.copyTag();
                    if (tag.contains("QuickSkinId")) {
                        figureBlockEntity.setQuickSkinId(tag.getStringOr("QuickSkinId", ""));
                    }
                    if (tag.contains("SkinSnapshot")) {
                        figureBlockEntity.setSkinSnapshot(tag.getStringOr("SkinSnapshot", ""));
                    }
                }
            }
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected void spawnDestroyParticles(Level level, Player player, BlockPos pos, BlockState state) {
        if (level.isClientSide) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof FigureBlockEntity figureBlockEntity) {
                if (BlockParticleHelper.spawnFigureDestroyParticles(level, pos, figureBlockEntity)) {
                    return;
                }
            }
        }
        // Fall back: use closest wool block based on collection color
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof FigureBlockEntity figureBlockEntity) {
            String collectionId = figureBlockEntity.getCollectionId();
            FigureCollection collection = CollectionRegistry.getCollection(collectionId).orElse(null);
            if (collection != null && collection.hasBackgroundColor()) {
                int[] bgColor = collection.getBackgroundColor();
                BlockState woolState = getClosestWoolBlock(bgColor[0], bgColor[1], bgColor[2]);
                level.levelEvent(player, 2001, pos, Block.getId(woolState));
                return;
            }
        }
        super.spawnDestroyParticles(level, player, pos, state);
    }

    private static BlockState getClosestWoolBlock(int r, int g, int b) {
        int[][] woolColors = {
            {233, 236, 236}, // WHITE
            {240, 118, 19},  // ORANGE
            {189, 68, 179},  // MAGENTA
            {58, 175, 217},  // LIGHT_BLUE
            {248, 198, 39},  // YELLOW
            {112, 185, 25},  // LIME
            {237, 141, 172}, // PINK
            {62, 68, 71},    // GRAY
            {142, 142, 134}, // LIGHT_GRAY
            {21, 137, 145},  // CYAN
            {121, 42, 172},  // PURPLE
            {53, 57, 157},   // BLUE
            {114, 71, 40},   // BROWN
            {84, 109, 27},   // GREEN
            {161, 39, 34},   // RED
            {20, 21, 25}     // BLACK
        };
        Block[] woolBlocks = {
            Blocks.WHITE_WOOL, Blocks.ORANGE_WOOL, Blocks.MAGENTA_WOOL, Blocks.LIGHT_BLUE_WOOL,
            Blocks.YELLOW_WOOL, Blocks.LIME_WOOL, Blocks.PINK_WOOL, Blocks.GRAY_WOOL,
            Blocks.LIGHT_GRAY_WOOL, Blocks.CYAN_WOOL, Blocks.PURPLE_WOOL, Blocks.BLUE_WOOL,
            Blocks.BROWN_WOOL, Blocks.GREEN_WOOL, Blocks.RED_WOOL, Blocks.BLACK_WOOL
        };

        double minDist = Double.MAX_VALUE;
        int closestIdx = 0;
        for (int i = 0; i < woolColors.length; i++) {
            double dist = Math.pow(r - woolColors[i][0], 2) + Math.pow(g - woolColors[i][1], 2) + Math.pow(b - woolColors[i][2], 2);
            if (dist < minDist) {
                minDist = dist;
                closestIdx = i;
            }
        }
        return woolBlocks[closestIdx].defaultBlockState();
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof FigureBlockEntity figureBlockEntity) {
                ItemStack dropStack = new ItemStack(ModItems.FIGURE_BLOCK_ITEM.get());
                figureBlockEntity.saveToItem(dropStack);
                popResource(level, pos, dropStack);
            }
        }

        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        ItemStack stack = super.getCloneItemStack(level, pos, state, includeData);
        if (level.getBlockEntity(pos) instanceof FigureBlockEntity figureBlockEntity) {
            figureBlockEntity.saveToItem(stack);
        }
        return stack;
    }
}
