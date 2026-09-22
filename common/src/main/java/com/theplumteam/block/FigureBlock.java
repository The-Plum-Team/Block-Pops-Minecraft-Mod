package com.theplumteam.block;

//? if >=1.21 {
/*import com.mojang.serialization.MapCodec;
*///? }
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.item.BlockEntityItemData;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModItems;
import com.theplumteam.util.TagReads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
//? if >=1.21.2 {
/*// 1.21.2 restored InteractionResult on useItemOn.
*///? } elif >=1.21 {
/*import net.minecraft.world.ItemInteractionResult;
*///? }
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
//? if >=1.21 {
/*import net.minecraft.world.level.LevelReader;
*///? }
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
//? if <1.21.2 {
import net.minecraft.world.level.block.RenderShape;
//? }
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
//? if >=1.21.2 {
/*import net.minecraft.world.level.block.state.properties.EnumProperty;
*///? } else {
import net.minecraft.world.level.block.state.properties.DirectionProperty;
//? }
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class FigureBlock extends BaseEntityBlock {
    //? if >=26.3 {
    /*// 26.3 dropped the block codec contract, so neither the codec nor the
    // accessor below exists any more.
    *///? } elif >=1.21 {
    /*public static final MapCodec<FigureBlock> CODEC = simpleCodec(FigureBlock::new);
    *///? }
    //? if >=1.21.2 {
    /*public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    *///? } else {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    //? }

    private static final VoxelShape SHAPE = Block.box(5, 0, 5, 11, 12, 11);

    public FigureBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    //? if >=26.3 {
    /*
    *///? } elif >=1.21 {
/*@Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
    *///? }

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
        return level.isClientSide() ? createTickerHelper(blockEntityType, ModBlockEntities.FIGURE_BLOCK.get(), FigureBlockEntity::tick) : null;
    }

    //? if <1.21.2 {
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }
    //? }

    //? if >=1.21.2 {
    /*@Override
    protected InteractionResult useItemOn(ItemStack heldItem, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return BlockInteractionResults.forItem(interact(state, level, pos, player, hand, hit));
    }
    *///? } elif >=1.21 {
    /*@Override
    protected ItemInteractionResult useItemOn(ItemStack heldItem, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return BlockInteractionResults.forItem(interact(state, level, pos, player, hand, hit));
    }
    *///? } else {
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return interact(state, level, pos, player, hand, hit);
    }
    //? }

    private InteractionResult interact(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        // Right-click (no shift) to cycle alternative skins
        if (!player.isShiftKeyDown()) {
            if (!level.isClientSide()) {
                if (level.getBlockEntity(pos) instanceof FigureBlockEntity figureBlockEntity && figureBlockEntity.hasFigure()) {
                    figureBlockEntity.cycleAlternativeSkin();
                }
            }
            //? if >=1.21.2 {
            /*return InteractionResult.SUCCESS;
            *///? } else {
            return InteractionResult.sidedSuccess(level.isClientSide());
            //? }
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

        if (!level.isClientSide()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof FigureBlockEntity figureBlockEntity) {
                CompoundTag tag = BlockEntityItemData.read(stack);
                if (tag != null) {
                    if (tag.contains("QuickSkinId")) {
                        figureBlockEntity.setQuickSkinId(TagReads.string(tag, "QuickSkinId", ""));
                    }
                    if (tag.contains("SkinSnapshot")) {
                        figureBlockEntity.setSkinSnapshot(TagReads.string(tag, "SkinSnapshot", ""));
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
    //? if >=1.21 {
    /*public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
    *///? } else {
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
    //? }
        if (!level.isClientSide()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof FigureBlockEntity figureBlockEntity) {
                ItemStack dropStack = new ItemStack(ModItems.FIGURE_BLOCK_ITEM.get());
                //? if >=1.21 {
                /*figureBlockEntity.saveToItem(dropStack, level.registryAccess());
                *///? } else {
                figureBlockEntity.saveToItem(dropStack);
                //? }
                popResource(level, pos, dropStack);
            }
        }

        //? if >=1.21 {
        /*return super.playerWillDestroy(level, pos, state, player);
        *///? } else {
        super.playerWillDestroy(level, pos, state, player);
        //? }
    }

    @Override
    //? if >=1.21.2 {
    /*public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
    *///? } elif >=1.21 {
    /*public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
    *///? } else {
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
    //? }
        //? if >=1.21.2 {
        /*ItemStack stack = super.getCloneItemStack(level, pos, state, includeData);
        *///? } else {
        ItemStack stack = super.getCloneItemStack(level, pos, state);
        //? }
        if (level.getBlockEntity(pos) instanceof FigureBlockEntity figureBlockEntity) {
            //? if >=1.21 {
            /*figureBlockEntity.saveToItem(stack, level.registryAccess());
            *///? } else {
            figureBlockEntity.saveToItem(stack);
            //? }
        }
        return stack;
    }
}
