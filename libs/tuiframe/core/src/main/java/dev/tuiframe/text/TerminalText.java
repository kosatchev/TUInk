package dev.tuiframe.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Grapheme-aware terminal cell metrics shared by terminal frontends. */
public final class TerminalText {
    /** Maximum UTF-16 payload emitted for one physical cell, including visible orphan marks. */
    public static final int MAX_DISPLAY_GRAPHEME_LENGTH = 256;

    public enum Bias {
        FLOOR,
        NEAREST,
        CEIL
    }

    public record Grapheme(int start, int end, String text, int width, boolean tab, boolean whitespace) {
        public Grapheme {
            if (start < 0 || end <= start || text == null || text.isEmpty()) {
                throw new IllegalArgumentException("Invalid grapheme");
            }
            width = Math.max(0, Math.min(2, width));
        }
    }

    /**
     * Streaming segmentation over the original string. Each successful {@link #advance()} exposes
     * the next cluster's UTF-16 range without allocating a substring or a Grapheme object.
     * Accessors describe that cluster until the next advance.
     */
    public static final class GraphemeCursor {
        private final String text;
        private Matcher matcher;
        private int start;
        private int end;
        private int width;
        private boolean tab;

        private GraphemeCursor(String text) {
            this.text = text;
        }

        public boolean advance() {
            if (end >= text.length()) return false;
            start = end;
            char ch = text.charAt(start);
            if (isSimpleAsciiChar(ch)
                && (start + 1 == text.length() || isSimpleAsciiChar(text.charAt(start + 1)))) {
                end = start + 1;
            } else {
                if (matcher == null) matcher = GRAPHEME_PATTERN.matcher(text);
                matcher.region(start, text.length());
                end = matcher.lookingAt() ? matcher.end() : start + 1;
            }
            tab = end == start + 1 && ch == '\t';
            width = tab ? 0 : end == start + 1 && isSimpleAsciiChar(ch) ? 1
                : graphemeWidth(text, start, end);
            return true;
        }

        public int start() { return start; }
        public int end() { return end; }
        public int width() { return width; }
        public boolean isTab() { return tab; }
        public boolean isWhitespace() { return isWhitespaceCluster(text, start, end); }
    }

    public static GraphemeCursor graphemeCursor(String text) {
        return new GraphemeCursor(text == null ? "" : text);
    }

    /**
     * Immutable grapheme boundaries and terminal-cell prefix columns for one
     * string and tab width.
     */
    public static final class Metrics {
        private final String text;
        private final int tabWidth;
        private final boolean simpleAscii;
        private final int graphemeCount;
        private final int[] boundaries;
        private final int[] visualColumns;
        private final byte[] widths;
        private final boolean[] whitespace;
        private final SparseIndex sparse;

        private Metrics(String text, int tabWidth, boolean simpleAscii,
            int[] boundaries, int[] visualColumns, byte[] widths, boolean[] whitespace, SparseIndex sparse) {
            this.text = text;
            this.tabWidth = tabWidth;
            this.simpleAscii = simpleAscii;
            this.boundaries = boundaries;
            this.visualColumns = visualColumns;
            this.widths = widths;
            this.whitespace = whitespace;
            this.sparse = sparse;
            this.graphemeCount = simpleAscii ? text.length()
                : sparse == null ? boundaries.length - 1 : sparse.floorIndex(text.length(), sparse.offsets);
        }

        public String text() {
            return text;
        }

        public int tabWidth() {
            return tabWidth;
        }

        public int graphemeCount() {
            return graphemeCount;
        }

        public int start(int graphemeIndex) {
            checkGraphemeIndex(graphemeIndex);
            return boundary(graphemeIndex);
        }

        public int end(int graphemeIndex) {
            checkGraphemeIndex(graphemeIndex);
            return boundary(graphemeIndex + 1);
        }

