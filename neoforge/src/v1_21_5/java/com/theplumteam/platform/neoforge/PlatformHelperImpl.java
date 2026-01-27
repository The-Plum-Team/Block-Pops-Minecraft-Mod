package com.theplumteam.platform.neoforge;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.platform.PlatformHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * NeoForge implementation of PlatformHelper for 1.21.4+
 * This class provides NeoForge-specific implementations for @ExpectPlatform methods
 */
@SuppressWarnings("unused")
public class PlatformHelperImpl {

    public static String getPlatformName() {
        return "NeoForge";
    }

    public static Path getGameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    public static Path getConfigDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    public static String getModVersion() {
        return ModList.get()
            .getModContainerById(BlockPopsMod.MOD_ID)
            .map(container -> container.getModInfo().getVersion().toString())
            .orElse("UNKNOWN");
    }

    public static boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    public static void openBoxFigureScreen(BlockPos pos, BoxBlockEntity boxBlockEntity) {
        // Use FML's dist checking for client-side execution
        if (FMLLoader.getDist().isClient()) {
            com.theplumteam.client.ClientHelpers.openBoxFigureScreen(pos, boxBlockEntity);
        }
    }

    public static void openClawMachineScreen(BlockPos pos, ClawMachineBlockEntity clawMachineBlockEntity) {
        // Use FML's dist checking for client-side execution
        if (FMLLoader.getDist().isClient()) {
            com.theplumteam.client.ClientHelpers.openClawMachineScreen(pos, clawMachineBlockEntity);
        }
    }

    /**
     * Creates a BlockEntityType using NeoForge's approach for 1.21.4+.
     * In 1.21.4, BlockEntityType.Builder is private, but the constructor is accessible.
     * The constructor takes: factory, boolean (op-only NBT loading), and vararg of valid blocks.
     */
    public static <T extends BlockEntity> BlockEntityType<T> createBlockEntityType(
            PlatformHelper.BlockEntityFactory<T> factory, Block... validBlocks) {
        // In NeoForge 1.21.4+, we can use the BlockEntityType constructor directly
        // Parameters: factory, boolean for op-only NBT access (false = all players), valid blocks
        return new BlockEntityType<>(factory::create, false, validBlocks);
    }
}
