package com.theplumteam.platform.fabric;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.block.PopBlockColor;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import com.theplumteam.item.BoxBlockItem;
import com.theplumteam.item.GeoBlockItem;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

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
        // Open the figure position screen (dev mode only)
        net.minecraft.client.Minecraft.getInstance().setScreen(
            new com.theplumteam.client.gui.FigurePositionScreen(
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
            )
        );
    }

    public static void openClawMachineScreen(BlockPos pos, ClawMachineBlockEntity clawMachineBlockEntity) {
        net.minecraft.client.Minecraft.getInstance().setScreen(
            new com.theplumteam.client.gui.CollectionSelectionScreen(
                pos,
                clawMachineBlockEntity.getCollectionId()
            )
        );
    }

    // Item factory methods - return common items (Fabric uses BuiltinItemRendererRegistry for custom rendering)

    public static Item createGeoBlockItem(Block block, Item.Properties properties) {
        return new GeoBlockItem(block, properties);
    }

    public static Item createBoxBlockItem(Block block, Item.Properties properties, String collectionId) {
        return new BoxBlockItem(block, properties, collectionId);
    }

    public static Item createBoxBlockItemForColor(Block block, Item.Properties properties, PopBlockColor color) {
        return new BoxBlockItem(block, properties, color);
    }
}