        public int width(int graphemeIndex) {
            checkGraphemeIndex(graphemeIndex);
            if (simpleAscii) {
                return text.charAt(graphemeIndex) == '\t' ? 0 : 1;
            }
            return sparse == null ? widths[graphemeIndex] : sparse.width(graphemeIndex);
        }

        public boolean isTab(int graphemeIndex) {
            checkGraphemeIndex(graphemeIndex);
            int start = boundary(graphemeIndex);
            return boundary(graphemeIndex + 1) == start + 1 && text.charAt(start) == '\t';
        }

        public boolean isWhitespace(int graphemeIndex) {
            checkGraphemeIndex(graphemeIndex);
            if (simpleAscii) {
                return Character.isWhitespace(text.charAt(graphemeIndex));
            }
            return sparse == null ? whitespace[graphemeIndex] : sparse.isWhitespace(graphemeIndex, text);
        }

        public int visualColumnAtBoundary(int boundaryIndex) {
            int safe = Math.max(0, Math.min(boundaryIndex, graphemeCount));
            return sparse != null ? sparse.positionAt(safe, sparse.columns)
                : visualColumns == null ? safe : visualColumns[safe];
        }

        public int previousBoundary(int offset) {
            int safe = clampOffset(offset);
            int floor = floorBoundaryIndex(safe);
            return boundary(Math.max(0, boundary(floor) == safe ? floor - 1 : floor));
        }

        public int nextBoundary(int offset) {
            int safe = clampOffset(offset);
            return boundary(Math.min(graphemeCount, floorBoundaryIndex(safe) + 1));
        }

        public int floorBoundary(int offset) {
            return boundary(floorBoundaryIndex(clampOffset(offset)));
        }

        public int ceilBoundary(int offset) {
            int safe = clampOffset(offset);
            int floor = floorBoundaryIndex(safe);
            return boundary(boundary(floor) == safe ? floor : floor + 1);
        }

        public boolean isBoundary(int offset) {
            int safe = clampOffset(offset);
            return boundary(floorBoundaryIndex(safe)) == safe;
        }

        public int visualColumnAt(int offset) {
            return visualColumnAtBoundary(floorBoundaryIndex(clampOffset(offset)));
        }

        public int visualWidth() {
            return visualColumnAtBoundary(graphemeCount);
        }

        public int storageWeight() {
            if (sparse != null) {
                return (int) Math.max(1, Math.min(Integer.MAX_VALUE, 4L * sparse.indices.length));
            }
            return simpleAscii ? visualColumns == null ? 1 : visualColumns.length
                : boundaries.length + visualColumns.length + widths.length + whitespace.length;
        }

        public int offsetForVisualColumn(int targetVisualColumn, Bias bias) {
            int target = Math.max(0, targetVisualColumn);
            if (target >= visualWidth()) {
                return text.length();
            }
            int left;
            if (sparse != null) {
                left = sparse.floorIndex(target, sparse.columns);
            } else if (visualColumns == null) {
                return target;
            } else {
                int found = Arrays.binarySearch(visualColumns, target);
                left = found >= 0 ? found : -found - 2;
            }
            int leftColumn = visualColumnAtBoundary(left);
            if (leftColumn == target) {
                return boundary(left);
            }
            int right = left + 1;
            return switch (bias == null ? Bias.NEAREST : bias) {
                case FLOOR -> boundary(left);
                case CEIL -> boundary(right);
                case NEAREST -> target - leftColumn <= visualColumnAtBoundary(right) - target
                    ? boundary(left) : boundary(right);
            };
        }

        private int boundary(int index) {
            return simpleAscii ? index : sparse == null ? boundaries[index] : sparse.positionAt(index, sparse.offsets);
        }

        private int floorBoundaryIndex(int safeOffset) {
            if (simpleAscii) {
                return safeOffset;
            }
            if (sparse != null) {
                return sparse.floorIndex(safeOffset, sparse.offsets);
            }
            int found = Arrays.binarySearch(boundaries, safeOffset);
            return found >= 0 ? found : Math.max(0, -found - 2);
        }

