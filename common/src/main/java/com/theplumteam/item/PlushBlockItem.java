package com.theplumteam.item;

import com.theplumteam.figure.Holographic;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/** A plush's item, which says in its tooltip when the plush came out holographic. */
public class PlushBlockItem extends BlockItem {
    public PlushBlockItem(Block block, Properties properties) {
        super(block, properties);
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
        if (Holographic.of(BlockEntityItemData.read(stack))) {
            lines.accept(Component.translatable("tooltip.blockpops.holographic").withStyle(ChatFormatting.LIGHT_PURPLE));
        }
    }
}
