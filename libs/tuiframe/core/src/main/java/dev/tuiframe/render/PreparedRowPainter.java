package dev.tuiframe.render;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalText;
import java.util.Objects;

/** Shared clipped glyph/tab painting for prepared rows and constant-space admission fallback. */
public final class PreparedRowPainter {
    /** Resolves transient overlays for a source UTF-16 grapheme range. The prepared base is immutable. */
    @FunctionalInterface
    public interface Overlay {
        CellStyle resolve(int start, int end, CellStyle base);
    }

    private static final String[] ASCII = asciiDisplay();

    private PreparedRowPainter() {}

    /**
     * Paints only intersecting prepared glyphs. The clipping origin is local to the supplied canvas.
     * Whitespace policy and marker palette are supplied by the consumer; null disables markers.
     * Returns the full prepared row width for an optional consumer-owned end-of-line decoration.
     */
    public static int paint(Canvas canvas, PreparedStyledRow prepared, int row, int startColumn,
        int columns, int hiddenCells, Overlay overlay, boolean markLeading, boolean markTrailing, CellStyle marker) {
        Objects.requireNonNull(canvas); Objects.requireNonNull(prepared);
        int width = Math.max(0, columns), hidden = Math.max(0, hiddenCells);
        if (width == 0) return prepared.visualWidth();
        int mark = canvas.save();
        try {
            canvas.clip(startColumn, row, width, 1);
            int first = firstVisible(prepared, hidden);
            long right = (long) hidden + width;
            for (int index = first; index < prepared.graphemeCount() && prepared.visualOffset(index) < right; index++) {
                CellStyle style = prepared.baseStyle(index);
                if (overlay != null) style = overlay.resolve(prepared.metrics().start(index), prepared.metrics().end(index), style);
                boolean whitespace = marker != null && (markLeading && index < prepared.leadingWhitespaceEnd()
                    || markTrailing && index >= prepared.trailingWhitespaceStart())
                    && (prepared.isTab(index) || prepared.isExactSpace(index));
                paintGlyph(canvas, row, startColumn, width, prepared.visualOffset(index) - hidden,
                    prepared.advance(index), prepared.isTab(index), prepared.isExactSpace(index), prepared.display(index),
                    style, whitespace, marker);
            }
            return prepared.visualWidth();
        } finally { canvas.restore(mark); }
    }

