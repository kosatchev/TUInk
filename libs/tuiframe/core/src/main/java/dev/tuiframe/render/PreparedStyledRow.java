package dev.tuiframe.render;

import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalText;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Immutable grapheme geometry and stable styles. Selection, search and caret are separate overlays. */
public final class PreparedStyledRow {
    /**
     * Complete producer-owned identity. Values never retain a document, session, model or host.
     * Producers must advance content/style revisions whenever the corresponding row inputs change.
     */
    public record Key(long producerId, long sourceId, long contentRevision, long styleRevision,
                      int row, int sliceStart, int sliceLength, int tabWidth, int tabPhase) {
        public Key {
            if (row < 0 || sliceStart < 0 || sliceLength < 0 || tabWidth < 1 || tabPhase < 0 || tabPhase >= tabWidth)
                throw new IllegalArgumentException("Invalid prepared row identity or tab geometry");
        }
    }

    /** Half-open UTF-16 range. Later overlapping ranges take precedence. */
    public record Span(int start, int end, CellStyle style) {
        public Span {
            if (start < 0 || end < start) throw new IllegalArgumentException("Invalid style range");
            Objects.requireNonNull(style, "style");
        }
    }

    private static final String[] ASCII_DISPLAY = asciiDisplay();
    private final TerminalText.Geometry metrics;
    private final String[] display;
    private final int[] advances;
    private final int[] visualOffsets;
    private final boolean[] tabs;
    private final boolean[] exactSpaces;
    private final CellStyle[] baseStyles;
    private final int leadingEnd;
    private final int trailingStart;
    private final int visualWidth;

    private PreparedStyledRow(CharSequence text, int tabWidth, int tabPhase, CellStyle defaultStyle, List<Span> spans) {
        this(prepareGeometry(text, tabWidth), tabPhase, defaultStyle, spans);
    }

    static TerminalText.Metrics prepareGeometry(CharSequence text, int tabWidth) {
        return TerminalText.metrics(ownSlice(text), tabWidth);
    }

    private PreparedStyledRow(TerminalText.Geometry geometry, int tabPhase, CellStyle defaultStyle, List<Span> spans) {
        metrics = geometry;
        CharSequence value = geometry.sequence();
        int tabWidth = geometry.tabWidth();
        int count = metrics.graphemeCount();
        display = new String[count];
        advances = new int[count];
        visualOffsets = new int[count];
        tabs = new boolean[count];
        exactSpaces = new boolean[count];
        baseStyles = new CellStyle[count];
        Arrays.fill(baseStyles, defaultStyle);
        for (Span span : spans) {
            Objects.requireNonNull(span, "span");
            int first = firstOverlapping(metrics, span.start());
            int after = firstStartingAtOrAfter(metrics, span.end());
            if (span.start() != span.end() && first < after) Arrays.fill(baseStyles, first, after, span.style());
        }
        int leading = 0;
        while (leading < count && metrics.isWhitespace(leading)) leading++;
        leadingEnd = leading;
        int trailing = count;
        while (trailing > 0 && metrics.isWhitespace(trailing - 1)) trailing--;
        trailingStart = trailing;
        int absoluteVisual = tabPhase;
        for (int index = 0; index < count; index++) {
            int start = metrics.start(index), end = metrics.end(index);
            boolean tab = metrics.isTab(index);
            int advance = tab ? tabWidth - absoluteVisual % tabWidth : Math.max(1, metrics.width(index));
            display[index] = tab ? " " : display(value, start, end);
            advances[index] = advance;
            visualOffsets[index] = absoluteVisual - tabPhase;
            tabs[index] = tab;
            exactSpaces[index] = end == start + 1 && value.charAt(start) == ' ';
            absoluteVisual = Math.addExact(absoluteVisual, advance);
        }
        visualWidth = absoluteVisual - tabPhase;
    }

    /**
     * Conservative preparation/retention estimate. It needs only lengths and allocates no geometry.
     * Includes dense Unicode metrics, prepared arrays, sanitized display strings, row/cache metadata
     * and an owned copy of a non-String visible slice, including its transient copy buffer.
     * Preparation never retains a larger backing rope, mutable source or application owner.
     */
    public static long estimatedBytes(int utf16Length, int spanCount) {
        if (utf16Length < 0 || spanCount < 0) throw new IllegalArgumentException("Negative row dimensions");
        return 1024L + 128L * utf16Length + 64L * spanCount;
    }

    /** Called only after admission. Text and span inputs must remain immutable during preparation. */
    static PreparedStyledRow prepare(CharSequence text, int tabWidth, int tabPhase,
                                     CellStyle defaultStyle, List<Span> spans) {
        return new PreparedStyledRow(text, tabWidth, tabPhase, defaultStyle, spans);
    }

    /** The caller pins the shared geometry's owner through the prepared row's complete lifetime. */
    static PreparedStyledRow prepare(TerminalText.Geometry geometry, int tabPhase,
                                     CellStyle defaultStyle, List<Span> spans) {
        return new PreparedStyledRow(geometry, tabPhase, defaultStyle, spans);
    }

    /** UTF-16/grapheme index for the local slice; use row advances/offsets for phase-aware tab geometry. */
    public TerminalText.Geometry metrics() { return metrics; }
    public int graphemeCount() { return display.length; }
    public String display(int index) { return display[index]; }
    public int advance(int index) { return advances[index]; }
    public int visualOffset(int index) { return visualOffsets[index]; }
    public boolean isTab(int index) { return tabs[index]; }
    public boolean isExactSpace(int index) { return exactSpaces[index]; }
    public CellStyle baseStyle(int index) { return baseStyles[index]; }
    public int leadingWhitespaceEnd() { return leadingEnd; }
    public int trailingWhitespaceStart() { return trailingStart; }
    public int visualWidth() { return visualWidth; }

    private static String ownSlice(CharSequence text) {
        if (text instanceof String string) return string;
        char[] copy = new char[text.length()];
        for (int index = 0; index < copy.length; index++) copy[index] = text.charAt(index);
        return new String(copy);
    }

    private static int firstOverlapping(TerminalText.Geometry metrics, int offset) {
        int low = 0, high = metrics.graphemeCount();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (metrics.end(middle) <= offset) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static int firstStartingAtOrAfter(TerminalText.Geometry metrics, int offset) {
        int low = 0, high = metrics.graphemeCount();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (metrics.start(middle) < offset) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static String display(CharSequence text, int start, int end) {
        if (end == start + 1 && text.charAt(start) < ASCII_DISPLAY.length) return ASCII_DISPLAY[text.charAt(start)];
        return TerminalText.sanitizeGrapheme(text, start, end);
    }

    private static String[] asciiDisplay() {
        String[] display = new String[128];
        for (int value = 0; value < display.length; value++)
            display[value] = TerminalText.sanitizeGrapheme(Character.toString(value));
        return display;
    }
}
