package com.theplumteam.client;

import com.theplumteam.client.gui.widget.LinkButton;
import com.theplumteam.network.OpenSupportCheckoutPacket;
import com.theplumteam.network.RequestSupportCheckoutPacket;
import org.jetbrains.annotations.Nullable;

/**
 * The supporter button's side of the checkout: it asks the server for a checkout made for this
 * player, and opens the one the server sends back. A checkout opens only within a short while of
 * the button being pressed and only on PayNow or Ko-fi, so a server cannot open pages on its own. What
 * happened is kept for a few seconds, for the machine menu to show beside the button.
 */
public final class ClientSupportCheckout {
    // How long after the press the server's answer is still taken
    private static final long ANSWER_WINDOW_MILLIS = 30_000;
    // How long the menu shows what happened
    private static final long STATUS_MILLIS = 8_000;

    private static volatile long requestedAt;
    @Nullable
    private static volatile String status;
    private static volatile long statusAt;

    private ClientSupportCheckout() {}

    public static void request() {
        requestedAt = System.currentTimeMillis();
        setStatus("Getting your supporter checkout...");
        new RequestSupportCheckoutPacket().sendToServer();
    }

    /** Opens the checkout in the browser, if the player has just asked for one. */
    public static void open(String url) {
        if (!answered() || !OpenSupportCheckoutPacket.isCheckoutUrl(url)) {
            return;
        }
        LinkButton.openLink(url);
        setStatus(OpenSupportCheckoutPacket.isKofi(url)
                ? "Ko-fi is open in your browser. After paying, use the link in the chat."
                : "The checkout is open in your browser.");
    }

    /** Shows why no checkout opens, if the player has just asked for one. */
    public static void show(String message) {
        if (answered()) {
            setStatus(message);
        }
    }

    /** What the menu says beside the button now, or null. */
    @Nullable
    public static String status() {
        String shown = status;
        long since = System.currentTimeMillis() - statusAt;
        // A request still waiting for its answer keeps its line up until the answer is overdue
        boolean waiting = requestedAt != 0 && System.currentTimeMillis() - requestedAt <= ANSWER_WINDOW_MILLIS;
        return shown != null && (since <= STATUS_MILLIS || waiting) ? shown : null;
    }

    // Whether an answer is due now, which it no longer is once taken
    private static boolean answered() {
        long asked = requestedAt;
        requestedAt = 0;
        return asked != 0 && System.currentTimeMillis() - asked <= ANSWER_WINDOW_MILLIS;
    }

    private static void setStatus(String text) {
        status = text;
        statusAt = System.currentTimeMillis();
    }
}
