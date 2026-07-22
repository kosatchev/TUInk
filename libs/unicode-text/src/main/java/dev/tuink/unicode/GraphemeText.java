package dev.tuink.unicode;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable extended-grapheme boundaries for one UTF-16 string.
 *
 * <p>This type deliberately contains no terminal-cell width, tab-column or
 * sanitization policy.</p>
 */
public final class GraphemeText {
    private static final Pattern GRAPHEME_PATTERN = Pattern.compile("\\X");

    private final String text;
    private final boolean simpleAscii;
    private final int[] boundaries;
    private final boolean[] whitespace;

    private GraphemeText(String text) {
        this.text = text == null ? "" : text;
        this.simpleAscii = isSimpleAscii(this.text);
        if (simpleAscii) {
            boundaries = null;
            whitespace = null;
            return;
        }

        int[] boundaryScratch = new int[this.text.length() + 1];
        boolean[] whitespaceScratch = new boolean[this.text.length()];
        Matcher matcher = GRAPHEME_PATTERN.matcher(this.text);
        int count = 0;
        int start = 0;
        boundaryScratch[0] = 0;
        while (start < this.text.length()) {
            matcher.region(start, this.text.length());
            if (!matcher.lookingAt()) {
                throw new IllegalStateException("Unicode grapheme matcher did not advance");
            }
            int end = matcher.end();
            if (end <= start) {
                throw new IllegalStateException("Unicode grapheme matcher returned an empty cluster");
            }
            whitespaceScratch[count] = isWhitespaceCluster(this.text, start, end);
            count++;
            boundaryScratch[count] = end;
            start = end;
        }
        boundaries = Arrays.copyOf(boundaryScratch, count + 1);
        whitespace = Arrays.copyOf(whitespaceScratch, count);
    }

    public static GraphemeText of(String text) {
        return new GraphemeText(text);
    }

    public static int previousBoundary(String text, int offset) {
        return of(text).previousBoundary(offset);
    }

    public static int nextBoundary(String text, int offset) {
        return of(text).nextBoundary(offset);
    }

    public static int floorBoundary(String text, int offset) {
        return of(text).floorBoundary(offset);
    }

    public static int ceilBoundary(String text, int offset) {
        return of(text).ceilBoundary(offset);
    }

    /** Returns false when {@code offset} is outside the supplied text. */
    public static boolean isBoundary(String text, int offset) {
        return of(text).isBoundary(offset);
    }

    public String text() {
        return text;
    }

    boolean simpleAscii() {
        return simpleAscii;
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

    /** Returns false when {@code offset} is outside {@code [0, text.length()]}. */
    public boolean isBoundary(int offset) {
        if (offset < 0 || offset > text.length()) {
            return false;
        }
        return simpleAscii || Arrays.binarySearch(boundaries, offset) >= 0;
    }

    /** Approximate primitive-array slots retained by this layout. */
    int storageWeight() {
        return simpleAscii ? 1 : boundaries.length + whitespace.length;
    }

    private int boundary(int boundaryIndex) {
        if (boundaryIndex < 0 || boundaryIndex > graphemeCount()) {
            throw new IndexOutOfBoundsException("boundary index: " + boundaryIndex);
        }
        return simpleAscii ? boundaryIndex : boundaries[boundaryIndex];
    }

    private int clampOffset(int offset) {
        return Math.max(0, Math.min(offset, text.length()));
    }

    private void checkGraphemeIndex(int index) {
        if (index < 0 || index >= graphemeCount()) {
            throw new IndexOutOfBoundsException("grapheme index: " + index);
        }
    }

    private static boolean isSimpleAscii(String text) {
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch > 0x7F || ch == '\r' || ch == '\n') {
                return false;
            }
        }
        return true;
    }

    private static boolean isWhitespaceCluster(String text, int start, int end) {
        for (int index = start; index < end;) {
            int codePoint = text.codePointAt(index);
            if (!Character.isWhitespace(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }
}
