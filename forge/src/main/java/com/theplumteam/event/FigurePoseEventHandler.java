package com.theplumteam.event;

import com.theplumteam.BlockPopsMod;
import com.theplumteam.blockentity.BoxBlockEntity;
import com.theplumteam.blockentity.FigureBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Event handler for figure pose changes.
 * Uses Forge's event system to intercept shift+right-click with stick,
 * which doesn't trigger the normal block use() method when sneaking.
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, modid = BlockPopsMod.MOD_ID)
public class FigurePoseEventHandler {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // Only handle if player is sneaking and holding a stick
        if (!event.getEntity().isShiftKeyDown()) {
            return;
        }

        if (!event.getItemStack().is(Items.STICK)) {
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
