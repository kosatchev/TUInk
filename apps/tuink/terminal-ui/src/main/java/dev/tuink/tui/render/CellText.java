package dev.tuink.tui.render;

import dev.tuink.core.DisplayText;
import dev.tuink.tui.style.CellStyle;

/** Shared grapheme-aware drawing and sizing for single-line TUI widgets. */
public final class CellText {
    private static final int TAB_WIDTH = 4;

    private CellText() {
    }

    public static int width(String text) {
        return DisplayText.visualWidth(text == null ? "" : text, TAB_WIDTH);
    }

    /** Draws complete graphemes only and returns the first cell after the text. */
    public static int draw(CellBuffer buffer, int row, int startCol, String text, CellStyle style, int endCol) {
        if (buffer == null || text == null || text.isEmpty()) {
            return startCol;
        }
        int outCol = startCol;
        for (DisplayText.Grapheme grapheme : DisplayText.graphemes(text)) {
            int advance = grapheme.tab()
                ? TAB_WIDTH - (Math.max(0, outCol) % TAB_WIDTH)
                : Math.max(1, grapheme.width());
            if (outCol + advance > endCol) {
                break;
            }
            if (grapheme.tab()) {
                for (int cell = 0; cell < advance; cell++) {
                    if (outCol + cell >= 0) {
                        buffer.set(row, outCol + cell, " ", style);
                    }
                }
            } else if (outCol >= 0) {
                buffer.set(row, outCol, DisplayText.sanitizeGrapheme(grapheme.text()), style);
            }
            outCol += advance;
        }
        return outCol;
    }

    /** Returns a grapheme-safe cell slice. A clipped half of a wide glyph becomes blank. */
    public static String clip(String text, int leftCells, int maxCells) {
        if (text == null || text.isEmpty() || maxCells <= 0) {
            return "";
        }
        int left = Math.max(0, leftCells);
        int right = left + maxCells;
        int visual = 0;
        int used = 0;
        StringBuilder out = new StringBuilder();
        for (DisplayText.Grapheme grapheme : DisplayText.graphemes(text)) {
            int advance = grapheme.tab()
                ? TAB_WIDTH - (visual % TAB_WIDTH)
                : Math.max(1, grapheme.width());
            int next = visual + advance;
            if (next <= left) {
                visual = next;
                continue;
            }
            if (visual >= right) {
                break;
            }
            int available = right - Math.max(left, visual);
            if (grapheme.tab()) {
                int visible = Math.min(advance - Math.max(0, left - visual), available);
                if (visible > 0) {
                    out.append(" ".repeat(visible));
                    used += visible;
                }
            } else if (visual < left) {
                int blank = Math.min(next - left, available);
                if (blank > 0) {
                    out.append(" ".repeat(blank));
                    used += blank;
                }
            } else if (used + advance <= maxCells) {
                out.append(grapheme.text());
                used += advance;
            }
            visual = next;
        }
        return out.toString();
    }

    public static String fit(String text, int cells) {
        if (cells <= 0) {
            return "";
        }
        String clipped = clip(text == null ? "" : text, 0, cells);
        int used = width(clipped);
        return clipped + " ".repeat(Math.max(0, cells - used));
    }

    public static String alignRight(String text, int cells) {
        if (cells <= 0) {
            return "";
        }
        String safe = text == null ? "" : text;
        int total = width(safe);
        if (total >= cells) {
            return clip(safe, total - cells, cells);
        }
        return " ".repeat(cells - total) + safe;
    }
}
