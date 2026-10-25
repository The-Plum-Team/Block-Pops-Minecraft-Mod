package com.theplumteam.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.network.OpenSupportCheckoutPacket;
import com.theplumteam.server.config.ServerConfig;
import com.theplumteam.util.E2EDeterminism;
import com.theplumteam.util.ServerLevels;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gets a player the checkout of the supporter subscription: the server asks the supporter service
 * (the one {@link SpecialAccess} asks) for the player's checkout, and the player opens it. The
 * service picks the shop. A PayNow checkout is made for the player: an online-mode server sends the
 * player's UUID, so the purchase follows the Mojang account, and an offline-mode one only the
 * name, which is all a non-premium player has. A Ko-fi page comes with a link to the service's
 * page where the player ties the Ko-fi email they paid with to their name. Once the checkout is
 * out, {@link SpecialAccess} asks about the player often until the payment shows.
 */
public final class SupportCheckout {
    private static final Gson GSON = new Gson();
    private static final int MAX_ANSWER_BYTES = 8192;
    // A player waits this long before asking for another checkout
    private static final long REQUEST_INTERVAL_MILLIS = 10_000;
    private static final int PREFIX_COLOR = 0xBB8FCE;
    private static final int TEXT_COLOR = 0xE8D5F5;

    private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BlockPops-SupportCheckout-IO");
        thread.setDaemon(true);
        return thread;
    });

    private SupportCheckout() {}

    /**
     * Asks for the player's checkout, off the server thread. The menu's button gets it opened at
     * once; the chat's link ({@code /blockpops supporter}) gets it back as a link to click.
     */
    public static void request(ServerPlayer player, boolean fromMenu) {
        ServerConfig config = ServerConfig.getInstance();
        String apiUrl = config.getSpecialAccessApiUrl();
        String apiKey = config.getSpecialAccessApiKey();
        // The packaged E2E stays inside its sandbox
        if (apiUrl.isEmpty() || apiKey.isEmpty() || E2EDeterminism.ENABLED) {
            reply(player, fromMenu, "blockpops.support.unavailable");
            return;
        }
        MinecraftServer server = ServerLevels.serverOf(player);
        // A second subscription would only charge twice
        if (SpecialAccess.isSupporter(player)) {
            reply(player, fromMenu, "blockpops.support.already");
            return;
        }

        UUID playerId = player.getUUID();
        long now = System.currentTimeMillis();
        Long last = LAST_REQUEST.get(playerId);
        if (last != null && now - last < REQUEST_INTERVAL_MILLIS) {
            reply(player, fromMenu, "blockpops.support.on_its_way");
            return;
        }
        LAST_REQUEST.put(playerId, now);

        String playerName = player.getScoreboardName();
        // Only an online-mode server knows the player's real UUID
        UUID premiumId = server.usesAuthentication() ? playerId : null;
        String url = (apiUrl.endsWith("/") ? apiUrl : apiUrl + "/") + "api/paynow/minecraft/checkout";
        IO.execute(() -> {
            Checkout checkout;
            try {
                checkout = ask(url, apiKey, premiumId, playerName);
            } catch (IOException | RuntimeException e) {
                BlockPopsMod.LOGGER.warn("Supporter checkout for {} failed: {}", playerName, e.toString());
                checkout = null;
            }
            Checkout answer = checkout;
            server.execute(() -> {
                ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                if (online == null) {
                    return;
                }
                if (answer == null) {
                    reply(online, fromMenu, "blockpops.support.failed");
                    return;
                }
                SpecialAccess.watchPurchase(online);
                if (fromMenu) {
                    OpenSupportCheckoutPacket.sendToPlayer(online, answer.url());
                } else {
                    sendLink(online, answer.url());
                }
                if (answer.link() != null) {
                    sendLinkPage(online, answer.link());
                }
            });
        });
    }

    // Where the player pays, and the page that ties a Ko-fi payment to them, if the shop needs it
    private record Checkout(String url, @Nullable String link) {}

    // The checkout, or null when the service gave none
    @Nullable
    private static Checkout ask(String url, String apiKey, @Nullable UUID premiumId, String playerName) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(15_000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            JsonObject body = new JsonObject();
            if (premiumId != null) {
                body.addProperty("uuid", premiumId.toString());
            }
            body.addProperty("name", playerName);
            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                BlockPopsMod.LOGGER.warn("Supporter checkout for {}: the service answered {}", playerName,
                        connection.getResponseCode());
                return null;
            }
            String answer;
            try (InputStream stream = connection.getInputStream()) {
                answer = new String(stream.readNBytes(MAX_ANSWER_BYTES), StandardCharsets.UTF_8);
            }
            JsonObject json = GSON.fromJson(answer, JsonObject.class);
            String checkoutUrl = text(json.get("url"));
            if (!OpenSupportCheckoutPacket.isCheckoutUrl(checkoutUrl)) {
                return null;
            }
            // The link page is the service's own: the same host the server asked
            String link = text(json.get("link"));
            URI service = URI.create(url);
            boolean linkOnService = link != null && link.startsWith("https://" + service.getHost() + "/");
            return new Checkout(checkoutUrl, linkOnService ? link : null);
        } finally {
            connection.disconnect();
        }
    }

    @Nullable
    private static String text(@Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static MutableComponent prefix() {
        return Component.literal("[BlockPops] ").withStyle(style -> style.withColor(PREFIX_COLOR));
    }

    // The menu shows its own answers; the chat's link gets them in the chat. Both get the
    // language key, so the player reads the answer in their own language.
    private static void reply(ServerPlayer player, boolean fromMenu, String key) {
        if (fromMenu) {
            OpenSupportCheckoutPacket.sendMessage(player, key);
        } else {
            tell(player, key);
        }
    }

    private static void tell(ServerPlayer player, String key) {
        player.sendSystemMessage(prefix().append(Component.translatable(key)
                .withStyle(style -> style.withColor(TEXT_COLOR))));
    }

    private static void sendLink(ServerPlayer player, String url) {
        MutableComponent link = Component.translatable("blockpops.support.open_checkout");
        //? if >=1.21.5 {
        /*link.withStyle(style -> style.withColor(PREFIX_COLOR).withBold(true).withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url))));
        *///? } else {
        link.withStyle(style -> style.withColor(PREFIX_COLOR).withBold(true).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
        //? }
        player.sendSystemMessage(prefix().append(Component.translatable("blockpops.support.ready", link)
                .withStyle(style -> style.withColor(TEXT_COLOR))));
    }

    // After paying on Ko-fi, the player ties the payment to their name on the service's page
    private static void sendLinkPage(ServerPlayer player, String url) {
        MutableComponent link = Component.translatable("blockpops.support.link_account");
        //? if >=1.21.5 {
        /*link.withStyle(style -> style.withColor(PREFIX_COLOR).withBold(true).withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url))));
        *///? } else {
        link.withStyle(style -> style.withColor(PREFIX_COLOR).withBold(true).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
        //? }
        player.sendSystemMessage(prefix().append(Component.translatable("blockpops.support.kofi_link", link)
                .withStyle(style -> style.withColor(TEXT_COLOR))));
    }
}