        private int clampOffset(int offset) {
            return Math.max(0, Math.min(offset, text.length()));
        }

        private void checkGraphemeIndex(int index) {
            if (index < 0 || index >= graphemeCount) {
                throw new IndexOutOfBoundsException("grapheme index: " + index);
            }
        }
    }

    /**
     * Ordinary one-unit, one-cell graphemes form arithmetic runs. Only the end
     * boundary of a tab, wide/zero-width glyph or multi-unit cluster needs an
     * entry. The three sorted coordinates support searches in either direction.
     */
    private static final class SparseIndex {
        private static final int WHITESPACE = 4;
        private static final SparseIndex EMPTY = new SparseIndex(0);
        private final int[] indices;
        private final int[] offsets;
        private final int[] columns;
        private final byte[] attributes;

        private SparseIndex(int size) {
            indices = new int[size];
            offsets = new int[size];
            columns = new int[size];
            attributes = new byte[size];
        }

        private int positionAt(int index, int[] positions) {
            int found = Arrays.binarySearch(indices, index);
            if (found >= 0) {
                return positions[found];
            }
            int previous = -found - 2;
            return previous < 0 ? index : positions[previous] + index - indices[previous];
        }

        private int floorIndex(int position, int[] positions) {
            int found = Arrays.binarySearch(positions, position);
            if (found >= 0) {
                return indices[found];
            }
            int next = -found - 1;
            int previous = next - 1;
            int index = previous < 0 ? position : indices[previous] + position - positions[previous];
            // Extrapolation through a multi-unit or multi-cell exception stops
            // at its starting boundary, rather than inventing an inner boundary.
            return next == indices.length ? index : Math.min(index, indices[next] - 1);
        }

        private int width(int index) {
            int found = Arrays.binarySearch(indices, index + 1);
            return found < 0 ? 1 : attributes[found] & 3;
        }

        private boolean isWhitespace(int index, String text) {
            int found = Arrays.binarySearch(indices, index + 1);
            return found < 0 ? Character.isWhitespace(text.charAt(positionAt(index, offsets)))
                : (attributes[found] & WHITESPACE) != 0;
        }
    }

    private record MetricCounts(int graphemes, int exceptions) {}

    /** Allocates the smaller representation once, after a streaming count pass. */
    private static final class MetricsBuilder {
        private final String text;
        private final int tabWidth;
        private final boolean ascii;
        private final int[] boundaries;
        private final int[] columns;
        private final byte[] widths;
        private final boolean[] whitespace;
        private final SparseIndex sparse;
        private int exception;

        private MetricsBuilder(String text, int tabWidth, boolean ascii, MetricCounts counts) {
            this.text = text;
            this.tabWidth = tabWidth;
            this.ascii = ascii;
            long denseBytes = ascii ? 4L * (counts.graphemes() + 1) : 10L * counts.graphemes() + 8;
            if (13L * counts.exceptions() < denseBytes) {
                sparse = counts.exceptions() == 0 ? SparseIndex.EMPTY : new SparseIndex(counts.exceptions());
                boundaries = null;
                columns = null;
                widths = null;
                whitespace = null;
            } else {
                sparse = null;
                boundaries = ascii ? null : new int[counts.graphemes() + 1];
                columns = new int[counts.graphemes() + 1];
                widths = ascii ? null : new byte[counts.graphemes()];
                whitespace = ascii ? null : new boolean[counts.graphemes()];
            }
        }

        private void accept(int index, int end, int column, int width, boolean blank, boolean exceptional) {
            if (sparse != null) {
                if (exceptional) {
                    sparse.indices[exception] = index;
                    sparse.offsets[exception] = end;
                    sparse.columns[exception] = column;
                    sparse.attributes[exception++] = (byte) (width | (blank ? SparseIndex.WHITESPACE : 0));
                }
            } else {
                columns[index] = column;
                if (!ascii) {
                    boundaries[index] = end;
                    widths[index - 1] = (byte) width;
                    whitespace[index - 1] = blank;
                }
            }
        }

