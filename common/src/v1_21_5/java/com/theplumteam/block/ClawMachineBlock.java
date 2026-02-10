package com.theplumteam.block;

import com.mojang.serialization.MapCodec;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.data.IPlayerDiscovery;
import com.theplumteam.data.PlayerDataManager;
import com.theplumteam.network.OpenFavoriteColorScreenPacket;
import com.theplumteam.network.SyncTokenDataPacket;
import com.theplumteam.platform.PlatformHelper;
import com.theplumteam.server.config.ServerConfig;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.server.ServerTickHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

public class ClawMachineBlock extends BaseEntityBlock {
    public static final MapCodec<ClawMachineBlock> CODEC = simpleCodec(ClawMachineBlock::new);
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    public ClawMachineBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER
                ? new ClawMachineBlockEntity(pos, state)
                : null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> blockEntityType) {
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) {
            return level.isClientSide
                    ? createTickerHelper(blockEntityType, ModBlockEntities.CLAW_MACHINE_BLOCK.get(),
                    ClawMachineBlockEntity::tick)
                    : null;
        }
        return null;
    }

    // In 1.21.4, ENTITYBLOCK_ANIMATED was removed. Block entities now rely on their block models.
    // The getRenderShape() override is no longer needed for GeckoLib blocks.

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockPos lowerPos = state.getValue(HALF) == DoubleBlockHalf.LOWER
                ? pos
                : pos.below();

        BlockEntity blockEntity = level.getBlockEntity(lowerPos);
        if (blockEntity instanceof ClawMachineBlockEntity clawMachineBlockEntity) {
            if (level.isClientSide) {
                PlatformHelper.openClawMachineScreen(lowerPos, clawMachineBlockEntity);
            } else {
                // Server side: sync token data to ensure client UI shows correct state
                if (player instanceof ServerPlayer serverPlayer) {
                    if (!ServerConfig.getInstance().isShowColorSelectionOnJoin()) {
                        IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(serverPlayer);
                        if (!discovery.hasChosenFavoriteColor()) {
                            OpenFavoriteColorScreenPacket.sendToPlayer(serverPlayer);
                            return InteractionResult.SUCCESS;
                        }
                    }
                    syncTokenDataToClient(serverPlayer);
                }
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private static void syncTokenDataToClient(ServerPlayer player) {
        IPlayerDiscovery discovery = PlayerDataManager.getDiscovery(player);
        long gameTime = player.serverLevel().getGameTime();
        long nextRegularTime = discovery.getNextRegularTokenTime();
        long ticksUntilNext = Math.max(0, nextRegularTime - gameTime);
        long millisUntilReset = ServerTickHandler.calculateMillisUntilNextReset();

        SyncTokenDataPacket.sendToPlayer(
                player,
                discovery.getRegularTokens(),
                ticksUntilNext,
                !discovery.hasUsedTodaySpecialToken(),
                millisUntilReset
        );
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();

        if (pos.getY() < level.getMaxY() - 1
                && level.getBlockState(pos.above()).canBeReplaced(context)) {

            Direction playerFacing = context.getHorizontalDirection();
            Direction blockFacing = playerFacing.getOpposite();

            return this.defaultBlockState()
                    .setValue(FACING, blockFacing)
                    .setValue(HALF, DoubleBlockHalf.LOWER);
        }
        return null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), 3);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            if (player.isCreative()) {
                preventCreativeDropFromBottomPart(level, pos, state, player);
            } else {
                dropResources(state, level, pos, null, player, player.getMainHandItem());
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    // In 1.21.5+, handle double-block removal logic elsewhere
    // affectNeighborsAfterRemoval is only for neighbor updates, not block removal logic
    // The other half cleanup is now handled automatically by Minecraft

    protected static void preventCreativeDropFromBottomPart(Level level, BlockPos pos,
                                                            BlockState state, Player player) {
        DoubleBlockHalf half = state.getValue(HALF);
        if (half == DoubleBlockHalf.UPPER) {
            BlockPos lowerPos = pos.below();
            BlockState lowerState = level.getBlockState(lowerPos);
            if (lowerState.is(state.getBlock())
                    && lowerState.getValue(HALF) == DoubleBlockHalf.LOWER) {
                BlockState airState = lowerState.hasProperty(BlockStateProperties.WATERLOGGED)
                        && lowerState.getValue(BlockStateProperties.WATERLOGGED)
                        ? Blocks.WATER.defaultBlockState()
                        : Blocks.AIR.defaultBlockState();
                level.setBlock(lowerPos, airState, 35);
                level.levelEvent(player, 2001, lowerPos, Block.getId(lowerState));
            }
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF);
    }
}
