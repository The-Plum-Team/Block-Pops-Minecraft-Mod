package com.theplumteam.item;

import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * A ticket a claw or gacha machine turns into a token (see {@link TokenTickets}). Its tooltip says what
 * it is for, in grey: a few lines of flavour in italics, then how to use it. A tooltip
 * does not break lines itself, so each line is its own key, tooltip.blockpops.&lt;id&gt;.flavor.N
 * and tooltip.blockpops.&lt;id&gt;.use.N, numbered from 1 for as long as the language has one.
 */
public class TicketItem extends Item {
    // A language that loses track of its numbering cannot fill a tooltip with lines
    private static final int MAX_LINES = 8;

    private final String descriptionKey;

    public TicketItem(Properties properties, String id) {
        super(properties);
        this.descriptionKey = "tooltip.blockpops." + id;
    }

    @Override
    //? if >=1.21.5 {
    /*public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context,
                                net.minecraft.world.item.component.TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        Consumer<Component> lines = tooltip;
    *///? } elif >=1.21 {
    /*public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        Consumer<Component> lines = tooltip::add;
    *///? } else {
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        Consumer<Component> lines = tooltip::add;
    //? }
        appendLines(lines, descriptionKey + ".flavor.", ChatFormatting.GRAY, ChatFormatting.ITALIC);
        appendLines(lines, descriptionKey + ".use.", ChatFormatting.GRAY);
    }

    private static void appendLines(Consumer<Component> lines, String prefix, ChatFormatting... style) {
        Language language = Language.getInstance();
        for (int i = 1; i <= MAX_LINES && language.has(prefix + i); i++) {
            lines.accept(Component.translatable(prefix + i).withStyle(style));
        }
    }
}
