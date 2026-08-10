package com.theplumteam.e2e;

import com.theplumteam.e2e.generated.ScenarioContract.ScenarioId;
import net.minecraft.client.Minecraft;

import java.util.List;

public interface Scenario {
    ScenarioId id();

    List<Step> build(Minecraft minecraft);
}
