package dev.tuink.core;

import java.util.ArrayList;
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

    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int VARIATION_SELECTOR_16 = 0xFE0F;
    private static final int COMBINING_KEYCAP = 0x20E3;
    private static final int DOTTED_CIRCLE = 0x25CC;
    private static final int REPLACEMENT_CHARACTER = 0xFFFD;
    private static final Pattern GRAPHEME_PATTERN = Pattern.compile("\\X");

    private DisplayText() {
    }

    public static List<Grapheme> graphemes(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Grapheme> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = nextGraphemeBoundary(text, start);
            String cluster = text.substring(start, end);
            boolean tab = "\t".equals(cluster);
            boolean whitespace = isWhitespaceCluster(cluster);
            out.add(new Grapheme(start, end, cluster, tab ? 0 : graphemeWidth(cluster), tab, whitespace));
            start = end;
        }
        return out;
    }

    public static int previousBoundary(String text, int offset) {
        String value = text == null ? "" : text;
        int safe = Math.max(0, Math.min(offset, value.length()));
        if (safe <= 0) {
            return 0;
        }
        int previous = 0;
        int cursor = 0;
        while (cursor < value.length() && cursor < safe) {
            previous = cursor;
            int next = nextGraphemeBoundary(value, cursor);
            if (next >= safe) {
                return previous;
            }
            cursor = next;
        }
        return previous;
    }

    public static int nextBoundary(String text, int offset) {
        String value = text == null ? "" : text;
        int safe = Math.max(0, Math.min(offset, value.length()));
        if (safe >= value.length()) {
            return value.length();
        }
        int cursor = 0;
        while (cursor < value.length()) {
            int next = nextGraphemeBoundary(value, cursor);
            if (safe < next) {
                return next;
            }
            cursor = next;
        }
        return value.length();
    }

    public static int floorBoundary(String text, int offset) {
        String value = text == null ? "" : text;
        int safe = Math.max(0, Math.min(offset, value.length()));
        int cursor = 0;
        while (cursor < value.length()) {
            int next = nextGraphemeBoundary(value, cursor);
            if (safe < next) {
                return cursor;
            }
            if (safe == next) {
                return next;
            }
            cursor = next;
        }
        return value.length();
    }

    public static boolean isBoundary(String text, int offset) {
        String value = text == null ? "" : text;
        int safe = Math.max(0, Math.min(offset, value.length()));
        if (safe == 0 || safe == value.length()) {
            return true;
        }
        int cursor = 0;
        while (cursor < value.length()) {
            cursor = nextGraphemeBoundary(value, cursor);
            if (cursor == safe) {
                return true;
            }
            if (cursor > safe) {
                return false;
            }
        }
        return false;
    }

    public static int ceilBoundary(String text, int offset) {
        String value = text == null ? "" : text;
        int safe = Math.max(0, Math.min(offset, value.length()));
        if (isBoundary(value, safe)) {
            return safe;
        }
        return nextBoundary(value, safe);
    }

    public static int visualColumnAt(String text, int offset, int tabWidth) {
        String value = text == null ? "" : text;
        int limit = floorBoundary(value, offset);
        int visual = 0;
        int safeTabWidth = Math.max(1, tabWidth);
        for (Grapheme grapheme : graphemes(value)) {
            if (grapheme.start() >= limit) {
                break;
            }
            visual += advance(grapheme, visual, safeTabWidth);
        }
        return visual;
    }

    public static int visualWidth(String text, int tabWidth) {
        String value = text == null ? "" : text;
        return visualColumnAt(value, value.length(), tabWidth);
    }

    public static int offsetForVisualColumn(String text, int targetVisualColumn, int tabWidth, Bias bias) {
        String value = text == null ? "" : text;
        int target = Math.max(0, targetVisualColumn);
        int safeTabWidth = Math.max(1, tabWidth);
        int visual = 0;
        for (Grapheme grapheme : graphemes(value)) {
            int nextVisual = visual + advance(grapheme, visual, safeTabWidth);
            if (target < nextVisual) {
                return switch (bias == null ? Bias.NEAREST : bias) {
                    case FLOOR -> grapheme.start();
                    case CEIL -> grapheme.end();
                    case NEAREST -> target - visual <= nextVisual - target
                        ? grapheme.start()
                        : grapheme.end();
                };
            }
            if (target == nextVisual) {
                return grapheme.end();
            }
            visual = nextVisual;
        }
        return value.length();
    }

    public static int graphemeWidth(String grapheme) {
        if (grapheme == null || grapheme.isEmpty()) {
            return 0;
        }
        if (containsUnsafeControl(grapheme) || containsUnpairedSurrogate(grapheme)) {
            return 1;
        }
        int maximum = 0;
        int regionalIndicators = 0;
        boolean emojiPresentation = false;
        boolean visibleBase = false;
        for (int i = 0; i < grapheme.length();) {
            int codePoint = grapheme.codePointAt(i);
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

    /** Returns a safe single display grapheme for storage in a terminal cell. */
    public static String sanitizeGrapheme(String grapheme) {
        if (grapheme == null || grapheme.isEmpty()) {
            return " ";
        }
        if (containsUnpairedSurrogate(grapheme)) {
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
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            if (codePoint <= 0x1F || (codePoint >= 0x7F && codePoint <= 0x9F)) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }

    private static int advance(Grapheme grapheme, int visual, int tabWidth) {
        if (grapheme.tab()) {
            return tabWidth - (visual % tabWidth);
        }
        return Math.max(1, grapheme.width());
    }

    private static int nextGraphemeBoundary(String text, int start) {
        Matcher matcher = GRAPHEME_PATTERN.matcher(text);
        matcher.region(Math.max(0, start), text.length());
        return matcher.lookingAt() ? matcher.end() : Math.min(text.length(), start + 1);
    }

    private static boolean containsUnpairedSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
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

    private static boolean isWhitespaceCluster(String cluster) {
        for (int i = 0; i < cluster.length();) {
            int codePoint = cluster.codePointAt(i);
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