        private Metrics finish() {
            return new Metrics(text, tabWidth, ascii, boundaries, columns, widths, whitespace, sparse);
        }
    }

    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int VARIATION_SELECTOR_16 = 0xFE0F;
    private static final int COMBINING_KEYCAP = 0x20E3;
    private static final int DOTTED_CIRCLE = 0x25CC;
    private static final int REPLACEMENT_CHARACTER = 0xFFFD;
    private static final Pattern GRAPHEME_PATTERN = Pattern.compile("\\X");

    private TerminalText() {
    }

    public static Metrics metrics(String text, int tabWidth) {
        String value = text == null ? "" : text;
        int safeTabWidth = Math.max(1, tabWidth);
        boolean ascii = isSimpleAscii(value);
        if (ascii && value.indexOf('\t') < 0) {
            return new Metrics(value, safeTabWidth, true, null, null, null, null, null);
        }
        MetricCounts counts = scanMetrics(value, safeTabWidth, null);
        MetricsBuilder builder = new MetricsBuilder(value, safeTabWidth, ascii, counts);
        scanMetrics(value, safeTabWidth, builder);
        return builder.finish();
    }

    /**
     * Estimates additional heap for the index arrays built by {@link #metrics}, without
     * allocating those arrays or copying the source. The caller still owns the source
     * string and must budget any copies separately. Includes space for array headers
     * and the metrics/scanner objects.
     */
    public static long estimatedMetricsBytes(String text, int tabWidth) {
        String value = text == null ? "" : text;
        boolean ascii = isSimpleAscii(value);
        if (ascii && value.indexOf('\t') < 0) return 512;
        MetricCounts counts = scanMetrics(value, Math.max(1, tabWidth), null);
        long denseBytes = ascii ? 4L * (counts.graphemes() + 1L) : 10L * counts.graphemes() + 8;
        return 512 + Math.min(denseBytes, 13L * counts.exceptions());
    }

    private static MetricCounts scanMetrics(String value, int tabWidth, MetricsBuilder builder) {
        int count = 0;
        int exceptions = 0;
        int visual = 0;
        GraphemeCursor cursor = graphemeCursor(value);
        while (cursor.advance()) {
            int width = cursor.width();
            visual += cursor.isTab() ? tabWidth - visual % tabWidth : Math.max(1, width);
            count++;
            boolean exceptional = cursor.end() != cursor.start() + 1 || width != 1;
            if (exceptional) {
                exceptions++;
            }
            if (builder != null) {
                builder.accept(count, cursor.end(), visual, width, cursor.isWhitespace(), exceptional);
            }
        }
        return new MetricCounts(count, exceptions);
    }

    public static List<Grapheme> graphemes(String text) {
        String value = text == null ? "" : text;
        if (value.isEmpty()) {
            return List.of();
        }
        List<Grapheme> out = new ArrayList<>(value.length());
        GraphemeCursor cursor = graphemeCursor(value);
        while (cursor.advance()) {
            out.add(new Grapheme(cursor.start(), cursor.end(), value.substring(cursor.start(), cursor.end()),
                cursor.width(), cursor.isTab(), cursor.isWhitespace()));
        }
        return out;
    }

