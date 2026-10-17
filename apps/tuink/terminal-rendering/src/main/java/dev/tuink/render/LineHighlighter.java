package dev.tuink.render;

import java.util.List;

/**
 * Per-line syntax highlighter contract.
 */
public interface LineHighlighter {
    List<StyleSpan> highlight(String line);

    /**
     * Returns visible spans within the supplied input, work and output budgets.
     * Implementations that can process a bounded prefix should override this
     * method. The compatibility adapter leaves oversized lines unstyled before
     * invoking a legacy implementation that could allocate for the complete line.
     */
    default List<StyleSpan> highlight(String line, HighlightRequest request) {
        return request.allowsFullLine(line) ? request.clipSpans(highlight(line)) : List.of();
    }
}
