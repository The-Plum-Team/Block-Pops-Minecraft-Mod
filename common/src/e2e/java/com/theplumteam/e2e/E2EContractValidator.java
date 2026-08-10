package com.theplumteam.e2e;

import com.theplumteam.e2e.generated.ScenarioContract;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class E2EContractValidator {
    private E2EContractValidator() {}

    static void validate(Scenario scenario, String role, List<Step> actualSteps) {
        if (scenario == null || actualSteps == null) {
            throw new IllegalArgumentException("scenario and steps are required");
        }
        ScenarioContract.RoleSpec expected = ScenarioContract.role(scenario.id(), role);
        List<ScenarioContract.StepSpec> expectedSteps = expected.steps();
        if (actualSteps.size() != expectedSteps.size()) {
            throw new IllegalStateException(
                    "E2E step count drift for " + scenario.id().externalId() + "/" + role);
        }
        Set<String> names = new HashSet<>();
        for (int index = 0; index < expectedSteps.size(); index++) {
            ScenarioContract.StepSpec spec = expectedSteps.get(index);
            Step actual = actualSteps.get(index);
            if (actual == null || !names.add(actual.name) || !spec.id().equals(actual.name)) {
                throw new IllegalStateException(
                        "E2E step identity/order drift at index " + index);
            }
            if ((actual.screenshot != null) != spec.captureRequired()) {
                throw new IllegalStateException("E2E capture drift at " + actual.name);
            }
            String expectedScreenshot = scenario.id().externalId() + "." + role + "."
                    + actual.name + ".png";
            if (spec.captureRequired() && !expectedScreenshot.equals(actual.screenshot)) {
                throw new IllegalStateException(
                        "E2E screenshot basename must encode semantic capture_id "
                                + expectedScreenshot);
            }
            if (spec.assertionRequired() && actual.assertion == null) {
                throw new IllegalStateException("required E2E assertion missing at " + actual.name);
            }
        }
    }
}
