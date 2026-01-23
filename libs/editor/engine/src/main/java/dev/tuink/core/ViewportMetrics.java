package dev.tuink.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared visual/viewport math used by rendering and mouse-scroll mapping.
 */
public final class ViewportMetrics {
    private ViewportMetrics() {
    }

    public static int textToVisualColumn(String line, int startCol, int endCol, int tabWidth) {
        int safeTabWidth = Math.max(1, tabWidth);
        int start = Math.max(0, Math.min(startCol, line.length()));
        int end = Math.max(start, Math.min(endCol, line.length()));
        int visual = 0;
        for (int i = start; i < end; i++) {
            char ch = line.charAt(i);
            int advance = ch == '\t' ? safeTabWidth - (visual % safeTabWidth) : 1;
            visual += advance;
        }
        return visual;
    }

    public static int visualToTextColumn(String line, int leftColumn, int targetVisualCol, int tabWidth) {
        int safeTabWidth = Math.max(1, tabWidth);
        int len = line.length();
        int col = Math.max(0, Math.min(leftColumn, len));
        int visual = 0;

        while (col < len) {
            char ch = line.charAt(col);
            int advance = ch == '\t' ? safeTabWidth - (visual % safeTabWidth) : 1;
            int nextVisual = visual + advance;
            if (targetVisualCol < nextVisual) {
                int leftDist = targetVisualCol - visual;
                int rightDist = nextVisual - targetVisualCol;
                return leftDist <= rightDist ? col : col + 1;
            }
            if (targetVisualCol == nextVisual) {
                return col + 1;
            }
            visual = nextVisual;
            col++;
        }
        return len;
    }

    public static List<Integer> wrapStarts(String line, int maxVisualWidth, boolean wrapByWords, int tabWidth) {
        if (line.isEmpty()) {
            return List.of(0);
        }
        if (maxVisualWidth <= 0) {
            return List.of(0);
        }
        List<Integer> starts = new ArrayList<>();
        int start = 0;
        starts.add(0);
        while (start < line.length()) {
            int end = wrapByWords
                ? advanceByWordWrap(line, start, maxVisualWidth, tabWidth)
                : advanceByVisualWidth(line, start, maxVisualWidth, tabWidth);
            if (end <= start) {
                end = Math.min(line.length(), start + 1);
            }
            if (end < line.length()) {
                starts.add(end);
            }
            start = end;
        }
        return starts;
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

    private static int advanceByVisualWidth(String line, int start, int maxVisualWidth, int tabWidth) {
        int safeTabWidth = Math.max(1, tabWidth);
        int visual = 0;
        int i = Math.max(0, Math.min(start, line.length()));
        while (i < line.length()) {
            char ch = line.charAt(i);
            int adv = ch == '\t' ? safeTabWidth - (visual % safeTabWidth) : 1;
            if (visual + adv > maxVisualWidth) {
                break;
            }
            visual += adv;
            i++;
        }
        return i;
    }

    private static int advanceByWordWrap(String line, int start, int maxVisualWidth, int tabWidth) {
        int safeTabWidth = Math.max(1, tabWidth);
        int visual = 0;
        int i = Math.max(0, Math.min(start, line.length()));
        int lastBreakAfterWhitespace = -1;
        while (i < line.length()) {
            char ch = line.charAt(i);
            int adv = ch == '\t' ? safeTabWidth - (visual % safeTabWidth) : 1;
            if (visual + adv > maxVisualWidth) {
                break;
            }
            visual += adv;
            i++;
            if (Character.isWhitespace(ch)) {
                lastBreakAfterWhitespace = i;
            }
        }
        if (i >= line.length()) {
            return i;
        }
        if (lastBreakAfterWhitespace > start) {
            return lastBreakAfterWhitespace;
        }
        return i;
    }
}
