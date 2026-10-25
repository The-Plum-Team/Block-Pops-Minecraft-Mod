package com.theplumteam.client.gui.style;

import com.mojang.blaze3d.platform.NativeImage;
import com.theplumteam.BlockPopsMod;
import com.theplumteam.client.gui.util.GuiPose;
import com.theplumteam.util.ResourceLocations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
//? if <1.21.2 {
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
//? } elif <1.21.6 {
/*import net.minecraft.client.renderer.RenderType;
*///? }
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;

/**
 * The cinema ticket a token line's "i" opens, in the token's colour: ADMIT ONE up its stub, the
 * wait for the next one or when the guaranteed one renews, beside the clock it runs on, what runs
 * and what stops that clock, and the tickets that add more.
 *
 * <p>The ticket's art (its shape, colours, edges and marks) is drawn pixel by pixel, one pixel per
 * ticket unit, into a texture made once for each token; its words are drawn over it.
 * Its small words take half a unit per font pixel, so the whole ticket is drawn at the scale that
 * makes that a whole number of screen pixels: a GUI unit per ticket unit at even GUI scales,
 * larger at odd ones, so it keeps its layout and stays crisp at every scale.
 */
public final class TokenTicket {
    /**
     * What a token line says, for its ticket: the token, how many the player holds, the time
     * left as the line writes it ("1h 12m", or null for a full regular stock), and when the
     * guaranteed one renews in the player's own time, once the server has said.
     */
    public record View(boolean regular, int count, @Nullable String next, @Nullable String resetAt) {
        int max() {
            return regular ? 3 : 1;
        }

        boolean full() {
            return regular && count >= max();
        }
    }

    private record Palette(int light, int face, int shade, int dark, String ticket) {
    }

    private static final Palette BLUE = new Palette(0xFF9DC0FF, 0xFF3A70B8, 0xFF2C5C9C, 0xFF142A4A, "blue_ticket");
    private static final Palette GOLD = new Palette(0xFFFFE27A, 0xFFE0A830, 0xFFB07C14, 0xFF4A3608, "gold_ticket");

    public static final int WIDTH = 256;
    public static final int HEIGHT = 86;
    // The small pixel font's size, in ticket units per font pixel
    private static final float SMALL = 0.5f;
    // The stub, left of the perforation; the main part's lines: the wait, the clock's rules two
    // to a row, and the tickets
    private static final int PERFORATION = 46;
    private static final int MAIN = PERFORATION + 9;
    private static final int RIGHT = WIDTH - 9;
    private static final int WAIT_TOP = 10;
    private static final int RULES_TOP = 27;
    private static final int RULE_WIDTH = (RIGHT - MAIN - 4) / 2;
    private static final int TICKETS_TOP = HEIGHT - 24;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREEN = 0xFF7BD96B;
    private static final int RED = 0xFFFF6B6B;
    private static final String[] CHECK = {"......#", ".....##", "#...##.", "##.##..", ".###...", "..#...."};
    private static final String[] CROSS = {"#...#", ".#.#.", "..#..", ".#.#.", "#...#"};
    private static final String[] PLAYING = {".#######.", "##.###.##", "#...#.#.#", "##.###.##", ".#######.", "##.....##"};
    private static final String[] SERVER = {"#######", "#.....#", "#######", "#.....#", "#######", "...#...", ".#####."};
    private static final String[] PAUSED = {"##.##", "##.##", "##.##", "##.##", "##.##", "##.##"};
    private static final String[] OFFLINE = {"..###", ".##..", "##...", "##...", "##...", ".##..", "..###"};
    private static final String[] CLOSED = {"#####", "#...#", "#...#", "#..##", "#...#", "#...#", "#####"};
    // What runs each clock and what stops it, two to a row
    private static final String[] GAME_TIME = {"blockpops.ticket.rule.playing", "blockpops.ticket.rule.server_on",
            "blockpops.ticket.rule.paused", "blockpops.ticket.rule.world_closed"};
    private static final String[][] GAME_TIME_ICONS = {PLAYING, SERVER, PAUSED, CLOSED};
    private static final boolean[] GAME_TIME_RUNS = {true, true, false, false};
    private static final String[] REAL_TIME = {"blockpops.ticket.rule.playing", "blockpops.ticket.rule.paused",
            "blockpops.ticket.rule.offline", "blockpops.ticket.rule.world_closed"};
    private static final String[][] REAL_TIME_ICONS = {PLAYING, PAUSED, OFFLINE, CLOSED};
    private static final boolean[] REAL_TIME_RUNS = {true, true, true, true};

