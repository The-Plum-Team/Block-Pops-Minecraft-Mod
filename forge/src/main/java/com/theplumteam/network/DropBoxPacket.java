package com.theplumteam.network;

import com.theplumteam.figure.CollectionRegistry;
import com.theplumteam.figure.FigureCollection;
import com.theplumteam.figure.FigureDefinition;
import com.theplumteam.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

public class DropBoxPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger(DropBoxPacket.class);

    private final BlockPos pos;
    private final String collectionId;

    public DropBoxPacket(BlockPos pos, String collectionId) {
        this.pos = pos;
        this.collectionId = collectionId;
    }

    public static void encode(DropBoxPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos);
        buffer.writeUtf(packet.collectionId);
    }

    public static DropBoxPacket decode(FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        String collectionId = buffer.readUtf();
        return new DropBoxPacket(pos, collectionId);
    }

    public static void handle(DropBoxPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        LOGGER.info("Received drop box packet on server - Position: {}, Collection ID: {}",
                    packet.pos, packet.collectionId);
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                LOGGER.info("Player is not null: {}", player.getName().getString());

                // Get the box block for this collection
                Block boxBlock = null;
                if (packet.collectionId.equals("default")) {
                    // For default collection, use the first color variant (white)
                    boxBlock = ModBlocks.DEFAULT_BOX_BLOCKS.values().stream()
                        .findFirst()
                        .map(supplier -> supplier.get())
                        .orElse(null);
                } else if (ModBlocks.BOX_BLOCKS.containsKey(packet.collectionId)) {
                    // For static collections, get the specific box block
                    boxBlock = ModBlocks.BOX_BLOCKS.get(packet.collectionId).get();
                } else {
                    // For dynamic collections (like world_players), use the default box block as fallback
                    LOGGER.info("Using default box block for dynamic collection: {}", packet.collectionId);
                    boxBlock = ModBlocks.DEFAULT_BOX_BLOCKS.values().stream()
                        .findFirst()
                        .map(supplier -> supplier.get())
                        .orElse(null);
                }

                if (boxBlock != null) {
                    // Create an ItemStack from the box block
                    ItemStack boxItem = new ItemStack(boxBlock);

                    // Get the collection and select a random figure
                    CollectionRegistry.getCollection(packet.collectionId).ifPresent(collection -> {
                        List<FigureDefinition> figures = collection.getFigures();
                        if (!figures.isEmpty()) {
                            // Select a random figure from the collection
                            Random random = new Random();
                            FigureDefinition randomFigure = figures.get(random.nextInt(figures.size()));

                            // Create NBT data for the box with the random figure
                            CompoundTag blockEntityTag = new CompoundTag();
                            blockEntityTag.putString("FigureId", randomFigure.getId());
                            // Store the collection ID so dynamic collections work correctly
                            blockEntityTag.putString("CollectionId", packet.collectionId);

                            // Set the BlockEntityTag on the item
                            boxItem.getOrCreateTag().put("BlockEntityTag", blockEntityTag);

                            LOGGER.info("Selected random figure '{}' ({}) from collection '{}' for box",
                                       randomFigure.getId(), randomFigure.getName(), packet.collectionId);
                        } else {
                            LOGGER.warn("Collection '{}' has no figures", packet.collectionId);
                        }
                    });

                    // Spawn the item entity at the claw machine position (slightly above)
                    double x = packet.pos.getX() + 0.5;
                    double y = packet.pos.getY() + 1.0; // Spawn above the lower block
                    double z = packet.pos.getZ() + 0.5;

                    ItemEntity itemEntity = new ItemEntity(player.level(), x, y, z, boxItem);
                    // Add a slight upward velocity for a nice drop effect
                    itemEntity.setDeltaMovement(0, 0.2, 0);
                    player.level().addFreshEntity(itemEntity);

                    LOGGER.info("Dropped box item for collection '{}' at position {}", packet.collectionId, packet.pos);
                } else {
                    LOGGER.warn("Could not find box block for collection: {}", packet.collectionId);
                }
            } else {
                LOGGER.warn("Player is null in packet handler!");
            }
        });
        context.setPacketHandled(true);
    }
}
