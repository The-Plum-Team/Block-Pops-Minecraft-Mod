package com.theplumteam.e2e.neoforge;

import com.theplumteam.e2e.E2EHarness;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-only packaged-E2E bootstrap; this class is never shipped in BlockPops. */
@Mod(BlockPopsE2ENeoForge.MOD_ID)
public final class BlockPopsE2ENeoForge {
    public static final String MOD_ID = "blockpops_e2e";

    public BlockPopsE2ENeoForge(IEventBus modEventBus) {
        modEventBus.addListener((FMLClientSetupEvent event) ->
                event.enqueueWork(E2EHarness::start));
    }
}
