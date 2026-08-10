package com.theplumteam.e2e.forge;

import com.theplumteam.e2e.E2EHarness;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod("blockpops_e2e")
public final class BlockPopsE2EForge {
    public BlockPopsE2EForge() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        bus.addListener(this::clientSetup);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist == Dist.CLIENT) {
            event.enqueueWork(E2EHarness::start);
        }
    }
}
