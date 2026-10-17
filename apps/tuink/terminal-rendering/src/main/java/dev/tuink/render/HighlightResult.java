package dev.tuink.render;

import java.util.List;

/**
 * Bounded display styles and whether another request can resume retained context work.
 * A plain-text fallback with no resumable progress is a completed result.
 */
public record HighlightResult(List<StyleSpan> spans, boolean retryPending) {
    public HighlightResult {
        spans = List.copyOf(spans);
    }

    public static HighlightResult completed(List<StyleSpan> spans) {
        return new HighlightResult(spans, false);
    }
}
