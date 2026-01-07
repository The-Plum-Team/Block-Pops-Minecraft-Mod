package com.theplumteam.platform.forge;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.item.ForgeBoxBlockItem;
import com.theplumteam.item.ForgeGeoBlockItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * Forge implementation of PlatformHelper
 * This class provides Forge-specific implementations for @ExpectPlatform methods
 */
@SuppressWarnings("unused")
public class PlatformHelperImpl {

    public static String getPlatformName() {
        return "Forge";
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
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
            com.theplumteam.client.ClientHelpers.openBoxFigureScreen(pos, boxBlockEntity)
        );
    }

    public static void openClawMachineScreen(BlockPos pos, ClawMachineBlockEntity clawMachineBlockEntity) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
            com.theplumteam.client.ClientHelpers.openClawMachineScreen(pos, clawMachineBlockEntity)
        );
    }

    // Item factory methods - return Forge-specific items with IClientItemExtensions

    public static Item createGeoBlockItem(Block block, Item.Properties properties) {
        return new ForgeGeoBlockItem(block, properties);
    }

    public static Item createBoxBlockItem(Block block, Item.Properties properties, String collectionId) {
        return new ForgeBoxBlockItem(block, properties, collectionId);
    }

    public static Item createBoxBlockItemForColor(Block block, Item.Properties properties, PopBlockColor color) {
        return new ForgeBoxBlockItem(block, properties, color);
    }
}