    /**
     * Paints a borrowed admitted geometry view when prepared-array admission is refused.
     * No new segmentation, copied text or source-sized arrays are constructed.
     */
    public static int paintGeometry(Canvas canvas, TerminalText.Geometry geometry, CellStyle defaultStyle,
        int row, int startColumn, int columns, int hiddenCells, Overlay overlay) {
        Objects.requireNonNull(canvas); Objects.requireNonNull(geometry); Objects.requireNonNull(defaultStyle);
        int width = Math.max(0, columns), hidden = Math.max(0, hiddenCells);
        if (width == 0) return geometry.visualWidth();
        int mark = canvas.save();
        try {
            canvas.clip(startColumn, row, width, 1);
            int low = 0, high = geometry.graphemeCount();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (geometry.visualColumnAtBoundary(middle + 1) <= hidden) low = middle + 1;
                else high = middle;
            }
            CharSequence text = geometry.sequence();
            long right = (long) hidden + width;
            for (int index = low; index < geometry.graphemeCount(); index++) {
                int visual = geometry.visualColumnAtBoundary(index);
                if (visual >= right) break;
                int start = geometry.start(index), end = geometry.end(index);
                int advance = geometry.visualColumnAtBoundary(index + 1) - visual;
                CellStyle style = overlay == null ? defaultStyle : overlay.resolve(start, end, defaultStyle);
                paintGlyph(canvas, row, startColumn, width, visual - hidden, advance, geometry.isTab(index),
                    false, geometry.isTab(index) ? " " : display(text, start, end), style, false, null);
            }
            return geometry.visualWidth();
        } finally { canvas.restore(mark); }
    }

    /**
     * Correct constant-space path when row admission is refused. It does not construct metrics,
     * copy the source or allocate arrays proportional to its length. Only the visible prefix is
     * segmented; whitespace suffix checks use code points without constructing an index.
     * Returns -1 when the unseen suffix means the row end was not visited.
     */
    public static int paintStreaming(Canvas canvas, CharSequence text, int tabWidth, int tabPhase,
        CellStyle defaultStyle, int row, int startColumn, int columns, int hiddenCells, Overlay overlay,
        boolean markLeading, boolean markTrailing, CellStyle marker) {
        Objects.requireNonNull(canvas); Objects.requireNonNull(text); Objects.requireNonNull(defaultStyle);
        if (tabWidth < 1 || tabPhase < 0 || tabPhase >= tabWidth) throw new IllegalArgumentException("Invalid tab geometry");
        int width = Math.max(0, columns), hidden = Math.max(0, hiddenCells);
        if (width == 0) return text.length() == 0 ? 0 : -1;
        int leadingEnd = marker != null && markLeading ? leadingWhitespaceEnd(text) : 0;
        int trailingStart = marker != null && markTrailing ? trailingWhitespaceStart(text) : text.length();
        int mark = canvas.save();
        try {
            canvas.clip(startColumn, row, width, 1);
            var cursor = TerminalText.graphemeCursor(text);
            int visual = 0, lastOffset = 0;
            long right = (long) hidden + width;
            while (visual < right && cursor.advance()) {
                int advance = cursor.isTab() ? tabWidth - (tabPhase + visual) % tabWidth : Math.max(1, cursor.width());
                lastOffset = cursor.end();
                if ((long) visual + advance > hidden) {
                    CellStyle style = overlay == null ? defaultStyle : overlay.resolve(cursor.start(), cursor.end(), defaultStyle);
                    String shown = cursor.isTab() ? " " : display(text, cursor.start(), cursor.end());
                    boolean space = !cursor.isTab() && cursor.end() == cursor.start() + 1 && shown.equals(" ");
                    boolean whitespace = marker != null && (markLeading && cursor.end() <= leadingEnd
                        || markTrailing && cursor.start() >= trailingStart) && (cursor.isTab() || space);
                    paintGlyph(canvas, row, startColumn, width, visual - hidden, advance, cursor.isTab(), space,
                        shown, style, whitespace, marker);
                }
                visual += advance;
            }
            return lastOffset == text.length() ? visual : -1;
        } finally { canvas.restore(mark); }
    }

    /** Sanitizes a single original grapheme while sharing precomputed ASCII display strings. */
    public static String display(CharSequence text, int start, int end) {
        if (end == start + 1) {
            char value = text.charAt(start);
            if (value < ASCII.length) return ASCII[value];
        }
        return TerminalText.sanitizeGrapheme(text, start, end);
    }

    private static void paintGlyph(Canvas canvas, int row, int startColumn, int columns, int offset,
        int advance, boolean tab, boolean exactSpace, String display, CellStyle style, boolean marked, CellStyle marker) {
        if (marked) style = style.equals(CellStyle.DEFAULT) ? marker
            : new CellStyle(marker.fg(), style.bg(), style.bold(), style.underline());
        if (tab) {
            int from = Math.max(0, -offset), to = Math.min(advance, columns - offset);
            for (int cell = from; cell < to; cell++) canvas.put(startColumn + offset + cell, row,
                marked && cell == 0 && offset >= 0 ? "»" : " ", style);
        } else if (offset >= 0 && (long) offset + advance <= columns) {
            canvas.put(startColumn + offset, row, marked && exactSpace ? "·" : display, style);
        }
    }

    private static int firstVisible(PreparedStyledRow row, int hidden) {
        int low = 0, high = row.graphemeCount();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if ((long) row.visualOffset(middle) + row.advance(middle) <= hidden) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static int leadingWhitespaceEnd(CharSequence text) {
        int offset = 0;
        while (offset < text.length()) {
            int cp = Character.codePointAt(text, offset);
            if (!Character.isWhitespace(cp)) break;
            offset += Character.charCount(cp);
        }
        return offset;
    }

    private static int trailingWhitespaceStart(CharSequence text) {
        int offset = text.length();
        while (offset > 0) {
            int cp = Character.codePointBefore(text, offset);
            if (!Character.isWhitespace(cp)) break;
            offset -= Character.charCount(cp);
        }
        return offset;
    }

    private static String[] asciiDisplay() {
        String[] result = new String[128];
        for (int index = 0; index < result.length; index++) result[index] = TerminalText.sanitizeGrapheme(Character.toString(index));
        return result;
    }
}
