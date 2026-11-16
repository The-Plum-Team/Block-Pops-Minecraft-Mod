package com.theplumteam.client.upload;

import com.theplumteam.BlockPopsMod;
import dev.architectury.platform.Platform;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How this build names itself to the upload service: {@code <mod version>+<Minecraft>-<loader>},
 * such as {@code 2.2.0+1.21.8-fabric}. The service refuses a game older than it supports, and
 * every package says which build made it.
 */
public final class UploadClientId {
    private static final Pattern NUMBERS = Pattern.compile("^([0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3})");
    private static String current;

    private UploadClientId() {
    }

    /** This build's name. */
    public static synchronized String current() {
        if (current == null) {
            // The other loader of a version is Forge until 1.21 and NeoForge from then on
            //? if >=1.21 {
            /*String loader = Platform.isFabric() ? "fabric" : "neoforge";
            *///? } else {
            String loader = Platform.isFabric() ? "fabric" : "forge";
            //? }
            current = of(Platform.getMod(BlockPopsMod.MOD_ID).getVersion(), Platform.getMinecraftVersion(), loader);
        }
        return current;
    }

    /** The name of a build, from its parts as the loader gives them. */
    static String of(String modVersion, String minecraft, String loader) {
        Matcher numbers = NUMBERS.matcher(modVersion == null ? "" : modVersion);
        String version = numbers.find() ? numbers.group(1) : "0.0.0";
        String game = minecraft == null ? "" : minecraft.replaceAll("[^A-Za-z0-9.]", "");
        return version + "+" + (game.isEmpty() ? "unknown" : game.substring(0, Math.min(game.length(), 16))) + "-"
                + loader;
    }

    static String userAgent() {
        return "BlockPops/" + current();
    }
}
