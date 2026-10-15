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
    private final int graphemeCount;
    private final int[] boundaries;
    private final SparseBoundaries sparse;

    private GraphemeText(String text) {
        this.text = text == null ? "" : text;
        this.simpleAscii = isSimpleAscii(this.text);
        if (simpleAscii) {
            graphemeCount = this.text.length();
            boundaries = null;
            sparse = null;
            return;
        }

        // Count before allocating: a long line can contain mostly one-unit
        // graphemes, or a few very long clusters. Neither needs a UTF-16-sized
        // scratch array followed by another retained copy.
        Counts counts = scan(this.text, null, null);
        graphemeCount = counts.graphemes();
        if (counts.exceptions() == 0) {
            boundaries = null;
            sparse = null;
        } else if (2L * counts.exceptions() < (long) graphemeCount + 1) {
            boundaries = null;
            sparse = new SparseBoundaries(counts.exceptions());
            scan(this.text, null, sparse);
        } else {
            boundaries = new int[graphemeCount + 1];
            sparse = null;
            scan(this.text, boundaries, null);
        }
    }

    private record Counts(int graphemes, int exceptions) {}

    /** An exception records the end of a grapheme occupying more than one UTF-16 unit. */
    private static final class SparseBoundaries {
        private final int[] indices;
        private final int[] offsets;

        private SparseBoundaries(int size) {
            indices = new int[size];
            offsets = new int[size];
        }

        private int boundary(int index) {
            int found = Arrays.binarySearch(indices, index);
            if (found >= 0) {
                return offsets[found];
            }
            int previous = -found - 2;
            return previous < 0 ? index : offsets[previous] + index - indices[previous];
        }

        private int floorIndex(int offset) {
            int found = Arrays.binarySearch(offsets, offset);
            if (found >= 0) {
                return indices[found];
            }
            int next = -found - 1;
            int previous = next - 1;
            int index = previous < 0 ? offset : indices[previous] + offset - offsets[previous];
            // Stop an arithmetic run at the start of the next exceptional
            // cluster, rather than creating boundaries inside that cluster.
            return next == indices.length ? index : Math.min(index, indices[next] - 1);
        }
    }

    private static Counts scan(String text, int[] boundaries, SparseBoundaries sparse) {
        Matcher matcher = null;
        int count = 0;
        int exceptions = 0;
        int start = 0;
        while (start < text.length()) {
            int end;
            if (isSimpleAsciiChar(text.charAt(start))
                && (start + 1 == text.length() || isSimpleAsciiChar(text.charAt(start + 1)))) {
                end = start + 1;
            } else {
                if (matcher == null) {
                    matcher = GRAPHEME_PATTERN.matcher(text);
                }
                matcher.region(start, text.length());
                if (!matcher.lookingAt() || matcher.end() <= start) {
                    throw new IllegalStateException("Unicode grapheme matcher did not advance");
                }
                end = matcher.end();
            }
            count++;
            if (boundaries != null) {
                boundaries[count] = end;
            }
            if (end != start + 1) {
                if (sparse != null) {
                    sparse.indices[exceptions] = count;
                    sparse.offsets[exceptions] = end;
                }
                exceptions++;
            }
            start = end;
        }
        return new Counts(count, exceptions);
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

    /** One boundary lookup without allocating or retaining a complete line index. */
    public static int ceilBoundary(String text, int offset) {
        String value = text == null ? "" : text;
        int safe = Math.max(0, Math.min(offset, value.length()));
        if (safe == 0 || safe == value.length()
            || isSimpleAsciiChar(value.charAt(safe - 1)) && isSimpleAsciiChar(value.charAt(safe))) {
            return safe;
        }
        Matcher matcher = null;
        int start = 0;
        while (start < safe) {
            if (isSimpleAsciiChar(value.charAt(start))
                && (start + 1 == value.length() || isSimpleAsciiChar(value.charAt(start + 1)))) {
                start++;
            } else {
                if (matcher == null) matcher = GRAPHEME_PATTERN.matcher(value);
                matcher.region(start, value.length());
                if (!matcher.lookingAt() || matcher.end() <= start) {
                    throw new IllegalStateException("Unicode grapheme matcher did not advance");
                }
                start = matcher.end();
            }
        }
        return start;
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
            : isWhitespaceCluster(text, boundary(graphemeIndex), boundary(graphemeIndex + 1));
    }

    public int previousBoundary(int offset) {
        int safe = clampOffset(offset);
        int floor = floorIndex(safe);
        return boundary(Math.max(0, boundary(floor) == safe ? floor - 1 : floor));
    }

    public int nextBoundary(int offset) {
        int safe = clampOffset(offset);
        if (safe == text.length()) {
            return safe;
        }
        return boundary(Math.min(graphemeCount, floorIndex(safe) + 1));
    }

    public int floorBoundary(int offset) {
        return boundary(floorIndex(clampOffset(offset)));
    }

    public int ceilBoundary(int offset) {
        int safe = clampOffset(offset);
        int floor = floorIndex(safe);
        return boundary(boundary(floor) == safe ? floor : floor + 1);
    }

    /** Returns false when {@code offset} is outside {@code [0, text.length()]}. */
    public boolean isBoundary(int offset) {
        if (offset < 0 || offset > text.length()) {
            return false;
        }
        return boundary(floorIndex(offset)) == offset;
    }

    /** Approximate primitive-array slots retained by this layout. */
    int storageWeight() {
        return boundaries != null ? boundaries.length : sparse != null ? 2 * sparse.indices.length : 1;
    }

    private int boundary(int boundaryIndex) {
        if (boundaryIndex < 0 || boundaryIndex > graphemeCount()) {
            throw new IndexOutOfBoundsException("boundary index: " + boundaryIndex);
        }
        return boundaries != null ? boundaries[boundaryIndex]
            : sparse != null ? sparse.boundary(boundaryIndex) : boundaryIndex;
    }

    private int floorIndex(int offset) {
        if (sparse != null) {
            return sparse.floorIndex(offset);
        }
        if (boundaries == null) {
            return offset;
        }
        int found = Arrays.binarySearch(boundaries, offset);
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

    private static boolean isSimpleAscii(String text) {
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (!isSimpleAsciiChar(ch)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSimpleAsciiChar(char character) {
        return character <= 0x7F && character != '\r' && character != '\n';
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
