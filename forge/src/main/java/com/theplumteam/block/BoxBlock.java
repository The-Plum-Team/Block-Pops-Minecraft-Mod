package com.theplumteam.block;

import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.client.gui.FigurePositionScreen;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraftforge.client.extensions.common.IClientBlockExtensions;
import net.minecraftforge.fml.loading.FMLLoader;
import org.jetbrains.annotations.Nullable;
import java.util.function.Consumer;

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

    public BoxBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
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

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof BoxBlockEntity boxBlockEntity)) {
            return InteractionResult.PASS;
        }

        // Shift-right-click behavior
        if (player.isShiftKeyDown()) {
            // If box is open, close it (server side)
            if (boxBlockEntity.isOpen() && !level.isClientSide) {
                boxBlockEntity.toggleOpen();
                return InteractionResult.SUCCESS;
            }
            // If box is closed, open adjustment screen (client side)
            else if (!boxBlockEntity.isOpen() && level.isClientSide) {
                // Only allow access in dev mode or creative mode
                if (!FMLLoader.isProduction()) {
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
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // Regular right-click (no shift) - server side only
        if (!level.isClientSide) {
            // If the box is open
            if (boxBlockEntity.isOpen()) {
                // Holding a figure block - try to put it back in the box
                if (heldItem.getItem() == ModItems.FIGURE_BLOCK_ITEM.get() && boxBlockEntity.isFigureExtracted()) {
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

                            // Play putting figure back sound
                            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1.0F, 1.0F);

                            return InteractionResult.SUCCESS;
                        }
                    }
                }
                // Any other item or empty hand - extract the figure
                else if (boxBlockEntity.hasFigure() && !boxBlockEntity.isFigureExtracted()) {
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

                    // If it's a player figure, attach the skin snapshot stored on this box entity
                    com.theplumteam.figure.FigureDefinition figureDef = boxBlockEntity.getFigureDefinition();
                    if (figureDef != null && figureDef.getType() == com.theplumteam.figure.FigureType.PLAYER) {
                        // Get the skin snapshot stored on this specific box entity to ensure it's preserved
                        String snapshot = boxBlockEntity.getSkinSnapshot();
                        if (snapshot != null && !snapshot.isEmpty()) {
                            blockEntityTag.putString("SkinSnapshot", snapshot);
                        }
                    }

                    figureBlockItem.addTagElement("BlockEntityTag", blockEntityTag);

                    // Give the player the figure block item
                    if (!player.getInventory().add(figureBlockItem)) {
                        // If inventory is full, drop it
                        player.drop(figureBlockItem, false);
                    }

                    // Mark the figure as extracted
                    boxBlockEntity.setFigureExtracted(true);

                    // Play taking figure out sound
                    level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 1.0F, 1.0F);

                    return InteractionResult.SUCCESS;
                }
            }
            // If the box is closed, only shears can open it
            else if (heldItem.getItem() == net.minecraft.world.item.Items.SHEARS) {
                boxBlockEntity.toggleOpen();

                // Play shears opening sound
                level.playSound(null, pos, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 1.0F, 1.0F);

                return InteractionResult.SUCCESS;
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
                // Get the collection/color from NBT to determine which item to drop
                String collectionId = boxBlockEntity.getCollectionId();
                PopBlockColor color = boxBlockEntity.getColor();

                ItemStack dropStack;
                if (color != null) {
                    dropStack = new ItemStack(ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(color).get());
                } else if (collectionId != null && !collectionId.isEmpty()) {
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

    /**
     * Get the wool block state corresponding to this box's color or collection background color
     */
    private BlockState getWoolBlockState(Level level, BlockPos pos) {
        // Get color/collection from the block entity
        if (level.getBlockEntity(pos) instanceof BoxBlockEntity boxBlockEntity) {
            PopBlockColor color = boxBlockEntity.getColor();
            String collectionId = boxBlockEntity.getCollectionId();

            // For default collection boxes (with colors), use the color to determine wool
            if (color != null) {
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

            // For other collections, map collection background color to closest wool color
            if (collectionId != null && !collectionId.isEmpty()) {
                return switch (collectionId) {
                    case "adventuretime" -> net.minecraft.world.level.block.Blocks.LIGHT_BLUE_WOOL.defaultBlockState();
                    case "fnaf" -> net.minecraft.world.level.block.Blocks.BLACK_WOOL.defaultBlockState();
                    case "jojos" -> net.minecraft.world.level.block.Blocks.MAGENTA_WOOL.defaultBlockState();
                    case "jujutsukaisen" -> net.minecraft.world.level.block.Blocks.BLACK_WOOL.defaultBlockState();
                    case "onepiece" -> net.minecraft.world.level.block.Blocks.BLUE_WOOL.defaultBlockState();
                    case "starwars" -> net.minecraft.world.level.block.Blocks.BLACK_WOOL.defaultBlockState();
                    case "supermario" -> net.minecraft.world.level.block.Blocks.BROWN_WOOL.defaultBlockState();
                    case "deltarune" -> net.minecraft.world.level.block.Blocks.PURPLE_WOOL.defaultBlockState();
                    case "world_players" -> net.minecraft.world.level.block.Blocks.WHITE_WOOL.defaultBlockState(); // Default for player collection
                    default -> net.minecraft.world.level.block.Blocks.WHITE_WOOL.defaultBlockState();
                };
            }
        }

        // Fallback to white wool
        return net.minecraft.world.level.block.Blocks.WHITE_WOOL.defaultBlockState();
    }

    /**
     * Spawn colored particles when walking on the box (IForgeBlock method)
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

                BlockPos pos = blockHit.getBlockPos();
                BlockState woolState = getWoolBlockState(level, pos);
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