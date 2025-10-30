package com.theplumteam.forge;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.network.ClawMachineCollectionPacket;
import com.theplumteam.network.DropBoxPacket;
import com.theplumteam.network.FigurePositionPacket;
import com.theplumteam.registry.ModBlockEntities;
import com.theplumteam.registry.ModBlocks;
import com.theplumteam.registry.ModCreativeTabs;
import com.theplumteam.registry.ModItems;
import dev.architectury.platform.forge.EventBuses;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

@Mod(BlockPopsMod.MOD_ID)
public final class BlockPopsModForge {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel NETWORK_CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BlockPopsMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public BlockPopsModForge() {
        // Submit our event bus to let Architectury API register our content on the right time.
        EventBuses.registerModEventBus(BlockPopsMod.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());

        // Register Forge-specific content
        ModBlocks.register();
        ModItems.register();
        ModBlockEntities.register();
        ModCreativeTabs.register();

        // Register network packets
        registerNetworkPackets();

        // Run our common setup.
        BlockPopsMod.init();
    }

    private void registerNetworkPackets() {
        int packetId = 0;
        NETWORK_CHANNEL.registerMessage(packetId++,
                FigurePositionPacket.class,
                FigurePositionPacket::encode,
                FigurePositionPacket::decode,
                FigurePositionPacket::handle
        );
        NETWORK_CHANNEL.registerMessage(packetId++,
                ClawMachineCollectionPacket.class,
                ClawMachineCollectionPacket::encode,
                ClawMachineCollectionPacket::decode,
                ClawMachineCollectionPacket::handle
        );
        NETWORK_CHANNEL.registerMessage(packetId++,
                DropBoxPacket.class,
                DropBoxPacket::encode,
                DropBoxPacket::decode,
                DropBoxPacket::handle
        );
    }
}
