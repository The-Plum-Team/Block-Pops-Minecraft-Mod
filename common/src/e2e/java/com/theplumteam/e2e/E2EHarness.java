package com.theplumteam.e2e;

import com.theplumteam.e2e.generated.ScenarioContract.ScenarioId;
import com.theplumteam.e2e.scenario.InWorldScenario;
import com.theplumteam.e2e.scenario.UiRegressionScenario;
import dev.architectury.event.events.client.ClientTickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

public final class E2EHarness {
    private enum State {
        WAIT_WORLD,
        RUN_STEPS,
        FLUSH,
        DONE
    }

    private static boolean started;

    private final String minecraft;
    private final String role;
    private final String scenarioId;
    private final E2EReport report;
    private State state = State.WAIT_WORLD;
    private int tick;
    private int deadline;
    private List<Step> steps;
    private int stepIndex;
    private boolean actionRun;
    private int actionTick;
    private int readyTick = -1;

    private E2EHarness(String minecraft, String role, String scenarioId) {
        this.minecraft = minecraft;
        this.role = role;
        this.scenarioId = scenarioId;
        report = new E2EReport(minecraft, role, scenarioId);
    }

    public static synchronized void start() {
        if (started || !Boolean.getBoolean("blockpops.e2e.enabled")) {
            return;
        }
        started = true;
        String minecraft = System.getProperty("blockpops.e2e.minecraft", "1.20.1");
        String role = System.getProperty("blockpops.e2e.role", "client_a");
        String scenario = System.getProperty("blockpops.e2e.scenario", "ui-regression");
        E2ELog.info("activating minecraft=" + minecraft + " role=" + role
                + " scenario=" + scenario);
        E2EHarness harness = new E2EHarness(minecraft, role, scenario);
        ClientTickEvent.CLIENT_POST.register(harness::tick);
    }

    private Scenario resolveScenario() {
        ScenarioId selected = ScenarioId.fromExternal(scenarioId);
        Scenario scenario = switch (selected) {
            case UI_REGRESSION -> new UiRegressionScenario();
            case IN_WORLD -> new InWorldScenario();
        };
        if (scenario.id() != selected) {
            throw new IllegalStateException("scenario implementation identity drift");
        }
        return scenario;
    }

    private void tick(Minecraft minecraftClient) {
        tick++;
        try {
            switch (state) {
                case WAIT_WORLD -> waitForWorld(minecraftClient);
                case RUN_STEPS -> runStep(minecraftClient);
                case FLUSH -> flush(minecraftClient);
                case DONE -> {
                }
            }
        } catch (Throwable failure) {
            E2ELog.error("harness tick crashed", failure);
            report.record("harness_crash", "fail", failure.toString(), null);
            finish(minecraftClient);
        }
    }

    private void waitForWorld(Minecraft minecraftClient) {
        if (deadline == 0) {
            deadline = tick + 20 * 120;
        }
        Screen screen = VanillaShim.currentScreen(minecraftClient);
        if (VanillaShim.isWarningOrErrorScreen(screen)) {
            report.record(
                    "startup_warning",
                    "fail",
                    "unexpected startup warning/error screen " + screen.getClass().getName(),
                    null);
            finish(minecraftClient);
            return;
        }
        if (minecraftClient.player != null && minecraftClient.level != null
                && !VanillaShim.overlayPresent(minecraftClient)) {
            Scenario scenario = resolveScenario();
            steps = scenario.build(minecraftClient);
            E2EContractValidator.validate(scenario, role, steps);
            state = State.RUN_STEPS;
            return;
        }
        if (tick > deadline) {
            report.record("join_world", "timeout", "world join exceeded 120 seconds", null);
            finish(minecraftClient);
        }
    }

    private void runStep(Minecraft minecraftClient) {
        if (stepIndex >= steps.size()) {
            state = State.FLUSH;
            deadline = tick + 80;
            return;
        }
        Step step = steps.get(stepIndex);
        if (!actionRun) {
            actionRun = true;
            actionTick = tick;
            E2ELog.info("step " + step.name + " action");
            if (step.action != null) {
                try {
                    step.action.run();
                } catch (Throwable failure) {
                    report.record(step.name, "fail", "action threw: " + failure, null);
                    advance();
                    return;
                }
            }
        }
        int waited = tick - actionTick;
        boolean ready = waited >= step.minTicks && (step.ready == null || safe(step.ready));
        if (!ready) {
            readyTick = -1;
            if (waited > step.timeoutTicks) {
                report.record(step.name, "timeout", "ready predicate timed out", null);
                advance();
            }
            return;
        }
        if (readyTick < 0) {
            readyTick = tick;
            if (step.screenshot != null) {
                // Every frame rendered while the step settles is then free of toasts.
                VanillaShim.clearToasts(minecraftClient);
            }
        }
        if (tick - readyTick < step.settleTicks) {
            return;
        }
        String screenshot = null;
        if (step.screenshot != null && VanillaShim.screenshot(minecraftClient, step.screenshot)) {
            screenshot = step.screenshot;
        }
        Step.Result result;
        try {
            result = step.assertion == null
                    ? Step.Result.fail("contracted assertion missing")
                    : step.assertion.run();
        } catch (Throwable failure) {
            result = Step.Result.fail("assertion threw: " + failure);
        }
        if (step.screenshot != null && screenshot == null) {
            result = Step.Result.fail("screenshot dispatch failed; assertion=" + result.message());
        }
        report.record(
                step.name, result.pass() ? "pass" : "fail", result.message(), screenshot);
        advance();
    }

    private static boolean safe(java.util.function.BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (Throwable failure) {
            return false;
        }
    }

    private void advance() {
        stepIndex++;
        actionRun = false;
        readyTick = -1;
    }

    private void flush(Minecraft minecraftClient) {
        if (tick >= deadline) {
            finish(minecraftClient);
        }
    }

    private void finish(Minecraft minecraftClient) {
        if (state == State.DONE) {
            return;
        }
        state = State.DONE;
        report.write();
        E2ELog.info("finished; passed=" + report.allPassed());
    }
}
