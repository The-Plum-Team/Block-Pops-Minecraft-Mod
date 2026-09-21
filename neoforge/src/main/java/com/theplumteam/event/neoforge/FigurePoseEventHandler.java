package com.theplumteam.event.neoforge;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.FigureBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Event handler for figure pose changes.
 * Uses Forge's event system to intercept shift+right-click,
 * which doesn't trigger the normal block use() method when sneaking.
 */
// NeoForge 21.6 picks the bus from the event type, so the attribute is gone.
//? if >=1.21.6 {
/*@EventBusSubscriber(modid = BlockPopsMod.MOD_ID)
*///? } else {
@EventBusSubscriber(modid = BlockPopsMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
//? }
public class FigurePoseEventHandler {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // Only handle main hand to prevent double-firing
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        // Only handle if player is sneaking (shift+right-click)
        if (!event.getEntity().isShiftKeyDown()) {
            return;
        }

        // Only process on server side
        if (event.getLevel().isClientSide()) {
            return;
        }

        BlockEntity blockEntity = event.getLevel().getBlockEntity(event.getPos());

        // Handle FigureBlockEntity (extracted figures)
        if (blockEntity instanceof FigureBlockEntity figureBlockEntity) {
            if (figureBlockEntity.hasFigure()) {
                figureBlockEntity.cyclePose();
                com.theplumteam.util.PlayerMessages.actionBar(event.getEntity(),
                    Component.literal("Pose changed to: " + figureBlockEntity.getPoseIndex()));
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }

        // Handle BoxBlockEntity (figures inside boxes) - only allow pose change if figure is extracted
        if (blockEntity instanceof BoxBlockEntity boxBlockEntity) {
            if (boxBlockEntity.hasFigure() && boxBlockEntity.isFigureExtracted()) {
                boxBlockEntity.cyclePose();
                com.theplumteam.util.PlayerMessages.actionBar(event.getEntity(),
                    Component.literal("Pose changed to: " + boxBlockEntity.getPoseIndex()));
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
        }
    }
}
