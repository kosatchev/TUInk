package dev.tuink.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared visual/viewport math used by rendering and mouse-scroll mapping.
 */
public final class ViewportMetrics {
    private ViewportMetrics() {
    }

    public static int gutterWidth(int lineCount) {
        int digits = Math.max(1, String.valueOf(Math.max(1, lineCount)).length());
        return digits + 2;
    }

    public static int textToVisualColumn(String line, int startCol, int endCol, int tabWidth) {
        DisplayText.Metrics metrics = DisplayText.metrics(line, tabWidth);
        int start = metrics.floorBoundary(startCol);
        int end = metrics.floorBoundary(Math.max(start, endCol));
        return Math.max(
            0,
            metrics.visualColumnAt(end) - metrics.visualColumnAt(start)
        );
    }

    public static int absoluteVisualColumn(String line, int textColumn, int tabWidth) {
        return DisplayText.metrics(line, tabWidth).visualColumnAt(textColumn);
    }

    public static int visualToTextColumn(String line, int leftColumn, int targetVisualCol, int tabWidth) {
        DisplayText.Metrics metrics = DisplayText.metrics(line, tabWidth);
        int left = metrics.floorBoundary(leftColumn);
        int leftVisual = metrics.visualColumnAt(left);
        return metrics.offsetForVisualColumn(
            leftVisual + Math.max(0, targetVisualCol),
            DisplayText.Bias.NEAREST
        );
    }

    public static int visualToTextColumnFloor(String line, int targetVisualCol, int tabWidth) {
        return DisplayText.metrics(line, tabWidth).offsetForVisualColumn(
            Math.max(0, targetVisualCol),
            DisplayText.Bias.FLOOR
        );
    }

    public static List<Integer> wrapStarts(String line, int maxVisualWidth, boolean wrapByWords, int tabWidth) {
        return wrapStarts(DisplayText.metrics(line, tabWidth), maxVisualWidth, wrapByWords);
    }

    public static List<Integer> wrapStarts(
        DisplayText.Metrics metrics,
        int maxVisualWidth,
        boolean wrapByWords
    ) {
        if (metrics.text().isEmpty() || maxVisualWidth <= 0) {
            return List.of(0);
        }
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        int segmentStartIndex = 0;
        int segmentStartVisual = 0;
        int visual = 0;
        int lastWhitespaceEndIndex = -1;
        int index = 0;

        while (index < metrics.graphemeCount()) {
            int advance = metrics.visualColumnAtBoundary(index + 1)
                - metrics.visualColumnAtBoundary(index);
            if (visual + advance - segmentStartVisual > maxVisualWidth && index > segmentStartIndex) {
                int breakIndex = wrapByWords && lastWhitespaceEndIndex > segmentStartIndex
                    ? lastWhitespaceEndIndex
                    : index;
                int startOffset = metrics.start(breakIndex);
                starts.add(startOffset);
                segmentStartIndex = breakIndex;
                segmentStartVisual = metrics.visualColumnAtBoundary(breakIndex);
                visual = segmentStartVisual;
                lastWhitespaceEndIndex = -1;
                index = breakIndex;
                continue;
            }

            visual += advance;
            index++;
            if (metrics.isWhitespace(index - 1)) {
                lastWhitespaceEndIndex = index;
            }
            if (visual - segmentStartVisual > maxVisualWidth && index == segmentStartIndex + 1) {
                if (index < metrics.graphemeCount()) {
                    starts.add(metrics.start(index));
                }
                segmentStartIndex = index;
                segmentStartVisual = visual;
                lastWhitespaceEndIndex = -1;
            }
        }
        return List.copyOf(starts);
    }

    public static int wrappedRowsBefore(
        TextModel model,
        int lineIndexExclusive,
        int width,
        boolean wrapByWords,
        int tabWidth
    ) {
        int limit = Math.max(0, Math.min(lineIndexExclusive, model.lineCount()));
        int rows = 0;
        for (int i = 0; i < limit; i++) {
            rows += wrapStarts(model.getLine(i), width, wrapByWords, tabWidth).size();
        }
        return rows;
    }

    public static int totalWrappedRows(TextModel model, int width, boolean wrapByWords, int tabWidth) {
        return wrappedRowsBefore(model, model.lineCount(), width, wrapByWords, tabWidth);
    }

    public static int lineIndexFromWrappedOffset(
        TextModel model,
        int wrappedOffset,
        int width,
        boolean wrapByWords,
        int tabWidth
    ) {
        int remaining = Math.max(0, wrappedOffset);
        for (int line = 0; line < model.lineCount(); line++) {
            int segments = wrapStarts(model.getLine(line), width, wrapByWords, tabWidth).size();
            if (remaining < segments) {
                return line;
            }
            remaining -= segments;
        }
        return Math.max(0, model.lineCount() - 1);
    }
}
