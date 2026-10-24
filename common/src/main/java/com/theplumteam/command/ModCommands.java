package com.theplumteam.command;

import com.mojang.brigadier.CommandDispatcher;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.platform.PlatformHelper;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Central command registration for BlockPops.
 * All cross-platform commands are registered here.
 */
public class ModCommands {

    /**
     * Register all BlockPops commands.
     * This is called from platform-specific command registration events.
     *
     * @param dispatcher The command dispatcher
     * @param registryAccess The registry access context
     * @param environment The command selection context
     */
    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext registryAccess,
            Commands.CommandSelection environment
    ) {
        BlockPopsMod.logDebug("Registering BlockPops commands...");

        // Register all commands
        GetFavoriteColorCommand.register(dispatcher);
        ReloadRegularTokensCommand.register(dispatcher);
        ReloadGuaranteedTokenCommand.register(dispatcher);
        ChangeFavoriteColorCommand.register(dispatcher);
        GetBoxCommand.register(dispatcher);
        SetDefaultColorCommand.register(dispatcher);
        CustomCollectionsCommand.register(dispatcher);
        // World collections are not finished: like their creator, they are made only in a
        // development environment for now
        if (PlatformHelper.isDevelopmentEnvironment()) {
            WorldCollectionsCommand.register(dispatcher);
        }
        SupporterCommand.register(dispatcher);

        BlockPopsMod.logDebug("BlockPops commands registered");
    }
}
