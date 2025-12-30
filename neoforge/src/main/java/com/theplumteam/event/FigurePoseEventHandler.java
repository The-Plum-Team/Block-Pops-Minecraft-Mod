package com.theplumteam.event;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.FigureBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Event handler for figure pose changes.
 * Uses NeoForge's event system to intercept shift+right-click,
 * which doesn't trigger the normal block use() method when sneaking.
 */
@EventBusSubscriber(bus = EventBusSubscriber.Bus.GAME, modid = BlockPopsMod.MOD_ID)
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
                event.getEntity().displayClientMessage(
                    Component.literal("Pose changed to: " + figureBlockEntity.getPoseIndex()), true);
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }

        // Handle BoxBlockEntity (figures inside boxes) - only allow pose change if figure is extracted
        if (blockEntity instanceof BoxBlockEntity boxBlockEntity) {
            if (boxBlockEntity.hasFigure() && boxBlockEntity.isFigureExtracted()) {
                boxBlockEntity.cyclePose();
                event.getEntity().displayClientMessage(
                    Component.literal("Pose changed to: " + boxBlockEntity.getPoseIndex()), true);
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
        }
    }
}
