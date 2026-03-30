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
        String value = line == null ? "" : line;
        int start = DisplayText.floorBoundary(value, startCol);
        int end = DisplayText.floorBoundary(value, Math.max(start, endCol));
        return Math.max(
            0,
            DisplayText.visualColumnAt(value, end, tabWidth)
                - DisplayText.visualColumnAt(value, start, tabWidth)
        );
    }

    public static int absoluteVisualColumn(String line, int textColumn, int tabWidth) {
        return DisplayText.visualColumnAt(line, textColumn, tabWidth);
    }

    public static int visualToTextColumn(String line, int leftColumn, int targetVisualCol, int tabWidth) {
        String value = line == null ? "" : line;
        int left = DisplayText.floorBoundary(value, leftColumn);
        int leftVisual = DisplayText.visualColumnAt(value, left, tabWidth);
        return DisplayText.offsetForVisualColumn(
            value,
            leftVisual + Math.max(0, targetVisualCol),
            tabWidth,
            DisplayText.Bias.NEAREST
        );
    }

    public static int visualToTextColumnFloor(String line, int targetVisualCol, int tabWidth) {
        return DisplayText.offsetForVisualColumn(
            line,
            Math.max(0, targetVisualCol),
            tabWidth,
            DisplayText.Bias.FLOOR
        );
    }

    public static List<Integer> wrapStarts(String line, int maxVisualWidth, boolean wrapByWords, int tabWidth) {
        String value = line == null ? "" : line;
        if (value.isEmpty() || maxVisualWidth <= 0) {
            return List.of(0);
        }
        int safeTabWidth = Math.max(1, tabWidth);
        List<DisplayText.Grapheme> graphemes = DisplayText.graphemes(value);
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        int segmentStartIndex = 0;
        int segmentStartVisual = 0;
        int visual = 0;
        int lastWhitespaceEndIndex = -1;
        int index = 0;

        while (index < graphemes.size()) {
            DisplayText.Grapheme grapheme = graphemes.get(index);
            int advance = grapheme.tab()
                ? safeTabWidth - (visual % safeTabWidth)
                : Math.max(1, grapheme.width());
            if (visual + advance - segmentStartVisual > maxVisualWidth && index > segmentStartIndex) {
                int breakIndex = wrapByWords && lastWhitespaceEndIndex > segmentStartIndex
                    ? lastWhitespaceEndIndex
                    : index;
                int startOffset = graphemes.get(breakIndex).start();
                starts.add(startOffset);
                segmentStartIndex = breakIndex;
                segmentStartVisual = DisplayText.visualColumnAt(value, startOffset, safeTabWidth);
                visual = segmentStartVisual;
                lastWhitespaceEndIndex = -1;
                index = breakIndex;
                continue;
            }

            visual += advance;
            index++;
            if (grapheme.whitespace()) {
                lastWhitespaceEndIndex = index;
            }
            if (visual - segmentStartVisual > maxVisualWidth && index == segmentStartIndex + 1) {
                if (index < graphemes.size()) {
                    starts.add(graphemes.get(index).start());
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
