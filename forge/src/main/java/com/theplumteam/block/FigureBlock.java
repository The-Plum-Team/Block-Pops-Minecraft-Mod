// ========== C:\Users\nebur\Documents\GitHub\BlockPops\forge\src\main\java\com\theplumteam\block\FigureBlock.java ==========
package com.theplumteam.block;

import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.FigureBlockEntity;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModItems;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.client.extensions.common.IClientBlockExtensions;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public class FigureBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    // Hitbox for a standing figure (collectible-sized, tighter fit)
    // Width: 6 units, Height: 12 units, Depth: 6 units
    private static final VoxelShape SHAPE = Block.box(
            5,   // minX - 6 units width centered at X=8
            0,   // minY - starts at ground
            5,   // minZ - 6 units depth centered at Z=8
            11,  // maxX
            12,  // maxY - height of figure (12 units = 0.75 blocks)
            11   // maxZ
    );

    public FigureBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
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
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        // Right-click (no shift) to cycle alternative skins
        // Shift+click is handled by FigurePoseEventHandler for pose changes
        if (!player.isShiftKeyDown()) {
            if (!level.isClientSide()) {
                if (level.getBlockEntity(pos) instanceof FigureBlockEntity figureBlockEntity && figureBlockEntity.hasFigure()) {
                    figureBlockEntity.cycleAlternativeSkin();
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return InteractionResult.PASS;
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
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        // Ensure critical skin data is synced to client immediately upon placement.
        if (!level.isClientSide) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof FigureBlockEntity figureBlockEntity) {
                CompoundTag tag = stack.getTagElement("BlockEntityTag");
                if (tag != null) {
                    if (tag.contains("QuickSkinId")) {
                        figureBlockEntity.setQuickSkinId(tag.getString("QuickSkinId"));
                    }
                    if (tag.contains("SkinSnapshot")) {
                        figureBlockEntity.setSkinSnapshot(tag.getString("SkinSnapshot"));
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
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // Drop the figure block item with NBT data preserved
        if (!level.isClientSide) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof FigureBlockEntity figureBlockEntity) {
                // Create the figure block item
                ItemStack dropStack = new ItemStack(ModItems.FIGURE_BLOCK_ITEM.get());

                // Save the block entity data to the item
                figureBlockEntity.saveToItem(dropStack);

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
        if (level.getBlockEntity(pos) instanceof FigureBlockEntity figureBlockEntity) {
            figureBlockEntity.saveToItem(stack);
        }
        return stack;
    }

    /**
     * Get the wool block state corresponding to this figure's color.
     * This is determined by favorite color for player figures, or collection theme for others.
     */
    private BlockState getWoolBlockState(BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof FigureBlockEntity figureBlockEntity) {
            FigureDefinition figureDef = figureBlockEntity.getFigureDefinition();
            if (figureDef != null) {
                // For player figures, use their favorite color
                if (figureDef.getType() == FigureType.PLAYER && figureDef.getFavoriteColor() != null) {
                    PopBlockColor color = figureDef.getFavoriteColor();
                    return switch (color) {
                        case ORIGINAL -> net.minecraft.world.level.block.Blocks.WHITE_WOOL.defaultBlockState();
                        case BLACK -> net.minecraft.world.level.block.Blocks.BLACK_WOOL.defaultBlockState();
                        case BLUE -> net.minecraft.world.level.block.Blocks.BLUE_WOOL.defaultBlockState();
                        case BROWN -> net.minecraft.world.level.block.Blocks.BROWN_WOOL.defaultBlockState();
                        case CYAN -> net.minecraft.world.level.block.Blocks.CYAN_WOOL.defaultBlockState();
                        case GRAY -> net.minecraft.world.level.block.Blocks.GRAY_WOOL.defaultBlockState();
                        case GREEN -> net.minecraft.world.level.block.Blocks.GREEN_WOOL.defaultBlockState();
                        case LIGHT_BLUE -> net.minecraft.world.level.block.Blocks.LIGHT_BLUE_WOOL.defaultBlockState();
                        case LIGHT_GRAY -> net.minecraft.world.level.block.Blocks.LIGHT_GRAY_WOOL.defaultBlockState();
                        case LIME -> net.minecraft.world.level.block.Blocks.LIME_WOOL.defaultBlockState();
                        case MAGENTA -> net.minecraft.world.level.block.Blocks.MAGENTA_WOOL.defaultBlockState();
                        case ORANGE -> net.minecraft.world.level.block.Blocks.ORANGE_WOOL.defaultBlockState();
                        case PINK -> net.minecraft.world.level.block.Blocks.PINK_WOOL.defaultBlockState();
                        case PURPLE -> net.minecraft.world.level.block.Blocks.PURPLE_WOOL.defaultBlockState();
                        case RED -> net.minecraft.world.level.block.Blocks.RED_WOOL.defaultBlockState();
                        case YELLOW -> net.minecraft.world.level.block.Blocks.YELLOW_WOOL.defaultBlockState();
                    };
                }

                // For other collections, map collection theme to closest wool color
                String collectionId = figureBlockEntity.getCollectionId();
                if (collectionId != null) {
                    return switch (collectionId) {
                        case "adventuretime" -> net.minecraft.world.level.block.Blocks.LIGHT_BLUE_WOOL.defaultBlockState();
                        case "fnaf" -> net.minecraft.world.level.block.Blocks.BLACK_WOOL.defaultBlockState();
                        case "jojos" -> net.minecraft.world.level.block.Blocks.MAGENTA_WOOL.defaultBlockState();
                        case "jujutsukaisen" -> net.minecraft.world.level.block.Blocks.BLACK_WOOL.defaultBlockState();
                        case "onepiece" -> net.minecraft.world.level.block.Blocks.BLUE_WOOL.defaultBlockState();
                        case "starwars" -> net.minecraft.world.level.block.Blocks.BLACK_WOOL.defaultBlockState();
                        case "supermario" -> net.minecraft.world.level.block.Blocks.BROWN_WOOL.defaultBlockState();
                        case "world_players" -> net.minecraft.world.level.block.Blocks.WHITE_WOOL.defaultBlockState(); // Should be handled by favorite color, but fallback
                        default -> net.minecraft.world.level.block.Blocks.WHITE_WOOL.defaultBlockState();
                    };
                }
            }
        }
        // Fallback to white wool
        return net.minecraft.world.level.block.Blocks.WHITE_WOOL.defaultBlockState();
    }

    /**
     * Spawn colored particles when walking on the figure block (IForgeBlock method)
     */
    @Override
    public boolean addRunningEffects(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level.isClientSide()) {
            BlockState woolState = getWoolBlockState(level, pos);
            level.addParticle(
                    new BlockParticleOption(ParticleTypes.BLOCK, woolState),
                    entity.getX() + ((Math.random() - 0.5) * entity.getBbWidth()),
                    entity.getY() + 0.1,
                    entity.getZ() + ((Math.random() - 0.5) * entity.getBbWidth()),
                    (Math.random() - 0.5) * 0.15,
                    0.05,
                    (Math.random() - 0.5) * 0.15
            );
        }
        return true;
    }

    /**
     * Register client-side block extensions for custom particles
     */
    @Override
    public void initializeClient(Consumer<IClientBlockExtensions> consumer) {
        consumer.accept(new IClientBlockExtensions() {
            @Override
            public boolean addDestroyEffects(BlockState state, Level level, BlockPos pos, ParticleEngine particleEngine) {
                BlockState woolState = getWoolBlockState(level, pos);

                // Spawn multiple particles in a grid pattern similar to default block breaking
                for (int i = 0; i < 4; ++i) {
                    for (int j = 0; j < 4; ++j) {
                        for (int k = 0; k < 4; ++k) {
                            double x = pos.getX() + (i + 0.5) / 4.0;
                            double y = pos.getY() + (j + 0.5) / 4.0;
                            double z = pos.getZ() + (k + 0.5) / 4.0;

                            level.addParticle(
                                    new BlockParticleOption(ParticleTypes.BLOCK, woolState),
                                    x, y, z,
                                    (Math.random() - 0.5) * 0.8,
                                    (Math.random() - 0.5) * 0.8,
                                    (Math.random() - 0.5) * 0.8
                            );
                        }
                    }
                }
                return true;
            }

            @Override
            public boolean addHitEffects(BlockState state, Level level, HitResult target, ParticleEngine particleEngine) {
                if (!(target instanceof BlockHitResult blockHit)) {
                    return false;
                }

                BlockState woolState = getWoolBlockState(level, blockHit.getBlockPos());
                BlockPos pos = blockHit.getBlockPos();
                Direction side = blockHit.getDirection();

                // Spawn a few particles on the side that was hit
                for (int i = 0; i < 4; ++i) {
                    double x = pos.getX() + 0.5 + (side.getStepX() * 0.5) + (Math.random() - 0.5) * 0.4;
                    double y = pos.getY() + 0.5 + (side.getStepY() * 0.5) + (Math.random() - 0.5) * 0.4;
                    double z = pos.getZ() + 0.5 + (side.getStepZ() * 0.5) + (Math.random() - 0.5) * 0.4;

                    level.addParticle(
                            new BlockParticleOption(ParticleTypes.BLOCK, woolState),
                            x, y, z,
                            side.getStepX() * 0.01,
                            side.getStepY() * 0.01,
                            side.getStepZ() * 0.01
                    );
                }
                return true;
            }
        });
    }
}