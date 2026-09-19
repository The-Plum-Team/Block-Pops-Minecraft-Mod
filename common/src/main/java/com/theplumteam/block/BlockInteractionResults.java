package com.theplumteam.block;

import net.minecraft.world.InteractionResult;
//? if >=1.21 {
/*import net.minecraft.world.ItemInteractionResult;
*///? }

/** Preserves the legacy block result without dispatching a second block interaction. */
public final class BlockInteractionResults {
    private BlockInteractionResults() {
    }

    //? if >=1.21 {
    /*public static ItemInteractionResult forItem(InteractionResult result) {
        return switch (result) {
            case SUCCESS -> ItemInteractionResult.SUCCESS;
            case CONSUME -> ItemInteractionResult.CONSUME;
            case CONSUME_PARTIAL -> ItemInteractionResult.CONSUME_PARTIAL;
            case PASS -> ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
            case FAIL -> ItemInteractionResult.FAIL;
            case SUCCESS_NO_ITEM_USED -> throw new IllegalArgumentException(
                    "Result is not part of the legacy block interaction contract: " + result);
        };
    }
    *///? }
}
