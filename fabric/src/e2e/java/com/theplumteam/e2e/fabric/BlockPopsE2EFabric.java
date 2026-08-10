package com.theplumteam.e2e.fabric;

import com.theplumteam.e2e.E2EHarness;
import net.fabricmc.api.ClientModInitializer;

public final class BlockPopsE2EFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        E2EHarness.start();
    }
}
