package com.theplumteam.e2e;

import com.theplumteam.e2e.generated.ScenarioContract;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class E2EReport {
    private record Entry(
            String id, String status, String message, String captureId, String screenshot) {}

    private final String minecraft;
    private final String role;
    private final String scenario;
    private final List<Entry> steps = new ArrayList<>();

    public E2EReport(String minecraft, String role, String scenario) {
        this.minecraft = minecraft;
        this.role = role;
        this.scenario = scenario;
    }

    public void record(String id, String status, String message, String screenshot) {
        String captureId = screenshot == null ? null : scenario + "." + role + "." + id;
        String bounded = message == null ? "" : message;
        if (bounded.length() > 1024) {
            bounded = bounded.substring(0, 1024);
        }
        steps.add(new Entry(id, status, bounded, captureId, screenshot));
    }

    public boolean allPassed() {
        return !steps.isEmpty() && steps.stream().allMatch(step -> "pass".equals(step.status));
    }

    public File write() {
        File directory = new File(System.getProperty("user.dir"), "e2e-report");
        try {
            Files.createDirectories(directory.toPath());
            File report = new File(directory, "report.json");
            File temporary = new File(directory, ".report.json.tmp");
            Files.writeString(temporary.toPath(), toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary.toPath(),
                        report.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary.toPath(), report.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(
                    new File(directory, "done.marker").toPath(),
                    allPassed() ? "pass" : "fail",
                    StandardCharsets.UTF_8);
            return report;
        } catch (IOException failure) {
            E2ELog.error("could not write report", failure);
            return null;
        }
    }

    private String toJson() {
        StringBuilder output = new StringBuilder();
        output.append("{\n");
        output.append("  \"schema_version\": 1,\n");
        output.append("  \"minecraft\": ").append(quote(minecraft)).append(",\n");
        output.append("  \"role\": ").append(quote(role)).append(",\n");
        output.append("  \"scenario\": ").append(quote(scenario)).append(",\n");
        output.append("  \"contract_sha256\": ")
                .append(quote(ScenarioContract.SHA256)).append(",\n");
        output.append("  \"status\": ").append(quote(allPassed() ? "pass" : "fail")).append(",\n");
        output.append("  \"steps\": [\n");
        for (int index = 0; index < steps.size(); index++) {
            Entry entry = steps.get(index);
            output.append("    {\"id\": ").append(quote(entry.id));
            output.append(", \"status\": ").append(quote(entry.status));
            output.append(", \"message\": ").append(quote(entry.message));
            output.append(", \"capture_id\": ")
                    .append(entry.captureId == null ? "null" : quote(entry.captureId));
            output.append(", \"screenshot\": ")
                    .append(entry.screenshot == null ? "null" : quote(entry.screenshot));
            output.append("}");
            output.append(index + 1 == steps.size() ? "\n" : ",\n");
        }
        output.append("  ]\n}\n");
        return output.toString();
    }

    private static String quote(String value) {
        StringBuilder output = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        return output.append('\"').toString();
    }
}
