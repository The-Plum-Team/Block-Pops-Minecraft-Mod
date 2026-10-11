package com.theplumteam.block;

//? if >=1.21 {
/*import com.mojang.serialization.MapCodec;
*///? }
import com.theplumteam.blockentity.PlushBlockEntity;
import com.theplumteam.platform.PlatformHelper;
import com.theplumteam.plush.BuiltInPlushes;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
//? if >=1.21.2 {
/*// 1.21.2 restored InteractionResult on useItemOn.
*///? } elif >=1.21 {
/*import net.minecraft.world.ItemInteractionResult;
*///? }
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
//? if <1.21.2 {
import net.minecraft.world.level.block.RenderShape;
//? }
import net.minecraft.world.level.block.entity.BlockEntity;
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

/**
 * A plush toy placed as a block of its own. It faces whoever places it. Right-click turns a
 * plush into its next variant, as it cycles a figure's alternative skins. In a development
 * environment, shift-right-click with an empty hand opens the placement adjustment screen.
 */
public class PlushBlock extends BaseEntityBlock {
    //? if >=1.21.2 {
    /*public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    *///? } else {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    //? }

    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 14, 14);

    private final String plushId;

    public PlushBlock(String plushId, Properties properties) {
        super(properties);
        this.plushId = plushId;
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public String getPlushId() {
        return plushId;
    }

    //? if >=26.3 {
    /*// 26.3 dropped the block codec contract, so the accessor below no longer exists.
    *///? } elif >=1.21 {
/*@Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(properties -> new PlushBlock(this.plushId, properties));
    }
    *///? }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (level.getBlockEntity(pos) instanceof PlushBlockEntity plush && plush.getAdjustedShape() != null) {
            return plush.getAdjustedShape();
        }
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PlushBlockEntity(ModBlockEntities.PLUSH_BLOCKS.get(plushId).get(), pos, state);
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
        // Shift-right-click opens the adjustment screen (client side, dev mode only)
        if (player.isShiftKeyDown() && PlatformHelper.isDevelopmentEnvironment()) {
            if (level.isClientSide() && level.getBlockEntity(pos) instanceof PlushBlockEntity plushBlockEntity) {
                PlatformHelper.openPlushScreen(pos, plushBlockEntity);
            }
            //? if >=1.21.2 {
            /*return InteractionResult.SUCCESS;
            *///? } else {
            return InteractionResult.sidedSuccess(level.isClientSide());
            //? }
        }

        // Right-click (no shift) to cycle the plush's variants
        String nextVariant = player.isShiftKeyDown() ? null : BuiltInPlushes.nextVariant(plushId);
        if (nextVariant != null) {
            if (!level.isClientSide()) {
                becomeVariant(state, level, pos, nextVariant);
            }
            //? if >=1.21.2 {
            /*return InteractionResult.SUCCESS;
            *///? } else {
            return InteractionResult.sidedSuccess(level.isClientSide());
            //? }
        }
        return InteractionResult.PASS;
    }

    // A variant is a block of its own, so the plush is replaced where it stands, keeping its
    // facing and whatever placement the adjustment screen gave it.
    private static void becomeVariant(BlockState state, Level level, BlockPos pos, String variantId) {
        PlushBlockEntity previous = level.getBlockEntity(pos) instanceof PlushBlockEntity plush ? plush : null;
        BlockState variant = ModBlocks.PLUSH_BLOCKS.get(variantId).get().defaultBlockState()
                .setValue(FACING, state.getValue(FACING));
        level.setBlock(pos, variant, 3);
        if (previous != null && level.getBlockEntity(pos) instanceof PlushBlockEntity current) {
            current.setPlacement(previous.getOffsetX(), previous.getOffsetY(), previous.getOffsetZ(), previous.getScale(),
                    previous.getHitboxOffsetX(), previous.getHitboxOffsetY(), previous.getHitboxOffsetZ(),
                    previous.getHitboxScaleX(), previous.getHitboxScaleY(), previous.getHitboxScaleZ());
        }
        level.playSound(null, pos, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    // No loot table: a plush broken outside creative drops itself.
    @Override
    //? if >=1.21 {
    /*public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
    *///? } else {
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
    //? }
        if (!level.isClientSide() && !player.isCreative()) {
            popResource(level, pos, new ItemStack(this));
        }
        //? if >=1.21 {
        /*return super.playerWillDestroy(level, pos, state, player);
        *///? } else {
        super.playerWillDestroy(level, pos, state, player);
        //? }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}
