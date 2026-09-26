package dev.tuiframe.cell;

import dev.tuiframe.text.TerminalText;
import dev.tuiframe.style.CellStyle;
import java.util.Objects;

/** Shared grapheme-aware drawing and sizing for single-line TUI widgets. */
public final class CellText {
    private static final int TAB_WIDTH = 4;

    private CellText() {
    }

    public static int width(String text) {
        return TerminalText.visualWidth(text == null ? "" : text, TAB_WIDTH);
    }

    /** Returns a visual column using the same text-relative tab stops as widgets. */
    public static int visualColumnAt(String text, int offset) {
        return TerminalText.visualColumnAt(text == null ? "" : text, offset, TAB_WIDTH);
    }

    /**
     * Draws complete graphemes at local Canvas coordinates and returns the
     * logical first x-coordinate after the complete text. Tabs use stops
     * relative to the text start, so translation and clipping do not change
     * their phase.
     */
    public static int draw(Canvas canvas, int x, int y, String text, CellStyle style) {
        Objects.requireNonNull(canvas, "canvas");
        if (text == null || text.isEmpty()) {
            return x;
        }
        int outX = x;
        int visual = 0;
        Cell blank = null;
        for (TerminalText.Grapheme grapheme : TerminalText.graphemes(text)) {
            int advance = grapheme.tab()
                ? TAB_WIDTH - (visual % TAB_WIDTH)
                : Math.max(1, grapheme.width());
            int nextOutX = Math.addExact(outX, advance);
            int nextVisual = Math.addExact(visual, advance);
            if (grapheme.tab()) {
                if (blank == null) {
                    blank = new Cell(" ", style);
                }
                for (int cell = 0; cell < advance; cell++) {
                    canvas.put(Math.addExact(outX, cell), y, blank);
                }
            } else {
                canvas.put(outX, y, grapheme.text(), style);
            }
            outX = nextOutX;
            visual = nextVisual;
        }
        return outX;
    }

    /**
     * Draws only the grapheme-safe prefix intersecting a left-aligned cell
     * viewport. Unlike {@link #draw(Canvas, int, int, String, CellStyle)}, this
     * method does not segment the invisible remainder of a long string.
     */
    public static void drawClipped(
        Canvas canvas,
        int x,
        int y,
        String text,
        CellStyle style,
        int maxCells
    ) {
        Objects.requireNonNull(canvas, "canvas");
        if (text == null || text.isEmpty() || maxCells <= 0) {
            return;
        }
        String prefix = TerminalText.prefixForVisualWidth(text, maxCells, TAB_WIDTH);
        int mark = canvas.save();
        try {
            if (!canvas.clip(x, y, maxCells, 1)) {
                return;
            }
            long visual = 0L;
            Cell blank = null;
            for (TerminalText.Grapheme grapheme : TerminalText.graphemes(prefix)) {
                int advance = grapheme.tab()
                    ? TAB_WIDTH - (int) (visual % TAB_WIDTH)
                    : Math.max(1, grapheme.width());
                long remaining = (long) maxCells - visual;
                if (remaining <= 0L) {
                    break;
                }
                if (grapheme.tab()) {
                    if (blank == null) {
                        blank = new Cell(" ", style);
                    }
                    int visible = (int) Math.min((long) advance, remaining);
                    for (int cell = 0; cell < visible; cell++) {
                        putAtLongX(canvas, (long) x + visual + cell, y, blank);
                    }
                } else if (advance <= remaining) {
                    putAtLongX(canvas, (long) x + visual, y, grapheme.text(), style);
                }
                visual += advance;
            }
        } finally {
            canvas.restore(mark);
        }
    }

    private static void putAtLongX(Canvas canvas, long x, int y, Cell cell) {
        if (x >= Integer.MIN_VALUE && x <= Integer.MAX_VALUE) {
            canvas.put((int) x, y, cell);
        }
    }

    private static void putAtLongX(Canvas canvas, long x, int y, String grapheme, CellStyle style) {
        if (x >= Integer.MIN_VALUE && x <= Integer.MAX_VALUE) {
            canvas.put((int) x, y, grapheme, style);
        }
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
        for (TerminalText.Grapheme grapheme : TerminalText.graphemes(text)) {
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
