package dev.tuink.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jline.utils.WCWidth;

/**
 * Shared mapping between UTF-16 document offsets, grapheme clusters and
 * terminal cell columns.
 */
public final class DisplayText {
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
     * Immutable grapheme boundaries and terminal-cell prefix columns for one
     * string and tab width.
     */
    public static final class Metrics {
        private final String text;
        private final int tabWidth;
        private final boolean simpleAscii;
        private final int[] boundaries;
        private final int[] visualColumns;
        private final byte[] widths;
        private final boolean[] whitespace;

        private Metrics(
            String text,
            int tabWidth,
            boolean simpleAscii,
            int[] boundaries,
            int[] visualColumns,
            byte[] widths,
            boolean[] whitespace
        ) {
            this.text = text;
            this.tabWidth = tabWidth;
            this.simpleAscii = simpleAscii;
            this.boundaries = boundaries;
            this.visualColumns = visualColumns;
            this.widths = widths;
            this.whitespace = whitespace;
        }

        public String text() {
            return text;
        }

        public int tabWidth() {
            return tabWidth;
        }

        public int graphemeCount() {
            return simpleAscii ? text.length() : boundaries.length - 1;
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
            return widths[graphemeIndex];
        }

        public boolean isTab(int graphemeIndex) {
            checkGraphemeIndex(graphemeIndex);
            int start = boundary(graphemeIndex);
            int end = boundary(graphemeIndex + 1);
            return end == start + 1 && text.charAt(start) == '\t';
        }

        public boolean isWhitespace(int graphemeIndex) {
            checkGraphemeIndex(graphemeIndex);
            return simpleAscii
                ? Character.isWhitespace(text.charAt(graphemeIndex))
                : whitespace[graphemeIndex];
        }

        public int visualColumnAtBoundary(int boundaryIndex) {
            int safe = Math.max(0, Math.min(boundaryIndex, graphemeCount()));
            if (visualColumns == null) {
                return safe;
            }
            return visualColumns[safe];
        }

        public int previousBoundary(int offset) {
            int safe = clampOffset(offset);
            if (safe <= 0) {
                return 0;
            }
            if (simpleAscii) {
                return safe - 1;
            }
            int found = Arrays.binarySearch(boundaries, safe);
            int index = found >= 0 ? found - 1 : -found - 2;
            return boundaries[Math.max(0, index)];
        }

        public int nextBoundary(int offset) {
            int safe = clampOffset(offset);
            if (safe >= text.length()) {
                return text.length();
            }
            if (simpleAscii) {
                return safe + 1;
            }
            int found = Arrays.binarySearch(boundaries, safe);
            int index = found >= 0 ? found + 1 : -found - 1;
            return boundaries[Math.min(boundaries.length - 1, index)];
        }

        public int floorBoundary(int offset) {
            int safe = clampOffset(offset);
            if (simpleAscii) {
                return safe;
            }
            int found = Arrays.binarySearch(boundaries, safe);
            return found >= 0 ? boundaries[found] : boundaries[Math.max(0, -found - 2)];
        }

        public int ceilBoundary(int offset) {
            int safe = clampOffset(offset);
            if (simpleAscii) {
                return safe;
            }
            int found = Arrays.binarySearch(boundaries, safe);
            return found >= 0
                ? boundaries[found]
                : boundaries[Math.min(boundaries.length - 1, -found - 1)];
        }

        public boolean isBoundary(int offset) {
            int safe = clampOffset(offset);
            return simpleAscii || Arrays.binarySearch(boundaries, safe) >= 0;
        }

        public int visualColumnAt(int offset) {
            int safe = clampOffset(offset);
            if (simpleAscii && visualColumns == null) {
                return safe;
            }
            int boundaryIndex = floorBoundaryIndex(safe);
            return visualColumns == null ? boundaryIndex : visualColumns[boundaryIndex];
        }

        public int visualWidth() {
            return visualColumnAtBoundary(graphemeCount());
        }

        public int storageWeight() {
            if (simpleAscii) {
                return visualColumns == null ? 1 : visualColumns.length;
            }
            return boundaries.length + visualColumns.length + widths.length + whitespace.length;
        }

        public int offsetForVisualColumn(int targetVisualColumn, Bias bias) {
            int target = Math.max(0, targetVisualColumn);
            int count = graphemeCount();
            int total = visualColumnAtBoundary(count);
            if (target >= total) {
                return text.length();
            }
            if (visualColumns == null) {
                return Math.min(target, text.length());
            }

            int found = Arrays.binarySearch(visualColumns, target);
            if (found >= 0) {
                return boundary(found);
            }
            int right = -found - 1;
            int left = Math.max(0, right - 1);
            return switch (bias == null ? Bias.NEAREST : bias) {
                case FLOOR -> boundary(left);
                case CEIL -> boundary(right);
                case NEAREST -> target - visualColumns[left] <= visualColumns[right] - target
                    ? boundary(left)
                    : boundary(right);
            };
        }

        private int boundary(int index) {
            return simpleAscii ? index : boundaries[index];
        }

        private int floorBoundaryIndex(int safeOffset) {
            if (simpleAscii) {
                return safeOffset;
            }
            int found = Arrays.binarySearch(boundaries, safeOffset);
            return found >= 0 ? found : Math.max(0, -found - 2);
        }