    // Whether each token's ticket art has been made, and the ticket item pictures it shows
    private static final boolean[] ART_MADE = new boolean[2];
    private static final int[][] TICKET_PIXELS = new int[2][];

    private TokenTicket() {
    }

    /**
     * Draws the token's ticket above its line's "i": its right edge a little past the icon, kept
     * on the canvas, and above everything else on the screen.
     */
    public static void draw(GuiGraphics graphics, Font font, View view, int iconX, int iconY, int canvasWidth) {
        Palette p = view.regular() ? BLUE : GOLD;
        // The crisp scale, unless the ticket would then leave the canvas
        float fit = Math.min((canvasWidth - 8) / (float) WIDTH, (iconY - 10) / (float) HEIGHT);
        float scale = Math.min(PixelFont.crispScale(SMALL) / SMALL, Math.max(SMALL, fit));
        // On the screen's pixel grid, so the art's pixels stay whole
        double gui = PixelFont.guiScale();
        float left = (float) (Math.round(Math.max(4, Math.min(iconX + 24, canvasWidth - 6) - WIDTH * scale) * gui) / gui);
        float top = (float) (Math.round(Math.max(4, iconY - 6 - HEIGHT * scale) * gui) / gui);
        ResourceLocation art = art(view, p);
        // Above the figures' models: a later stratum from 1.21.6, depth before it
        GuiPose.flush(graphics);
        GuiPose.nextStratum(graphics);
        GuiPose.push(graphics);
        GuiPose.lift(graphics, 400.0f);
        GuiPose.translate(graphics, left, top);
        GuiPose.scale(graphics, scale, scale);
        quad(graphics, art, WIDTH + 4, HEIGHT + 4);
        GuiPose.flush(graphics);
        words(graphics, font, view, p);
        GuiPose.pop(graphics);
    }

    private static void words(GuiGraphics graphics, Font font, View v, Palette p) {
        String admit = tr("blockpops.ticket.admit_one");
        int admitLength = width(admit, 1.0f);
        upText(graphics, admit, 22, (HEIGHT - admitLength) / 2, admitLength, WHITE, 1.0f);
        int serialLength = width("NO. 004127", SMALL);
        upText(graphics, "NO. 004127", 10, (HEIGHT - serialLength) / 2, serialLength, mix(p.light, WHITE, 0.4), SMALL);

        // The wait, with the clock it runs on
        int lite = mix(p.light, WHITE, 0.3);
        String head;
        String time;
        if (v.regular()) {
            head = tr(v.full() ? "blockpops.ticket.stock" : "blockpops.ticket.next_show_in");
            time = v.full() || v.next() == null ? tr("blockpops.ticket.full") : v.next();
        } else if (v.count() > 0) {
            head = v.resetAt() != null ? tr("blockpops.ticket.renews_at", v.resetAt()) : tr("blockpops.ticket.renews_daily");
            time = tr("blockpops.ticket.ready");
        } else {
            head = v.resetAt() != null ? tr("blockpops.ticket.at_in", v.resetAt()) : tr("blockpops.ticket.next_show_in");
            time = v.next() != null ? v.next() : "";
        }
        // The head's capitals centred on the time's
        pixel(graphics, head, MAIN, WAIT_TOP + 3, lite, SMALL, true);
        int timeX = MAIN + width(head, SMALL) + 5;
        pixel(graphics, time, timeX, WAIT_TOP, WHITE, 1.0f, true);
        String clock = tr(v.regular() ? "blockpops.ticket.game_time" : "blockpops.ticket.real_time");
        chip(graphics, clock, timeX + width(time, 1.0f) + 6 + width(clock, SMALL) + 6, WAIT_TOP + 1, p.light, p.dark);

        // What runs the clock and what stops it
        String[] rules = v.regular() ? GAME_TIME : REAL_TIME;
        for (int i = 0; i < rules.length; i++) {
            fitted(graphics, font, tr(rules[i]), MAIN + (i % 2) * (RULE_WIDTH + 4) + 15,
                    RULES_TOP + (i / 2) * 15 + 3, RULE_WIDTH - 15 - 12);
        }
        UiText.draw(graphics, font, tr(v.regular() ? "blockpops.ticket.blue_tickets" : "blockpops.ticket.gold_tickets"), MAIN + 18, TICKETS_TOP + 4, WHITE);
    }

