package com.theplumteam.e2e;

import java.util.function.BooleanSupplier;

public final class Step {
    public record Result(boolean pass, String message) {
        public static Result pass(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }
    }

    @FunctionalInterface
    public interface Check {
        Result run() throws Exception;
    }

    final String name;
    Runnable action;
    BooleanSupplier ready;
    int minTicks = 5;
    int settleTicks = 0;
    int timeoutTicks = 400;
    String screenshot;
    Check assertion;

    private Step(String name) {
        this.name = name;
    }

    public static Step of(String name) {
        return new Step(name);
    }

    public Step action(Runnable value) {
        action = value;
        return this;
    }

    public Step ready(BooleanSupplier value) {
        ready = value;
        return this;
    }

    public Step minTicks(int value) {
        minTicks = value;
        return this;
    }

    public Step settleTicks(int value) {
        settleTicks = value;
        return this;
    }

    public Step timeoutTicks(int value) {
        timeoutTicks = value;
        return this;
    }

    public Step screenshot(String value) {
        screenshot = value;
        return this;
    }

    public Step assertion(Check value) {
        assertion = value;
        return this;
    }
}