    /**
     * Returns the smallest grapheme-safe prefix covering a left-aligned cell
     * viewport. The last grapheme may cross the right edge so a clipped
     * painter can preserve partial-tab and atomic-wide-cell semantics without
     * scanning the remainder of a long line.
     */
    public static String prefixForVisualWidth(String text, int maxCells, int tabWidth) {
        String value = text == null ? "" : text;
        if (value.isEmpty() || maxCells <= 0) {
            return "";
        }
        int safeTabWidth = Math.max(1, tabWidth);
        int end = 0;
        long visual = 0L;
        long limit = maxCells;
        GraphemeCursor cursor = graphemeCursor(value);
        while (visual < limit && cursor.advance()) {
            long advance = cursor.isTab()
                ? safeTabWidth - (visual % safeTabWidth)
                : Math.max(1, cursor.width());
            visual += advance;
            end = cursor.end();
        }
        return end == value.length() ? value : value.substring(0, end);
    }

    public static int visualColumnAt(String text, int offset, int tabWidth) {
        return metrics(text, tabWidth).visualColumnAt(offset);
    }

    public static int visualWidth(String text, int tabWidth) {
        return metrics(text, tabWidth).visualWidth();
    }

    public static int offsetForVisualColumn(String text, int targetVisualColumn, int tabWidth, Bias bias) {
        return metrics(text, tabWidth).offsetForVisualColumn(targetVisualColumn, bias);
    }

    public static int graphemeWidth(String grapheme) {
        if (grapheme == null || grapheme.isEmpty()) {
            return 0;
        }
        return graphemeWidth(grapheme, 0, grapheme.length());
    }

    /** Returns whether the complete string is exactly one extended grapheme cluster. */
    public static boolean isSingleGrapheme(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (text.length() == 1) {
            return true;
        }
        int firstCodePoint = text.codePointAt(0);
        if (Character.charCount(firstCodePoint) == text.length()) {
            return true;
        }
        if (isSimpleAscii(text)) {
            return false;
        }
        Matcher matcher = GRAPHEME_PATTERN.matcher(text);
        return matcher.lookingAt() && matcher.end() == text.length();
    }

    /** Returns a safe single display grapheme for storage in a terminal cell. */
    public static String sanitizeGrapheme(String grapheme) {
        if (grapheme == null || grapheme.isEmpty()) {
            return " ";
        }
        if (grapheme.length() > MAX_DISPLAY_GRAPHEME_LENGTH) {
            return oversizedGrapheme(grapheme, 0, grapheme.length());
        }
        if (containsUnpairedSurrogate(grapheme, 0, grapheme.length())) {
            return Character.toString(REPLACEMENT_CHARACTER);
        }
        if (containsUnsafeControl(grapheme)) {
            if (grapheme.codePointCount(0, grapheme.length()) == 1) {
                return visibleControl(grapheme.codePointAt(0));
            }
            return Character.toString(REPLACEMENT_CHARACTER);
        }
        if (graphemeWidth(grapheme) == 0) {
            int first = grapheme.codePointAt(0);
            if (first == ZERO_WIDTH_JOINER
                || first == COMBINING_KEYCAP
                || isEmojiModifier(first)
                || isVariationSelector(first)) {
                return Character.toString(REPLACEMENT_CHARACTER);
            }
            int type = Character.getType(first);
            if (type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK) {
                if (grapheme.length() == MAX_DISPLAY_GRAPHEME_LENGTH) {
                    return Character.toString(REPLACEMENT_CHARACTER);
                }
                return Character.toString(DOTTED_CIRCLE) + grapheme;
            }
            return Character.toString(REPLACEMENT_CHARACTER);
        }
        return grapheme;
    }

    /** Sanitizes an original grapheme range without copying an oversized payload. */
    public static String sanitizeGrapheme(String text, int start, int end) {
        Objects.checkFromToIndex(start, end, text.length());
        return end - start > MAX_DISPLAY_GRAPHEME_LENGTH
            ? oversizedGrapheme(text, start, end)
            : sanitizeGrapheme(text.substring(start, end));
    }

    private static String oversizedGrapheme(String text, int start, int end) {
        // A fullwidth question mark preserves a wide cluster's two-cell geometry.
        return graphemeWidth(text, start, end) == 2 ? "\uFF1F" : Character.toString(REPLACEMENT_CHARACTER);
    }

