package com.theplumteam.block;

import com.mojang.serialization.MapCodec;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.figure.FigureType;
import com.theplumteam.platform.PlatformHelper;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
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
    private static final TagKey<Item> SHEARS_TAG = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("blockpops", "shears"));
    public static final MapCodec<BoxBlock> CODEC = simpleCodec(BoxBlock::new);
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
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape baseShape = SHAPE;

        // Apply hitbox offsets and scale from block entity if available
        if (level.getBlockEntity(pos) instanceof BoxBlockEntity boxBlockEntity) {
            double localOffsetX = boxBlockEntity.getHitboxOffsetX();
            double localOffsetY = boxBlockEntity.getHitboxOffsetY();
            double localOffsetZ = boxBlockEntity.getHitboxOffsetZ();
            double hitboxScaleX = boxBlockEntity.getHitboxScaleX();
            double hitboxScaleY = boxBlockEntity.getHitboxScaleY();
            double hitboxScaleZ = boxBlockEntity.getHitboxScaleZ();

            Direction facing = state.getValue(FACING);

            VoxelShape scaledShape = baseShape;
            if (hitboxScaleX != 1.0 || hitboxScaleY != 1.0 || hitboxScaleZ != 1.0) {
                double centerX = 8.0;
                double centerY = 7.0;
                double centerZ = 8.0;

                double effectiveScaleX = hitboxScaleX;
                double effectiveScaleZ = hitboxScaleZ;

                if (facing == Direction.EAST || facing == Direction.WEST) {
                    effectiveScaleX = hitboxScaleZ;
                    effectiveScaleZ = hitboxScaleX;
                }

                double minX = centerX + (3.0 - centerX) * effectiveScaleX;
                double minY = 0.0;
                double minZ = centerZ + (3.0 - centerZ) * effectiveScaleZ;
                double maxX = centerX + (13.0 - centerX) * effectiveScaleX;
                double maxY = 14.0 * hitboxScaleY;
                double maxZ = centerZ + (13.0 - centerZ) * effectiveScaleZ;

                scaledShape = Block.box(minX, minY, minZ, maxX, maxY, maxZ);
            }

            if (localOffsetX != 0.0 || localOffsetY != 0.0 || localOffsetZ != 0.0) {
                double worldOffsetX = 0;
                double worldOffsetZ = 0;

                switch (facing) {
                    case NORTH:
                        worldOffsetX = localOffsetX;
                        worldOffsetZ = -localOffsetZ;
                        break;
                    case SOUTH:
                        worldOffsetX = -localOffsetX;
                        worldOffsetZ = localOffsetZ;
                        break;
                    case EAST:
                        worldOffsetX = localOffsetZ;
                        worldOffsetZ = localOffsetX;
                        break;
                    case WEST:
                        worldOffsetX = -localOffsetZ;
                        worldOffsetZ = -localOffsetX;
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
    protected ItemInteractionResult useItemOn(ItemStack heldItem, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof BoxBlockEntity boxBlockEntity)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // Shift-right-click behavior
        if (player.isShiftKeyDown()) {
            // If box is open, close it (server side)
            if (boxBlockEntity.isOpen() && !level.isClientSide) {
                boxBlockEntity.toggleOpen();
                return ItemInteractionResult.SUCCESS;
            }
            // If box is closed
            else if (!boxBlockEntity.isOpen()) {
                if (level.isClientSide) {
                    if (PlatformHelper.isDevelopmentEnvironment()) {
                        PlatformHelper.openBoxFigureScreen(pos, boxBlockEntity);
                        return ItemInteractionResult.SUCCESS;
                    }
                } else {
                    boxBlockEntity.cycleAlternativeSkin();
                    return ItemInteractionResult.SUCCESS;
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // Regular right-click (no shift) - server side only
        if (!level.isClientSide) {
            if (boxBlockEntity.isOpen()) {
                // Holding a figure block - try to put it back in the box
                if (heldItem.getItem() == ModItems.FIGURE_BLOCK_ITEM.get() && boxBlockEntity.isFigureExtracted()) {
                    CustomData customData = heldItem.get(DataComponents.BLOCK_ENTITY_DATA);
                    if (customData != null) {
                        CompoundTag blockEntityTag = customData.copyTag();
                        String heldFigureId = blockEntityTag.getString("FigureId");
                        String heldCollectionId = blockEntityTag.getString("CollectionId");

                        if (heldFigureId.equals(boxBlockEntity.getFigureId()) &&
                                heldCollectionId.equals(boxBlockEntity.getCollectionId())) {

                            if (blockEntityTag.contains("QuickSkinId")) {
                                boxBlockEntity.setQuickSkinId(blockEntityTag.getString("QuickSkinId"));
                            }
                            if (blockEntityTag.contains("SkinSnapshot")) {
                                boxBlockEntity.setSkinSnapshot(blockEntityTag.getString("SkinSnapshot"));
                            }

                            boxBlockEntity.setFigureExtracted(false);
                            boxBlockEntity.toggleOpen();
                            heldItem.shrink(1);

                            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 1.0F, 1.0F);
                            return ItemInteractionResult.SUCCESS;
                        }
                    }
                }
                // Extract the figure
                else if (boxBlockEntity.hasFigure() && !boxBlockEntity.isFigureExtracted()) {
                    ItemStack figureBlockItem = new ItemStack(ModItems.FIGURE_BLOCK_ITEM.get());
                    CompoundTag blockEntityTag = new CompoundTag();
                    blockEntityTag.putString("FigureId", boxBlockEntity.getFigureId());
                    blockEntityTag.putString("CollectionId", boxBlockEntity.getCollectionId());
                    blockEntityTag.putInt("AlternativeSkinIndex", boxBlockEntity.getAlternativeSkinIndex());
                    blockEntityTag.putInt("PoseIndex", boxBlockEntity.getPoseIndex());
                    blockEntityTag.putDouble("FigureOffsetX", boxBlockEntity.getFigureOffsetX());
                    blockEntityTag.putDouble("FigureOffsetY", boxBlockEntity.getFigureOffsetY());
                    blockEntityTag.putDouble("FigureOffsetZ", boxBlockEntity.getFigureOffsetZ());
                    blockEntityTag.putDouble("FigureScale", boxBlockEntity.getFigureScale());

                    FigureDefinition figureDef = boxBlockEntity.getFigureDefinition();
                    if (figureDef != null && figureDef.getType() == FigureType.PLAYER) {
                        String snapshot = boxBlockEntity.getSkinSnapshot();
                        if (snapshot != null && !snapshot.isEmpty()) {
                            blockEntityTag.putString("SkinSnapshot", snapshot);
                        }
                        String quickSkinId = boxBlockEntity.getQuickSkinId();
                        if (quickSkinId != null && !quickSkinId.isEmpty()) {
                            blockEntityTag.putString("QuickSkinId", quickSkinId);
                        }
                    }

                    // Required in 1.21+ - block entity type ID must be present for serialization
                    blockEntityTag.putString("id", "blockpops:figure_block");
                    figureBlockItem.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(blockEntityTag));

                    if (!player.getInventory().add(figureBlockItem)) {
                        player.drop(figureBlockItem, false);
                    }

                    boxBlockEntity.setFigureExtracted(true);
                    level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 1.0F, 1.0F);
                    return ItemInteractionResult.SUCCESS;
                }
            }
            else {
                if (heldItem.is(SHEARS_TAG) || heldItem.getItem() instanceof ShearsItem) {
                    boxBlockEntity.toggleOpen();
                    level.playSound(null, pos, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 1.0F, 1.0F);
                    return ItemInteractionResult.SUCCESS;
                } else {
                    FigureDefinition figureDef = boxBlockEntity.getFigureDefinition();
                    if (figureDef != null && figureDef.hasAlternatives()) {
                        player.displayClientMessage(Component.literal("Use Shears to open | Shift+Right-click to change skin").withStyle(ChatFormatting.GRAY), true);
                    } else {
                        player.displayClientMessage(Component.literal("Use Shears to open").withStyle(ChatFormatting.GRAY), true);
                    }
                    return ItemInteractionResult.SUCCESS;
                }
            }
        }

        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof BoxBlockEntity boxBlockEntity)) {
            return InteractionResult.PASS;
        }

        // Shift-right-click behavior with empty hand
        if (player.isShiftKeyDown()) {
            if (boxBlockEntity.isOpen() && !level.isClientSide) {
                boxBlockEntity.toggleOpen();
                return InteractionResult.SUCCESS;
            }
            else if (!boxBlockEntity.isOpen() && level.isClientSide) {
                if (PlatformHelper.isDevelopmentEnvironment()) {
                    PlatformHelper.openBoxFigureScreen(pos, boxBlockEntity);
                    return InteractionResult.SUCCESS;
                }
            }
        }

        return InteractionResult.sidedSuccess(level.isClientSide);
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
            if (blockEntity instanceof BoxBlockEntity boxBlockEntity) {
                CustomData customData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
                if (customData != null) {
                    CompoundTag tag = customData.copyTag();
                    if (tag.contains("QuickSkinId")) {
                        boxBlockEntity.setQuickSkinId(tag.getString("QuickSkinId"));
                    }
                    if (tag.contains("SkinSnapshot")) {
                        boxBlockEntity.setSkinSnapshot(tag.getString("SkinSnapshot"));
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
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof BoxBlockEntity boxBlockEntity) {
                String collectionId = boxBlockEntity.getCollectionId();
                PopBlockColor color = boxBlockEntity.getColor();

                ItemStack dropStack;
                if (color != null) {
                    dropStack = new ItemStack(ModItems.DEFAULT_BOX_BLOCK_ITEMS.get(color).get());
                } else if (collectionId != null && !collectionId.isEmpty()) {
                    dropStack = new ItemStack(ModItems.BOX_BLOCK_ITEMS.get(collectionId).get());
                } else {
                    dropStack = new ItemStack(this.asItem());
                }

                boxBlockEntity.saveToItem(dropStack);
                popResource(level, pos, dropStack);
            }
        }

        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        ItemStack stack = super.getCloneItemStack(level, pos, state);
        if (level.getBlockEntity(pos) instanceof BoxBlockEntity boxBlockEntity) {
            boxBlockEntity.saveToItem(stack);
        }
        return stack;
    }
}
