package dev.tuink.render;

import java.util.List;

/**
 * Render-ready visual data for a single visible editor row.
 */
public record LineVisual(String gutter, String text, List<StyleSpan> spans) {
}
