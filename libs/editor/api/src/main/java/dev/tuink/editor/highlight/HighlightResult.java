package dev.tuink.editor.highlight;

import java.util.List;

/**
 * Bounded display styles and whether another request can resume retained context work.
 * A plain-text fallback with no resumable progress is a completed result.
 */
public record HighlightResult(List<TokenSpan> spans, boolean retryPending) {
    public HighlightResult {
        spans = List.copyOf(spans);
    }

    public static HighlightResult completed(List<TokenSpan> spans) {
        return new HighlightResult(spans, false);
    }
}