        private int clampOffset(int offset) {
            return Math.max(0, Math.min(offset, text.length()));
        }

        private void checkGraphemeIndex(int index) {
            if (index < 0 || index >= graphemeCount()) {
                throw new IndexOutOfBoundsException("grapheme index: " + index);
            }
        }
    }

    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int VARIATION_SELECTOR_16 = 0xFE0F;
    private static final int COMBINING_KEYCAP = 0x20E3;
    private static final int DOTTED_CIRCLE = 0x25CC;
    private static final int REPLACEMENT_CHARACTER = 0xFFFD;
    private static final Pattern GRAPHEME_PATTERN = Pattern.compile("\\X");

    private DisplayText() {
    }

    public static Metrics metrics(String text, int tabWidth) {
        String value = text == null ? "" : text;
        int safeTabWidth = Math.max(1, tabWidth);
        if (isSimpleAscii(value)) {
            int[] visualColumns = null;
            if (value.indexOf('\t') >= 0) {
                visualColumns = new int[value.length() + 1];
                int visual = 0;
                for (int i = 0; i < value.length(); i++) {
                    visual += value.charAt(i) == '\t'
                        ? safeTabWidth - (visual % safeTabWidth)
                        : 1;
                    visualColumns[i + 1] = visual;
                }
            }
            return new Metrics(value, safeTabWidth, true, null, visualColumns, null, null);
        }

        int maximumClusters = Math.max(1, value.length());
        int[] boundaries = new int[maximumClusters + 1];
        int[] visualColumns = new int[maximumClusters + 1];
        byte[] widths = new byte[maximumClusters];
        boolean[] whitespace = new boolean[maximumClusters];
        Matcher matcher = GRAPHEME_PATTERN.matcher(value);
        int count = 0;
        int visual = 0;
        int start = 0;
        boundaries[0] = 0;
        while (start < value.length()) {
            matcher.region(start, value.length());
            int end = matcher.lookingAt() ? matcher.end() : Math.min(value.length(), start + 1);
            boolean tab = end == start + 1 && value.charAt(start) == '\t';
            int width = tab ? 0 : graphemeWidth(value, start, end);
            int advance = tab
                ? safeTabWidth - (visual % safeTabWidth)
                : Math.max(1, width);
            widths[count] = (byte) width;
            whitespace[count] = isWhitespaceCluster(value, start, end);
            visual += advance;
            count++;
            boundaries[count] = end;
            visualColumns[count] = visual;
            start = end;
        }
        return new Metrics(
            value,
            safeTabWidth,
            false,
            Arrays.copyOf(boundaries, count + 1),
            Arrays.copyOf(visualColumns, count + 1),
            Arrays.copyOf(widths, count),
            Arrays.copyOf(whitespace, count)
        );
    }

    public static List<Grapheme> graphemes(String text) {
        String value = text == null ? "" : text;
        if (value.isEmpty()) {
            return List.of();
        }
        List<Grapheme> out = new ArrayList<>(value.length());
        if (isSimpleAscii(value)) {
            for (int index = 0; index < value.length(); index++) {
                char ch = value.charAt(index);
                boolean tab = ch == '\t';
                out.add(
                    new Grapheme(
                        index,
                        index + 1,
                        String.valueOf(ch),
                        tab ? 0 : 1,
                        tab,
                        Character.isWhitespace(ch)
                    )
                );
            }
            return out;
        }

        Matcher matcher = GRAPHEME_PATTERN.matcher(value);
        int start = 0;
        while (start < value.length()) {
            matcher.region(start, value.length());
            int end = matcher.lookingAt() ? matcher.end() : Math.min(value.length(), start + 1);
            boolean tab = end == start + 1 && value.charAt(start) == '\t';
            out.add(
                new Grapheme(
                    start,
                    end,
                    value.substring(start, end),
                    tab ? 0 : graphemeWidth(value, start, end),
                    tab,
                    isWhitespaceCluster(value, start, end)
                )
            );
            start = end;
        }
        return out;
    }

    public static int previousBoundary(String text, int offset) {
        return metrics(text, 1).previousBoundary(offset);
    }

    public static int nextBoundary(String text, int offset) {
        return metrics(text, 1).nextBoundary(offset);
    }

    public static int floorBoundary(String text, int offset) {
        return metrics(text, 1).floorBoundary(offset);
    }

    public static boolean isBoundary(String text, int offset) {
        return metrics(text, 1).isBoundary(offset);
    }

    public static int ceilBoundary(String text, int offset) {
        return metrics(text, 1).ceilBoundary(offset);
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

    /** Returns a safe single display grapheme for storage in a terminal cell. */
    public static String sanitizeGrapheme(String grapheme) {
        if (grapheme == null || grapheme.isEmpty()) {
            return " ";
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
                return Character.toString(DOTTED_CIRCLE) + grapheme;
            }
            return Character.toString(REPLACEMENT_CHARACTER);
        }
        return grapheme;
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
            if (formatting) {
                emojiPresentation = true;
            }
            int type = Character.getType(codePoint);
            boolean combining = type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
            if (!formatting && !combining) {
                visibleBase = true;
                maximum = Math.max(maximum, Math.max(0, WCWidth.wcwidth(codePoint)));
            }
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
            if (ch > 0x7F || ch == '\r' || ch == '\n') {
                return false;
            }
        }
        return true;
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
