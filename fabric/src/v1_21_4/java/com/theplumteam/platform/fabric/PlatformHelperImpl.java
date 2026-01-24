package com.theplumteam.platform.fabric;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.platform.PlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.nio.file.Path;

/**
 * Fabric implementation of PlatformHelper
 * This class provides Fabric-specific implementations for @ExpectPlatform methods
 */
@SuppressWarnings("unused")
public class PlatformHelperImpl {

    public static String getPlatformName() {
        return "Fabric";
    }

    public static Path getGameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }

    public static Path getConfigDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    public static boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static String getModVersion() {
        return FabricLoader.getInstance()
            .getModContainer(BlockPopsMod.MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("UNKNOWN");
    }

    public static boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    public static void openBoxFigureScreen(BlockPos pos, BoxBlockEntity boxBlockEntity) {
        // Only call client code on the client side to avoid ClassNotFoundException on server
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            ClientPlatformHelperImpl.openBoxFigureScreen(pos, boxBlockEntity);
        }
    }

    public static void openClawMachineScreen(BlockPos pos, ClawMachineBlockEntity clawMachineBlockEntity) {
        // Only call client code on the client side to avoid ClassNotFoundException on server
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            ClientPlatformHelperImpl.openClawMachineScreen(pos, clawMachineBlockEntity);
        }
    }

    /**
     * Creates a BlockEntityType using Fabric's FabricBlockEntityTypeBuilder.
     * In 1.21.4+, BlockEntityType constructor is private.
     */
    public static <T extends BlockEntity> BlockEntityType<T> createBlockEntityType(
            PlatformHelper.BlockEntityFactory<T> factory, Block... validBlocks) {
        return FabricBlockEntityTypeBuilder.create(factory::create, validBlocks).build();
    }
}