    // ---- the art --------------------------------------------------------------------------------

    private static ResourceLocation art(View v, Palette p) {
        int slot = v.regular() ? 0 : 1;
        ResourceLocation location = ResourceLocations.of("blockpops", "token_ticket/" + (v.regular() ? "regular" : "guaranteed"));
        if (!ART_MADE[slot]) {
            Art a = new Art(WIDTH + 4, HEIGHT + 4);
            paintTicket(a, v, p);
            register(location, a.image());
            ART_MADE[slot] = true;
        }
        return location;
    }

    private static void paintTicket(Art a, View v, Palette p) {
        int w = WIDTH;
        int h = HEIGHT;
        // A notch where the perforation meets each edge, and the corners cut in
        Inside shape = (x, y) -> {
            if (x < 0 || x >= w || y < 0 || y >= h) {
                return false;
            }
            double px = x + 0.5;
            double py = y + 0.5;
            if (near(px, py, PERFORATION, 0, 3.5) || near(px, py, PERFORATION, h, 3.5)) {
                return false;
            }
            return !(near(px, py, 0, 0, 6) || near(px, py, w, 0, 6) || near(px, py, 0, h, 6) || near(px, py, w, h, 6));
        };
        // A soft shadow down and right, then the ticket: the token's colour, darker on the stub,
        // with a faint diagonal sheen and a dark edge
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (shape.test(x, y) && !shape.test(x + 3, y + 3)) {
                    a.blend(x + 3, y + 3, 0x50000000);
                }
            }
        }
        for (int y = 0; y < h; y++) {
            int row = mix(p.face, p.shade, y / (double) (h - 1));
            for (int x = 0; x < w; x++) {
                if (!shape.test(x, y)) {
                    continue;
                }
                boolean edge = !(shape.test(x - 1, y) && shape.test(x + 1, y) && shape.test(x, y - 1) && shape.test(x, y + 1));
                int c = Math.floorMod(x + y, 9) < 2 ? mix(row, WHITE, 0.08) : row;
                a.blend(x, y, edge ? p.dark : x < PERFORATION ? mix(c, 0xFF000000, 0.2) : c);
            }
        }
        // A dashed line inside the edge, clear of the corners and the perforation's notches
        int dash = (p.light & 0xFFFFFF) | 0x90000000;
        for (int x = 3; x < w - 3; x++) {
            for (int y : new int[] {3, h - 4}) {
                if ((x / 3) % 2 == 0 && dashes(x, y, w, h)) {
                    a.blend(x, y, dash);
                }
            }
        }
        for (int y = 3; y < h - 3; y++) {
            for (int x : new int[] {3, w - 4}) {
                if ((y / 3) % 2 == 0 && dashes(x, y, w, h)) {
                    a.blend(x, y, dash);
                }
            }
        }
        for (int y = 6; y < h - 6; y += 4) {
            a.fill(PERFORATION, y, PERFORATION + 1, y + 2, p.dark);
        }
        // A pill for each of the clock's rules: its picture, and a green check or a red cross
        String[][] icons = v.regular() ? GAME_TIME_ICONS : REAL_TIME_ICONS;
        boolean[] runs = v.regular() ? GAME_TIME_RUNS : REAL_TIME_RUNS;
        for (int i = 0; i < runs.length; i++) {
            int x = MAIN + (i % 2) * (RULE_WIDTH + 4);
            int y = RULES_TOP + (i / 2) * 15;
            a.fill(x, y, x + RULE_WIDTH, y + 13, (p.dark & 0xFFFFFF) | 0xC0000000);
            a.icon(icons[i], x + 4, y + 4, (UiText.SHADOW & 0xFFFFFF) | 0xFF000000);
            a.icon(icons[i], x + 3, y + 3, WHITE);
            if (runs[i]) {
                a.icon(CHECK, x + RULE_WIDTH - 10, y + 3, GREEN);
            } else {
                a.icon(CROSS, x + RULE_WIDTH - 9, y + 4, RED);
            }
        }
        a.sprite(TICKET_PIXELS, v.regular() ? 0 : 1, p.ticket, MAIN - 1, TICKETS_TOP);
    }

    private static boolean dashes(int x, int y, int w, int h) {
        double px = x + 0.5;
        double py = y + 0.5;
        boolean nearCorner = near(px, py, 0, 0, 10) || near(px, py, w, 0, 10) || near(px, py, 0, h, 10)
                || near(px, py, w, h, 10);
        return !nearCorner && Math.abs(px - PERFORATION) >= 7;
    }

    private static boolean near(double x, double y, double cx, double cy, double radius) {
        return (x - cx) * (x - cx) + (y - cy) * (y - cy) < radius * radius;
    }

    // ---- words ----------------------------------------------------------------------------------

    // Text drawn smaller when it is wider than room, so a long translation stays clear of the
    // pill's picture and its check or cross
    private static void fitted(GuiGraphics graphics, Font font, String text, int x, int y, int room) {
        int textWidth = font.width(text);
        if (textWidth <= room) {
            UiText.draw(graphics, font, text, x, y, WHITE);
            return;
        }
        float scale = room / (float) textWidth;
        GuiPose.push(graphics);
        GuiPose.translate(graphics, x, y + (1.0f - scale) * (font.lineHeight - 1) / 2.0f);
        GuiPose.scale(graphics, scale, scale);
        UiText.draw(graphics, font, text, 0, 0, WHITE);
        GuiPose.pop(graphics);
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static int width(String text, float scale) {
        return PixelFont.width(Component.literal(text), scale);
    }

    // Pixel-font capitals with their top at y; the font puts them 3 font pixels above its origin
    private static void pixel(GuiGraphics graphics, String text, float x, float y, int color, float scale, boolean shadow) {
        PixelFont.draw(graphics, PixelFont.text(text), x, y + 3 * scale, scale, color, shadow);
    }

    private static void chip(GuiGraphics graphics, String label, int right, int top, int color, int face) {
        int w = width(label, SMALL) + 6;
        graphics.fill(right - w, top, right, top + 9, face);
        Surfaces.outline(graphics, right - w, top, w, 9, color);
        pixel(graphics, label, right - w + 3, top + 2, color, SMALL, true);
    }

    // Pixel text reading bottom to top, its shadow down and right like the rest; (x, y) is the
    // top-left corner of the turned text
    private static void upText(GuiGraphics graphics, String text, int x, int y, int length, int color, float scale) {
        for (int pass = 0; pass < 2; pass++) {
            float offset = pass == 0 ? scale : 0.0f;
            GuiPose.push(graphics);
            GuiPose.translate(graphics, x + offset, y + length + offset);
            GuiPose.rotate(graphics, -90.0f);
            pixel(graphics, text, 0, 0, pass == 0 ? (color & 0xFF000000) | (UiText.SHADOW & 0xFFFFFF) : color, scale,
                    false);
            GuiPose.pop(graphics);
        }
    }

    private static int mix(int from, int to, double amount) {
        double k = Math.max(0.0, Math.min(1.0, amount));
        int a = (int) Math.round((from >>> 24) + ((to >>> 24) - (from >>> 24)) * k);
        int r = (int) Math.round(((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * k);
        int g = (int) Math.round(((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * k);
        int b = (int) Math.round((from & 255) + ((to & 255) - (from & 255)) * k);
        return a << 24 | r << 16 | g << 8 | b;
    }

    @FunctionalInterface
    private interface Inside {
        boolean test(int x, int y);
    }

    /** A picture drawn pixel by pixel, one pixel per GUI unit, then shown as one texture. */
    private static final class Art {
        final int width;
        final int height;
        final int[] argb;

        Art(int width, int height) {
            this.width = width;
            this.height = height;
            this.argb = new int[width * height];
        }

        // Source over what is already there, alpha and all
        void blend(int x, int y, int src) {
            if (x < 0 || y < 0 || x >= width || y >= height || src >>> 24 == 0) {
                return;
            }
            int i = y * width + x;
            if (src >>> 24 == 255) {
                argb[i] = src;
                return;
            }
            int dst = argb[i];
            float a = (src >>> 24) / 255.0f;
            float d = (dst >>> 24) / 255.0f * (1.0f - a);
            float out = a + d;
            int r = Math.round((((src >> 16) & 255) * a + ((dst >> 16) & 255) * d) / out);
            int g = Math.round((((src >> 8) & 255) * a + ((dst >> 8) & 255) * d) / out);
            int b = Math.round(((src & 255) * a + (dst & 255) * d) / out);
            argb[i] = Math.round(out * 255.0f) << 24 | r << 16 | g << 8 | b;
        }

        void fill(int x0, int y0, int x1, int y1, int color) {
            for (int y = Math.max(0, y0); y < Math.min(height, y1); y++) {
                for (int x = Math.max(0, x0); x < Math.min(width, x1); x++) {
                    blend(x, y, color);
                }
            }
        }

        void icon(String[] rows, int x, int y, int color) {
            for (int j = 0; j < rows.length; j++) {
                for (int i = 0; i < rows[j].length(); i++) {
                    if (rows[j].charAt(i) == '#') {
                        blend(x + i, y + j, color);
                    }
                }
            }
        }

        // The mod's own ticket item, read once from its 16x16 picture
        void sprite(int[][] cache, int slot, String name, int x, int y) {
            if (cache[slot] == null) {
                cache[slot] = readSprite(name);
            }
            int[] pixels = cache[slot];
            for (int i = 0; i < pixels.length; i++) {
                blend(x + i % 16, y + i / 16, pixels[i]);
            }
        }

        NativeImage image() {
            NativeImage image = new NativeImage(width, height, true);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int c = argb[y * width + x];
                    //? if >=1.21.2 {
                    /*image.setPixel(x, y, c);
                    *///? } else {
                    image.setPixelRGBA(x, y, (c & 0xFF00FF00) | (c & 0xFF) << 16 | (c >> 16) & 0xFF);
                    //? }
                }
            }
            return image;
        }
    }

    private static int[] readSprite(String name) {
        ResourceLocation location = ResourceLocations.of("blockpops", "textures/item/" + name + ".png");
        try (InputStream stream = Minecraft.getInstance().getResourceManager().getResource(location).orElseThrow().open();
             NativeImage image = NativeImage.read(stream)) {
            int[] pixels = new int[256];
            for (int y = 0; y < Math.min(16, image.getHeight()); y++) {
                for (int x = 0; x < Math.min(16, image.getWidth()); x++) {
                    //? if >=1.21.2 {
                    /*pixels[y * 16 + x] = image.getPixel(x, y);
                    *///? } else {
                    int abgr = image.getPixelRGBA(x, y);
                    pixels[y * 16 + x] = (abgr & 0xFF00FF00) | (abgr & 0xFF) << 16 | (abgr >> 16) & 0xFF;
                    //? }
                }
            }
            return pixels;
        } catch (Exception e) {
            BlockPopsMod.LOGGER.warn("Could not read the ticket picture {}", location, e);
            return new int[0];
        }
    }

    private static void register(ResourceLocation location, NativeImage image) {
        //? if >=1.21.5 {
        /*DynamicTexture texture = new DynamicTexture(location::toString, image);
        *///? } else {
        DynamicTexture texture = new DynamicTexture(image);
        //? }
        Minecraft.getInstance().getTextureManager().register(location, texture);
    }

    // The whole texture drawn at the pose's origin through each version's textured GUI drawing;
    // before 1.21.2 the quad joins a batch drawn at the next flush
    private static void quad(GuiGraphics graphics, ResourceLocation texture, int width, int height) {
        //? if >=1.21.6 {
        /*graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0, 0,
                width, height, width, height, width, height);
        *///? } elif >=1.21.2 {
        /*graphics.blit(RenderType::guiTextured, texture, 0, 0, 0, 0, width, height, width, height, width, height);
        *///? } else {
        VertexConsumer consumer = graphics.bufferSource().getBuffer(RenderType.text(texture));
        Matrix4f pose = graphics.pose().last().pose();
        vertex(consumer, pose, 0, 0, 0, 0);
        vertex(consumer, pose, 0, height, 0, 1);
        vertex(consumer, pose, width, height, 1, 1);
        vertex(consumer, pose, width, 0, 1, 0);
        //? }
    }

    //? if <1.21.2 {
    private static void vertex(VertexConsumer consumer, Matrix4f pose, float x, float y, float u, float v) {
        //? if >=1.21 {
        /*consumer.addVertex(pose, x, y, 0.0f).setColor(0xFFFFFFFF).setUv(u, v).setLight(LightTexture.FULL_BRIGHT);
        *///? } else {
        consumer.vertex(pose, x, y, 0.0f).color(255, 255, 255, 255).uv(u, v).uv2(LightTexture.FULL_BRIGHT).endVertex();
        //? }
    }
    //? }
}
