package com.theplumteam.client.worldcontent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.util.AuthlibProfiles;
import com.theplumteam.worldcontent.WorldContentLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.Resource;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * "Use my skin" in the figure inspector: the player's own skin as a figure's skin, read from
 * this computer only. Nothing is fetched from the network.
 *
 * <p>The game keeps every skin it downloads in its skin cache, under its assets folder, at
 * {@code skins/<first two>/<name>}, where the name is the SHA-1 of the skin's texture hash, the
 * last part of its texture link. The hash comes from the textures property of the player's own
 * profile as the server sent it in the player list, and the folder from the game's skin manager,
 * which knows where it keeps its cache on every version. The cached file is read only when it is
 * a regular file of at most {@link WorldContentLimits#SKIN_BYTES}, never through a link, and then
 * gets the same checks as a picked skin, so it must be a 64×64 PNG.
 *
 * <p>When there is no such file (offline or in a development game there is no textures property,
 * a skin may not be cached yet, and an old 64×32 skin fails the checks), the game's default skin
 * is used instead, from the game's own resources, and the answer says why. The result is a
 * {@link FilePicker.LocalFile} like any other: it is previewed at once and only uploaded on Save,
 * and its shown name never leaves this computer.
 *
 * Start on the render thread; the answer comes back on it.
 */
public final class OwnSkin {
    /** What came of it: a checked skin or a refusal, and whether it is the player's own or the default. */
    public record Result(FilePicker.PickResult pick, boolean own, @Nullable Component note) {
    }

    // A textures property is a few hundred bytes of base64; a larger one is not one to decode
    private static final int MAX_PROPERTY_LENGTH = 32 * 1024;
    // The last part of a texture link: Mojang's hashes are hexadecimal
    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{1,128}");

    private static final ExecutorService READER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BlockPops Own Skin Reader");
        thread.setDaemon(true);
        return thread;
    });

    private OwnSkin() {
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("blockpops.world_creator.own_skin." + key, args);
    }

    /**
     * Reads the player's own skin from the skin cache, or the default skin, and hands the
     * result to the receiver on the render thread.
     */
    public static void read(Consumer<Result> receiver) {
        Minecraft minecraft = Minecraft.getInstance();
        String player = minecraft.player != null ? AuthlibProfiles.name(minecraft.player.getGameProfile()) : "";
        String hash = textureHash(minecraft);
        if (hash == null) {
            receiver.accept(defaultSkin(tr("no_textures")));
            return;
        }
        Path cached = cachedSkin(minecraft, hash);
        if (cached == null) {
            receiver.accept(defaultSkin(tr("no_cache")));
            return;
        }
        try {
            READER.execute(() -> {
                FilePicker.PickResult pick = readCached(cached, player);
                minecraft.execute(() -> receiver.accept(pick.kind() == FilePicker.PickResult.Kind.ACCEPTED
                        ? new Result(pick, true, null)
                        : defaultSkin(pick.message().isEmpty() ? tr("not_cached")
                                : tr("refused", pick.message()))));
            });
        } catch (RejectedExecutionException e) {
            receiver.accept(defaultSkin(tr("not_cached")));
        }
    }

    /** Where the skin cache keeps a skin by its texture hash, or null when the cache cannot be found. */
    @Nullable
    private static Path cachedSkin(Minecraft minecraft, String hash) {
        Path root = skinsRoot(minecraft.getSkinManager());
        if (root == null) {
            return null;
        }
        String name = sha1(hash);
        return name == null ? null : root.resolve(name.substring(0, 2)).resolve(name);
    }

    // The hash of the player's skin, from the textures property the server listed it with
    @Nullable
    private static String textureHash(Minecraft minecraft) {
        if (minecraft.player == null) {
            return null;
        }
        GameProfile profile = minecraft.player.getGameProfile();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection != null) {
            PlayerInfo info = connection.getPlayerInfo(minecraft.player.getUUID());
            if (info != null) {
                profile = info.getProfile();
            }
        }
        Collection<Property> textures = AuthlibProfiles.properties(profile).get("textures");
        if (textures == null || textures.isEmpty()) {
            return null;
        }
        String value = AuthlibProfiles.value(textures.iterator().next());
        if (value == null || value.isEmpty() || value.length() > MAX_PROPERTY_LENGTH) {
            return null;
        }
        try {
            String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            JsonObject skin = root.isJsonObject() && root.getAsJsonObject().get("textures") instanceof JsonObject all
                    && all.get("SKIN") instanceof JsonObject found ? found : null;
            if (skin == null || !skin.has("url") || !skin.get("url").isJsonPrimitive()) {
                return null;
            }
            String path = URI.create(skin.get("url").getAsString()).getPath();
            if (path == null) {
                return null;
            }
            // The hash is the base name of the link's path, as authlib gives it to the skin manager
            String hash = path.substring(path.lastIndexOf('/') + 1);
            int dot = hash.lastIndexOf('.');
            if (dot >= 0) {
                hash = hash.substring(0, dot);
            }
            return HASH.matcher(hash).matches() ? hash : null;
        } catch (RuntimeException e) {
            BlockPopsMod.logDebug("Could not read the player's textures property: {}", e.toString());
            return null;
        }
    }

    /**
     * The skin manager's cache folder. Its field is private and its owner changed over the
     * versions (the manager itself on 1.20.1, then each of its texture caches, which share one
     * folder), so it is found by type, which no remapping changes.
     */
    @Nullable
    private static Path skinsRoot(SkinManager manager) {
        try {
            for (Field field : SkinManager.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> type = field.getType();
                if (type == File.class) {
                    field.setAccessible(true);
                    File folder = (File) field.get(manager);
                    return folder != null ? folder.toPath() : null;
                }
                if (type.getEnclosingClass() == SkinManager.class) {
                    field.setAccessible(true);
                    Object cache = field.get(manager);
                    for (Field inner : type.getDeclaredFields()) {
                        if (!Modifier.isStatic(inner.getModifiers()) && inner.getType() == Path.class && cache != null) {
                            inner.setAccessible(true);
                            return (Path) inner.get(cache);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Such as a module that does not open the field; the default skin is used instead
            BlockPopsMod.logDebug("Could not find the skin cache: {}", e.toString());
        }
        return null;
    }

    // The name the skin cache gives a texture hash: the SHA-1 of its characters, as Guava's
    // hashUnencodedChars takes them, two bytes each, low byte first
    @Nullable
    private static String sha1(String hash) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(hash.getBytes(StandardCharsets.UTF_16LE));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    /**
     * Reads the cached skin and checks it as a picked skin. A file that is not there gives a
     * refusal with no message. Off the render thread.
     */
    private static FilePicker.PickResult readCached(Path file, String player) {
        String name = player.isEmpty() ? "skin.png" : player + ".png";
        try {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                return new FilePicker.PickResult(FilePicker.PickResult.Kind.REFUSED, null, name, "");
            }
            if (!attributes.isRegularFile() || attributes.size() > WorldContentLimits.SKIN_BYTES) {
                return new FilePicker.PickResult(FilePicker.PickResult.Kind.REFUSED, null, name,
                        tr("not_a_skin").getString());
            }
            byte[] bytes;
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readNBytes(WorldContentLimits.SKIN_BYTES + 1);
            }
            return FilePicker.check(name, bytes, FilePicker.Slot.SKIN);
        } catch (IOException | SecurityException e) {
            BlockPopsMod.logDebug("Could not read the cached skin: {}", e.toString());
            return new FilePicker.PickResult(FilePicker.PickResult.Kind.REFUSED, null, name, "");
        }
    }

    // The game's default skin from its own resources, saying why it is used. Render thread.
    private static Result defaultSkin(Component why) {
        Minecraft minecraft = Minecraft.getInstance();
        String name = "steve.png";
        Optional<Resource> resource = minecraft.getResourceManager().getResource(CreatorPreview.DEFAULT_SKIN);
        if (resource.isEmpty()) {
            return new Result(new FilePicker.PickResult(FilePicker.PickResult.Kind.REFUSED, null, name,
                    tr("no_default").getString()), false, why);
        }
        try (InputStream in = resource.get().open()) {
            byte[] bytes = in.readNBytes(WorldContentLimits.SKIN_BYTES + 1);
            return new Result(FilePicker.check(name, bytes, FilePicker.Slot.SKIN), false, why);
        } catch (IOException e) {
            return new Result(new FilePicker.PickResult(FilePicker.PickResult.Kind.REFUSED, null, name,
                    tr("no_default").getString()), false, why);
        }
    }
}
