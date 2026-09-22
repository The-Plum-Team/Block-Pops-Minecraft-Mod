package com.theplumteam.platform.neoforge;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.item.BoxBlockItem;
import com.theplumteam.item.GeoBlockItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
//? if >=1.21.2 {
/*import com.theplumteam.platform.PlatformHelper;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
*///? }
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * NeoForge implementation of PlatformHelper.
 * This class provides NeoForge-specific implementations for @ExpectPlatform methods.
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
        // 26.1 moved the production flag and the dist off FMLLoader and the
        // FMLEnvironment field onto accessors of their own.
        //? if >=26 {
        /*return !FMLEnvironment.isProduction();
        *///? } else {
        return !FMLLoader.isProduction();
        //? }
    }

    public static void openBoxFigureScreen(BlockPos pos, BoxBlockEntity boxBlockEntity) {
        //? if >=26 {
        /*if (FMLEnvironment.getDist() == Dist.CLIENT) {
        *///? } else {
        if (FMLEnvironment.dist == Dist.CLIENT) {
        //? }
            com.theplumteam.client.ClientHelpers.openBoxFigureScreen(pos, boxBlockEntity);
        }
    }

    public static void openClawMachineScreen(BlockPos pos, ClawMachineBlockEntity clawMachineBlockEntity) {
        //? if >=26 {
        /*if (FMLEnvironment.getDist() == Dist.CLIENT) {
        *///? } else {
        if (FMLEnvironment.dist == Dist.CLIENT) {
        //? }
            com.theplumteam.client.ClientHelpers.openClawMachineScreen(pos, clawMachineBlockEntity);
        }
    }

    // NeoForge 21.1 removed Item#initializeClient; the custom renderers are bound
    // once through RegisterClientExtensionsEvent, so plain items are returned here.

    public static Item createGeoBlockItem(Block block, Item.Properties properties) {
        return new GeoBlockItem(block, properties);
    }

    public static Item createBoxBlockItem(Block block, Item.Properties properties, String collectionId) {
        return new BoxBlockItem(block, properties, collectionId);
    }

    public static Item createBoxBlockItemForColor(Block block, Item.Properties properties, PopBlockColor color) {
        return new BoxBlockItem(block, properties, color);
    }
    //? if >=1.21.2 {
    /*public static <T extends BlockEntity> BlockEntityType<T> createBlockEntityType(
            PlatformHelper.BlockEntityFactory<T> factory, Block... validBlocks) {
        // NeoForge exposes the BlockEntityType constructor directly from 1.21.4.
        return new BlockEntityType<>(factory::create, false, validBlocks);
    }
    *///? }
}
