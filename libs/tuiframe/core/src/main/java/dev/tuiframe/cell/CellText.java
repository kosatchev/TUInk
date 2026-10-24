package dev.tuiframe.cell;

import dev.tuiframe.text.TerminalText;
import dev.tuiframe.style.CellStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared grapheme-aware drawing and sizing for single-line TUI widgets. */
public final class CellText {
    private static final int TAB_WIDTH = 4;
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

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
        TerminalText.GraphemeCursor cursor = TerminalText.graphemeCursor(text);
        while (cursor.advance()) {
            int advance = cursor.isTab()
                ? TAB_WIDTH - (visual % TAB_WIDTH)
                : Math.max(1, cursor.width());
            int nextOutX = Math.addExact(outX, advance);
            int nextVisual = Math.addExact(visual, advance);
            if (cursor.isTab()) {
                if (blank == null) {
                    blank = new Cell(" ", style);
                }
                for (int cell = 0; cell < advance; cell++) {
                    canvas.put(Math.addExact(outX, cell), y, blank);
                }
            } else {
                canvas.put(outX, y, TerminalText.sanitizeGrapheme(text, cursor.start(), cursor.end()), style);
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
        int mark = canvas.save();
        try {
            if (!canvas.clip(x, y, maxCells, 1)) {
                return;
            }
            long visual = 0L;
            Cell blank = null;
            TerminalText.GraphemeCursor cursor = TerminalText.graphemeCursor(text);
            while (visual < maxCells && cursor.advance()) {
                int advance = cursor.isTab()
                    ? TAB_WIDTH - (int) (visual % TAB_WIDTH)
                    : Math.max(1, cursor.width());
                long remaining = (long) maxCells - visual;
                if (remaining <= 0L) {
                    break;
                }
                if (cursor.isTab()) {
                    if (blank == null) {
                        blank = new Cell(" ", style);
                    }
                    int visible = (int) Math.min((long) advance, remaining);
                    for (int cell = 0; cell < visible; cell++) {
                        putAtLongX(canvas, (long) x + visual + cell, y, blank);
                    }
                } else if (advance <= remaining) {
                    putAtLongX(canvas, (long) x + visual, y,
                        TerminalText.sanitizeGrapheme(text, cursor.start(), cursor.end()), style);
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

    /**
     * Returns a display-ready grapheme-safe cell slice. A clipped half of a wide glyph becomes blank.
     * Retained graphemes use the same visible representation as a Cell, so an orphan mark cannot
     * join the expanded tab's spaces when the slice is drawn again.
     */
    public static String clip(String text, int leftCells, int maxCells) {
        if (text == null || text.isEmpty() || maxCells <= 0) {
            return "";
        }
        CellSlice slice = new CellSlice(leftCells, maxCells);
        long visual = 0;
        TerminalText.GraphemeCursor cursor = TerminalText.graphemeCursor(text);
        while (visual < slice.right && cursor.advance()) {
            int advance = cursor.isTab() ? TAB_WIDTH - (int) (visual % TAB_WIDTH) : Math.max(1, cursor.width());
            slice.append(text, cursor.start(), cursor.end(), cursor.isTab(), visual, advance);
            visual += advance;
        }
        return slice.text.toString();
    }

    /**
     * Clips with an existing compact index and its tab width. Only the visible range is visited;
     * fields can reuse their metrics while panning instead of segmenting the hidden prefix again.
     */
    public static String clipIndexed(TerminalText.Metrics metrics, int leftCells, int maxCells) {
        Objects.requireNonNull(metrics, "metrics");
        String source = metrics.text();
        if (source.isEmpty() || maxCells <= 0) return "";
        CellSlice slice = new CellSlice(leftCells, maxCells);
        int offset = metrics.offsetForVisualColumn(Math.max(0, leftCells), TerminalText.Bias.FLOOR);
        long visual = metrics.visualColumnAt(offset);
        while (offset < source.length() && visual < slice.right) {
            int end = metrics.nextBoundary(offset);
            int next = metrics.visualColumnAt(end);
            boolean tab = end == offset + 1 && source.charAt(offset) == '\t';
            slice.append(source, offset, end, tab, visual, (int) (next - visual));
            offset = end;
            visual = next;
        }
        return slice.text.toString();
    }

    private static final class CellSlice {
        private final long left;
        private final long right;
        private final StringBuilder text = new StringBuilder();

        private CellSlice(int leftCells, int maxCells) {
            left = Math.max(0, leftCells);
            right = left + maxCells;
        }

        private void append(String source, int start, int end, boolean tab, long visual, int advance) {
            long next = visual + advance;
            if (next <= left || visual >= right) return;
            if (tab || visual < left) {
                int visible = (int) (Math.min(next, right) - Math.max(left, visual));
                text.append(" ".repeat(visible));
            } else if (next <= right) {
                text.append(TerminalText.sanitizeGrapheme(source, start, end));
            }
        }
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
        return " ".repeat(cells - total) + clip(safe, 0, total);
    }

    /** Shortens the middle, retaining complete graphemes and a preferred number of trailing cells. */
    public static String ellipsizeMiddle(String text, int cells, int preferredTailCells) {
        return ellipsizeMiddle(text, cells, preferredTailCells, 0);
    }

    /**
     * As above, with tab stops measured from the supplied initial column.
     * Returned controls and tabs are display-ready, independent of their eventual placement.
     * Only the two visible ends are retained while scanning arbitrarily long labels.
     */
    public static String ellipsizeMiddle(String text, int cells, int preferredTailCells, int initialColumn) {
        return fitMiddle(text, cells, preferredTailCells, initialColumn).text();
    }

    /** Display-ready text and whether every original grapheme fits in the supplied cell budget. */
    public record FittedText(String text, boolean complete) {
        public FittedText {
            Objects.requireNonNull(text, "text");
        }
    }

    /** Fits once and reports completeness using exactly the same sanitized graphemes as the output. */
    public static FittedText fitMiddle(String text, int cells, int preferredTailCells, int initialColumn) {
        if (preferredTailCells < 0 || initialColumn < 0) {
            throw new IllegalArgumentException("Text fitting bounds must be non-negative");
        }
        if (text == null || text.isEmpty()) return new FittedText("", true);
        if (cells <= 0) return new FittedText("", false);
        ArrayList<DisplayPart> head = new ArrayList<>();
        ArrayDeque<DisplayPart> tail = new ArrayDeque<>();
        int headWidth = 0;
        int tailWidth = 0;
        long visual = initialColumn;
        boolean headComplete = true;
        Matcher matcher = null;
        for (int start = 0; start < text.length();) {
            int end;
            char ch = text.charAt(start);
            if (ch >= 0x20 && ch <= 0x7e
                && (start + 1 == text.length() || text.charAt(start + 1) <= 0x7f)) {
                end = start + 1;
            } else {
                if (matcher == null) matcher = GRAPHEME.matcher(text);
                matcher.region(start, text.length());
                end = matcher.lookingAt() ? matcher.end() : start + 1;
            }
            boolean tab = ch == '\t' && end == start + 1;
            String visible = tab ? " ".repeat(TAB_WIDTH - (int) (visual % TAB_WIDTH))
                : TerminalText.sanitizeGrapheme(text, start, end);
            int width = tab ? visible.length() : TerminalText.visualWidth(visible, TAB_WIDTH);
            DisplayPart part = new DisplayPart(visible, width);
            if (headComplete && headWidth <= cells - width) {
                head.add(part);
                headWidth += width;
            } else {
                headComplete = false;
            }
            tail.addLast(part);
            tailWidth += width;
            while (tailWidth > cells) tailWidth -= tail.removeFirst().width();
            visual += width;
            start = end;
        }
        if (headComplete) return new FittedText(join(head), true);
        if (cells == 1) return new FittedText("…", false);
        int tailBudget = Math.max(cells / 2, Math.min(preferredTailCells, cells - 1));
        while (tailWidth > tailBudget) tailWidth -= tail.removeFirst().width();
        int headBudget = cells - 1 - tailWidth;
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (DisplayPart part : head) {
            if (used > headBudget - part.width()) break;
            result.append(part.text());
            used += part.width();
        }
        result.append('…');
        for (DisplayPart part : tail) result.append(part.text());
        return new FittedText(result.toString(), false);
    }

    private static String join(Iterable<DisplayPart> parts) {
        StringBuilder text = new StringBuilder();
        for (DisplayPart part : parts) text.append(part.text());
        return text.toString();
    }

    private record DisplayPart(String text, int width) {}
}
