package com.theplumteam.platform;

import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.ClawMachineBlockEntity;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;

/**
 * Platform abstraction layer for BlockPops
 * Methods here are implemented in platform-specific modules (forge/fabric)
 * using Architectury's @ExpectPlatform annotation.
 */
public class PlatformHelper {

    /**
     * Gets the platform name (e.g., "Forge", "Fabric")
     */
    @ExpectPlatform
    public static String getPlatformName() {
        throw new AssertionError();
    }

    /**
     * Gets the game directory (where Minecraft is installed)
     */
    @ExpectPlatform
    public static Path getGameDirectory() {
        throw new AssertionError();
    }

    /**
     * Gets the config directory
     * Forge: <game_dir>/config
     * Fabric: <game_dir>/config
     */
    @ExpectPlatform
    public static Path getConfigDirectory() {
        throw new AssertionError();
    }

    /**
     * Checks if a mod is loaded
     * @param modId The mod ID to check
     * @return true if the mod is loaded
     */
    @ExpectPlatform
    public static boolean isModLoaded(String modId) {
        throw new AssertionError();
    }

    /**
     * Gets the mod version
     */
    @ExpectPlatform
    public static String getModVersion() {
        throw new AssertionError();
    }

    /**
     * Checks if running in a development environment
     */
    @ExpectPlatform
    public static boolean isDevelopmentEnvironment() {
        throw new AssertionError();
    }

    /**
     * Opens the box figure adjustment screen (client-side, dev mode only)
     */
    @ExpectPlatform
    public static void openBoxFigureScreen(BlockPos pos, BoxBlockEntity boxBlockEntity) {
        throw new AssertionError();
    }

    /**
     * Opens the claw machine collection selection screen (client-side)
     */
    @ExpectPlatform
    public static void openClawMachineScreen(BlockPos pos, ClawMachineBlockEntity clawMachineBlockEntity) {
        throw new AssertionError();
    }

    /**
     * Creates a BlockEntityType using platform-specific APIs.
     * In 1.21.4+, BlockEntityType constructor is private.
     * Fabric uses FabricBlockEntityTypeBuilder, NeoForge uses their own utilities.
     *
     * @param factory The factory function to create the block entity
     * @param validBlocks The blocks this block entity can be attached to
     * @param <T> The block entity type
     * @return The created BlockEntityType
     */
    @ExpectPlatform
    public static <T extends BlockEntity> BlockEntityType<T> createBlockEntityType(
            BlockEntityFactory<T> factory, Block... validBlocks) {
        throw new AssertionError();
    }

    /**
     * Functional interface for creating block entities.
     * Takes a BlockPos and BlockState, returns a new block entity instance.
     */
    @FunctionalInterface
    public interface BlockEntityFactory<T extends BlockEntity> {
        T create(BlockPos pos, BlockState state);
    }
}
