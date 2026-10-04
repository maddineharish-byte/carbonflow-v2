package com.carbonflow.perf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Collects Phase 10.10 measurements and renders them as evidence.
 *
 * <h2>Why a machine-readable artefact as well as prose</h2>
 * <p>Two outputs are written: {@code perf-baseline.json} (every measurement, so
 * a later phase can diff against this baseline without re-deriving anything)
 * and {@code PERFORMANCE-BASELINE.md} (the human report). The JSON is the
 * primary artefact — if the two ever disagree, the JSON is what was measured
 * and the prose is a rendering error to be fixed, not a second opinion.
 *
 * <h2>No invented numbers</h2>
 * <p>Every method here takes a value that was actually observed in this run.
 * There is no default, no fallback and no interpolation. Where a measurement
 * could not be taken the harness records that fact rather than a plausible
 * stand-in, because a table cell that looks like a measurement but is not is
 * worse than an empty one.
 */
public final class PerfReport {

    /** One captured measurement. */
    public record Entry(String section, String scenario, String metric, Object value,
                        String unit, String note) {

        static Entry of(String section, String scenario, String metric, Object value, String unit) {
            return new Entry(section, scenario, metric, value, unit, null);
        }

        Entry withNote(String note) {
            return new Entry(section, scenario, metric, value, unit, note);
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<String> tables = new ArrayList<>();
    private final Map<String, Object> context = new LinkedHashMap<>();

    public PerfReport context(String key, Object value) {
        context.put(key, value);
        return this;
    }

    public void record(String section, String scenario, String metric, Object value, String unit) {
        entries.add(Entry.of(section, scenario, metric, value, unit));
    }

    public void record(String section, String scenario, String metric, Object value, String unit,
                       String note) {
        entries.add(new Entry(section, scenario, metric, value, unit, note));
    }

    /** Appends a rendered markdown table verbatim. */
    public void table(String markdown) {
        tables.add(markdown);
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /**
     * Writes both artefacts.
     *
     * @param targetDirectory usually {@code target/perf}
     * @param baseName        file-name stem for both outputs
     */
    public void write(Path targetDirectory, String baseName) throws IOException {
        Files.createDirectories(targetDirectory);
        Files.writeString(targetDirectory.resolve(baseName + ".json"),
                toJson(), StandardCharsets.UTF_8);
        Files.writeString(targetDirectory.resolve(baseName + ".md"),
                toMarkdown(), StandardCharsets.UTF_8);
    }

    private String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"context\": {\n");
        int i = 0;
        for (Map.Entry<String, Object> e : context.entrySet()) {
            json.append("    ").append(quote(e.getKey())).append(": ").append(json(e.getValue()));
            json.append(++i < context.size() ? ",\n" : "\n");
        }
        json.append("  },\n");
        json.append("  \"measurements\": [\n");
        for (int k = 0; k < entries.size(); k++) {
            Entry e = entries.get(k);
            json.append("    {\"section\": ").append(quote(e.section()))
                    .append(", \"scenario\": ").append(quote(e.scenario()))
                    .append(", \"metric\": ").append(quote(e.metric()))
                    .append(", \"value\": ").append(json(e.value()))
                    .append(", \"unit\": ").append(quote(e.unit()));
            if (e.note() != null) {
                json.append(", \"note\": ").append(quote(e.note()));
            }
            json.append("}").append(k + 1 < entries.size() ? ",\n" : "\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    private String toMarkdown() {
        StringBuilder md = new StringBuilder();
        md.append("```json\n");
        md.append(toJson());
        md.append("```\n\n");
        for (String table : tables) {
            md.append(table).append("\n");
        }
        return md.toString();
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            if (value instanceof Double d && (Double.isNaN(d) || Double.isInfinite(d))) {
                return "null";
            }
            if (value instanceof Float f && (Float.isNaN(f) || Float.isInfinite(f))) {
                return "null";
            }
            return String.valueOf(value);
        }
        return quote(String.valueOf(value));
    }

    // ------------------------------------------------------------------
    // Table helpers
    // ------------------------------------------------------------------

    /** Renders a markdown table from a header row and body rows. */
    public static String table(List<String> header, List<List<String>> rows) {
        StringBuilder md = new StringBuilder();
        md.append("| ").append(String.join(" | ", header)).append(" |\n");
        md.append("|").append(" --- |".repeat(header.size())).append("\n");
        for (List<String> row : rows) {
            md.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        return md.toString().trim();
    }

    /** Formats a double for a report cell without implying unwarranted precision. */
    public static String num(double value) {
        if (Double.isNaN(value)) {
            return "n/a";
        }
        if (value >= 1000) {
            return String.format(Locale.ROOT, "%,.0f", value);
        }
        if (value >= 100) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
