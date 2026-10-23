package com.theplumteam.client.gui.creator.ui;

import com.theplumteam.client.worldcontent.ClientWorldCollections;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.IntSupplier;

/**
 * How much of a storage limit is used, as the mockup's row of twenty small blocks with "18.4 of
 * 64 MiB" under it, right-aligned and free to run left of the blocks. The blocks are green,
 * amber from 85% and red once the limit is reached. By default it shows the space this server
 * keeps for every World collection's files, as last synced; other sources, such as one
 * collection's share, can be given instead. The numbers are read each frame, so a sync shows
 * without a rebuild.
 */
public class UiQuotaMeter extends UiNode.Box {
    public static final int HEIGHT = 18;
    private static final int SEGMENTS = 20;
    private static final int SEGMENT_HEIGHT = 6;
    private static final int EMPTY = 0xFF2E2E2E;
    private static final int TEXT = 0xFFC8C8C8;

    private final IntSupplier usedKiB;
    private final IntSupplier maxKiB;
    @Nullable
    private Component suffix;
    @Nullable
    private Component tooltip;

    /** The space of this server's World collection files. */
    public UiQuotaMeter() {
        this(ClientWorldCollections::quotaUsedKiB, ClientWorldCollections::quotaMaxKiB);
        this.tooltip = CreatorScreen.tr("quota.tooltip");
    }

    public UiQuotaMeter(IntSupplier usedKiB, IntSupplier maxKiB) {
        this.usedKiB = usedKiB;
        this.maxKiB = maxKiB;
        this.height = HEIGHT;
    }

    /** Text after the amount, such as " · 5 of 32 collections"; null for none. */
    public UiQuotaMeter suffix(@Nullable Component suffix) {
        this.suffix = suffix;
        return this;
    }

    public UiQuotaMeter tooltip(@Nullable Component tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    /** An amount of kibibytes in mebibytes, as "18.4 of 64 MiB"; a whole limit has no decimal. */
    public static Component text(int usedKiB, int maxKiB) {
        String used = String.format(Locale.ROOT, "%.1f", Math.max(0, usedKiB) / 1024.0);
        String limit = maxKiB % 1024 == 0 ? String.valueOf(maxKiB / 1024)
                : String.format(Locale.ROOT, "%.1f", maxKiB / 1024.0);
        return CreatorScreen.tr("quota.mib", used, limit);
    }

    @Override
    public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible()) {
            return;
        }
        int used = usedKiB.getAsInt();
        int limit = maxKiB.getAsInt();
        float fraction = limit <= 0 ? 0.0f : Math.min(1.0f, used / (float) limit);
        int filled = Math.round(fraction * SEGMENTS);
        if (filled == 0 && used > 0 && limit > 0) {
            // Anything stored shows, however little
            filled = 1;
        }
        int colour = fraction >= 1.0f ? Theme.RED : fraction >= 0.85f ? Theme.AMBER : Theme.GREEN;
        for (int i = 0; i < SEGMENTS; i++) {
            int left = x + i * width / SEGMENTS;
            int right = x + (i + 1) * width / SEGMENTS - 1;
            graphics.fill(left, y, Math.max(left + 1, right), y + SEGMENT_HEIGHT, i < filled ? colour : EMPTY);
        }
        if (limit > 0) {
            Font font = Minecraft.getInstance().font;
            String text = text(used, limit).getString() + (suffix == null ? "" : suffix.getString());
            // Right-aligned and free to run left past the blocks, as in the mockup
            graphics.drawString(font, text, x + width - font.width(text), y + SEGMENT_HEIGHT + 4, TEXT, false);
        }
        if (tooltip != null && hovered(mouseX, mouseY)) {
            UiTooltip.set(tooltip);
        }
    }
}
