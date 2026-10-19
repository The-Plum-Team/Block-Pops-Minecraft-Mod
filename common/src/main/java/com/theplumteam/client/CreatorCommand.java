package com.theplumteam.client;

import com.theplumteam.client.gui.SettingsScreen;
import dev.architectury.event.events.client.ClientCommandRegistrationEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.platform.Platform;
import net.minecraft.network.chat.Component;

/**
 * The client command {@code /blockpopscreator}, which opens the World collections creator
 * without a machine, so its "Put in this machine" is not offered. Like every other way into
 * the creator it asks {@link SettingsScreen#canOpenCreator()}, and answers a player who may
 * not open it with a message instead.
 *
 * <p>The command exists only in a development environment, while the creator is still being
 * worked on. It has a root of its own rather than a branch of the server's {@code /blockpops}
 * command, because a client command sharing a server command's root depends on how each loader
 * passes on the arguments it does not know.
 */
public final class CreatorCommand {
    private static final String NAME = "blockpopscreator";

    // Set by the command and taken on the next client tick: the chat screen closes only after
    // the command returns, and would close a creator opened from inside it
    private static boolean openRequested;

    private CreatorCommand() {
    }

    /** Registers the command and its tick, in a development environment only. Client side. */
    public static void register() {
        if (!Platform.isDevelopmentEnvironment()) {
            return;
        }
        ClientCommandRegistrationEvent.EVENT.register((dispatcher, context) -> dispatcher.register(
                ClientCommandRegistrationEvent.literal(NAME).executes(command -> {
                    if (!SettingsScreen.canOpenCreator()) {
                        command.getSource().arch$sendFailure(
                                Component.translatable("blockpops.world_creator.command.refused"));
                        return 0;
                    }
                    openRequested = true;
                    return 1;
                })));
        ClientTickEvent.CLIENT_POST.register(minecraft -> {
            if (openRequested) {
                openRequested = false;
                SettingsScreen.openCreator(null, null);
            }
        });
    }
}
