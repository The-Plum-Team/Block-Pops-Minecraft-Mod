package com.theplumteam.e2e;

public final class E2ELog {
    private static final String PREFIX = "[BlockPops-E2E] ";

    private E2ELog() {}

    public static void info(String message) {
        System.out.println(PREFIX + message);
    }

    public static void warn(String message) {
        System.err.println(PREFIX + "WARN " + message);
    }

    public static void error(String message, Throwable failure) {
        System.err.println(PREFIX + "ERROR " + message);
        failure.printStackTrace(System.err);
    }
}
