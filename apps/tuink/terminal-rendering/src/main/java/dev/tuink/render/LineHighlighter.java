package dev.tuink.render;

import java.util.List;

/**
 * Per-line syntax highlighter contract.
 */
public interface LineHighlighter {
    List<StyleSpan> highlight(String line);
}