    public static boolean containsUnsafeControl(String text) {
        if (text == null) {
            return false;
        }
        return containsUnsafeControl(text, 0, text.length());
    }

    private static int graphemeWidth(String text, int start, int end) {
        if (start >= end) {
            return 0;
        }
        if (containsUnsafeControl(text, start, end) || containsUnpairedSurrogate(text, start, end)) {
            return 1;
        }
        int maximum = 0;
        int regionalIndicators = 0;
        boolean emojiPresentation = false;
        int previousCodePoint = -1;
        int previousBase = -1;
        boolean emojiJoinPending = false;
        boolean visibleBase = false;
        for (int i = start; i < end;) {
            int codePoint = text.codePointAt(i);
            if (isRegionalIndicator(codePoint)) {
                regionalIndicators++;
            }
            boolean formatting = codePoint == VARIATION_SELECTOR_16
                || codePoint == COMBINING_KEYCAP
                || isEmojiModifier(codePoint)
                || codePoint == ZERO_WIDTH_JOINER;
            // Formatting marks also occur in ordinary text. They request a
            // two-cell emoji only when attached to a compatible emoji base.
            if (codePoint == VARIATION_SELECTOR_16 && Character.isEmoji(previousCodePoint)
                || codePoint == COMBINING_KEYCAP && isKeycapBase(previousBase)
                || isEmojiModifier(codePoint) && Character.isEmojiModifierBase(previousBase)
                || emojiJoinPending && Character.isExtendedPictographic(codePoint)) {
                emojiPresentation = true;
            }
            emojiJoinPending = codePoint == ZERO_WIDTH_JOINER
                && Character.isExtendedPictographic(previousBase);
            int type = Character.getType(codePoint);
            boolean combining = type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
            if (!formatting && !combining) {
                visibleBase = true;
                maximum = Math.max(maximum, TerminalCellWidth.visibleBaseWidth(codePoint));
                previousBase = codePoint;
            }
            previousCodePoint = codePoint;
            i += Character.charCount(codePoint);
        }
        if (!visibleBase || maximum == 0) {
            return 0;
        }
        if (regionalIndicators >= 2 || emojiPresentation) {
            return 2;
        }
        return Math.min(2, maximum);
    }

    private static boolean isSimpleAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!isSimpleAsciiChar(ch)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSimpleAsciiChar(char ch) {
        return ch <= 0x7F && ch != '\r' && ch != '\n';
    }

    private static boolean containsUnsafeControl(String text, int start, int end) {
        for (int i = start; i < end;) {
            int codePoint = text.codePointAt(i);
            if (codePoint <= 0x1F || (codePoint >= 0x7F && codePoint <= 0x9F)) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean containsUnpairedSurrogate(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= end || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEmojiModifier(int codePoint) {
        return codePoint >= 0x1F3FB && codePoint <= 0x1F3FF;
    }

    private static boolean isKeycapBase(int codePoint) {
        return codePoint == '#' || codePoint == '*' || codePoint >= '0' && codePoint <= '9';
    }

    private static boolean isRegionalIndicator(int codePoint) {
        return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
    }

    private static boolean isVariationSelector(int codePoint) {
        return codePoint >= 0xFE00 && codePoint <= 0xFE0F
            || codePoint >= 0xE0100 && codePoint <= 0xE01EF;
    }

    private static boolean isWhitespaceCluster(String text, int start, int end) {
        for (int i = start; i < end;) {
            int codePoint = text.codePointAt(i);
            if (!Character.isWhitespace(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    private static String visibleControl(int codePoint) {
        if (codePoint >= 0 && codePoint <= 0x1F) {
            return Character.toString(0x2400 + codePoint);
        }
        if (codePoint == 0x7F) {
            return Character.toString(0x2421);
        }
        return Character.toString(REPLACEMENT_CHARACTER);
    }
}
