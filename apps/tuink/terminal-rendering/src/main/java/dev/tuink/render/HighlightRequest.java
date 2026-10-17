package dev.tuink.render;

import java.util.ArrayList;
import java.util.List;

/**
 * One bounded syntax request. Coordinates and returned spans address the original
 * line in UTF-16 units. Work counts character reads or equivalent lexer steps;
 * the scan limit includes any prefix needed to establish lexical context.
 */
public record HighlightRequest(
    int startUtf16,
    int endUtf16,
    int maxScanCodeUnits,
    int maxSpans,
    long maxWork
) {
    public HighlightRequest {
        if (startUtf16 < 0 || endUtf16 < startUtf16
            || maxScanCodeUnits < 0 || maxSpans < 0 || maxWork < 0) {
            throw new IllegalArgumentException("Invalid syntax highlighting bounds");
        }
    }

    /** End of the permitted prefix, without splitting a supplementary code point. */
    public int scanEnd(String line) {
        int end = Math.min(line.length(), maxScanCodeUnits);
        if (end > 0 && end < line.length() && Character.isHighSurrogate(line.charAt(end - 1))
            && Character.isLowSurrogate(line.charAt(end))) {
            end--;
        }
        return end;
    }

    /** Legacy implementations receive only complete lines within the input budget. */
    public boolean allowsFullLine(String line) {
        return line != null && startUtf16 < endUtf16 && maxSpans > 0 && maxWork > 0
            && line.length() <= maxScanCodeUnits && line.length() <= maxWork;
    }

    /** Clips output before retaining or copying an implementation's complete result. */
    public List<StyleSpan> clipSpans(List<StyleSpan> spans) {
        if (spans == null || spans.isEmpty() || startUtf16 == endUtf16 || maxSpans == 0 || maxWork == 0) {
            return List.of();
        }
        List<StyleSpan> visible = new ArrayList<>();
        long remaining = maxWork;
        for (StyleSpan span : spans) {
            if (remaining-- == 0) {
                return List.of();
            }
            int start = Math.max(startUtf16, span.start());
            int end = Math.min(endUtf16, span.end());
            if (end > start) {
                if (visible.size() == maxSpans) {
                    return List.of();
                }
                visible.add(new StyleSpan(start, end, span.style()));
            }
        }
        return List.copyOf(visible);
    }
}
