package dev.tuink.tui.ui;

import dev.tuiframe.cell.CellText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DirtyTabSummary {
    private static final String UNNAMED = "--no name--";

    private DirtyTabSummary() {
    }

    public static String build(List<Path> files, int maxCells) {
        List<String> labels = displayLabels(files);
        String prefix = "Unsaved (" + labels.size() + "): ";
        if (labels.isEmpty()) {
            return "Unsaved (0)";
        }

        String full = prefix + String.join(", ", labels);
        if (maxCells <= 0) {
            return "";
        }
        if (CellText.width(full) <= maxCells) {
            return full;
        }
        if (labels.size() == 1) {
            return ellipsize(full, maxCells);
        }

        for (int included = labels.size() - 1; included >= 0; included--) {
            int remaining = labels.size() - included;
            StringBuilder candidate = new StringBuilder(prefix);
            if (included > 0) {
                candidate.append(String.join(", ", labels.subList(0, included))).append(", ");
            }
            candidate.append("… +").append(remaining);
            if (CellText.width(candidate.toString()) <= maxCells) {
                return candidate.toString();
            }
        }
        return ellipsize(full, maxCells);
    }

    static List<String> displayLabels(List<Path> files) {
        List<Path> safeFiles = files == null ? List.of() : files;
        List<String> baseLabels = new ArrayList<>(safeFiles.size());
        Map<String, Integer> baseCounts = new HashMap<>();
        for (Path file : safeFiles) {
            String label = baseLabel(file);
            baseLabels.add(label);
            baseCounts.merge(label, 1, Integer::sum);
        }

        List<String> resolved = new ArrayList<>(safeFiles.size());
        for (int i = 0; i < safeFiles.size(); i++) {
            Path file = safeFiles.get(i);
            String base = baseLabels.get(i);
            resolved.add(baseCounts.getOrDefault(base, 0) > 1 && file != null ? file.toString() : base);
        }

        Map<String, Integer> resolvedCounts = new HashMap<>();
        for (String label : resolved) {
            resolvedCounts.merge(label, 1, Integer::sum);
        }
        Map<String, Integer> occurrences = new HashMap<>();
        List<String> unique = new ArrayList<>(resolved.size());
        for (String label : resolved) {
            if (resolvedCounts.getOrDefault(label, 0) <= 1) {
                unique.add(label);
                continue;
            }
            int occurrence = occurrences.merge(label, 1, Integer::sum);
            unique.add(label + " #" + occurrence);
        }
        return List.copyOf(unique);
    }

    private static String baseLabel(Path file) {
        if (file == null) {
            return UNNAMED;
        }
        Path fileName = file.getFileName();
        return fileName == null ? file.toString() : fileName.toString();
    }

    private static String ellipsize(String text, int maxCells) {
        if (maxCells <= 0) {
            return "";
        }
        if (maxCells == 1) {
            return "…";
        }
        return CellText.clip(text, 0, maxCells - 1) + "…";
    }
}
