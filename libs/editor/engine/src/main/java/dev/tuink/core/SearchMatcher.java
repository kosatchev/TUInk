package dev.tuink.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiled single-line search whose match ranges always address the original
 * source string in UTF-16 offsets.
 */
public final class SearchMatcher {
    private final SearchOptions.MatchMode matchMode;
    private final Pattern pattern;
    private final WildcardPattern wildcard;

    private SearchMatcher(String query, SearchOptions options) {
        String safeQuery = query == null ? "" : query;
        SearchOptions safeOptions = options == null ? SearchOptions.DEFAULT : options;
        this.matchMode = safeOptions.matchMode() == null
            ? SearchOptions.MatchMode.SUBSTRING
            : safeOptions.matchMode();
        this.wildcard = !safeQuery.isEmpty() && matchMode == SearchOptions.MatchMode.WILDCARD
            ? new WildcardPattern(safeQuery, safeOptions.caseInsensitive())
            : null;
        this.pattern = safeQuery.isEmpty() || wildcard != null
            ? null
            : compilePattern(safeQuery, safeOptions.caseInsensitive());
    }

    public static SearchMatcher compile(String query, SearchOptions options) {
        return new SearchMatcher(query, options);
    }

    /** Returns non-overlapping half-open UTF-16 ranges in {@code source}. */
    public List<Match> findAll(String source) {
        List<Match> matches = new ArrayList<>();
        scanMatches(source, (start, end) -> {
            matches.add(new Match(start, end));
            return true;
        });
        return List.copyOf(matches);
    }

    /**
     * Returns only results intersecting {@code [startUtf16, endUtf16)}.
     * Ranges retain their full source offsets rather than being clipped to the
     * interval, including a result that starts before its left edge. Earlier
     * results still determine non-overlap, as in {@link #findAll(String)}.
     */
    public List<Match> findIntersecting(String source, int startUtf16, int endUtf16) {
        if (endUtf16 <= startUtf16) {
            return List.of();
        }
        List<Match> matches = new ArrayList<>();
        scanMatches(source, (start, end) -> {
            if (start >= endUtf16) {
                return false;
            }
            if (end > startUtf16) {
                matches.add(new Match(start, end));
            }
            return true;
        });
        return List.copyOf(matches);
    }

    /**
     * Returns the first result whose start is at least {@code fromUtf16}, or null.
     * Results retain the same non-overlapping ranges as {@link #findAll(String)},
     * even when the requested offset lies inside an earlier result.
     */
    public Match findFirstAtOrAfter(String source, int fromUtf16) {
        int[] found = {-1, -1};
        scanMatches(source, (start, end) -> {
            if (start < fromUtf16) {
                return true;
            }
            found[0] = start;
            found[1] = end;
            return false;
        });
        return found[0] < 0 ? null : new Match(found[0], found[1]);
    }

    /** Returns the last result starting before {@code beforeUtf16}, or null. */
    public Match findLastBefore(String source, int beforeUtf16) {
        int[] found = {-1, -1};
        scanMatches(source, (start, end) -> {
            if (start >= beforeUtf16) {
                return false;
            }
            found[0] = start;
            found[1] = end;
            return true;
        });
        return found[0] < 0 ? null : new Match(found[0], found[1]);
    }

    /** Counts results without allocating a range object for each occurrence. */
    public int countMatches(String source) {
        int[] count = {0};
        scanMatches(source, (start, end) -> {
            count[0]++;
            return true;
        });
        return count[0];
    }

    /**
     * Returns whether the supplied source range is exactly one of this
     * matcher's results. The range is expressed in original-source UTF-16
     * offsets.
     */
    public boolean matchesRange(String source, int startUtf16, int endUtf16) {
        if (source == null
            || startUtf16 < 0
            || endUtf16 < startUtf16
            || endUtf16 > source.length()
            || (pattern == null && wildcard == null)) {
            return false;
        }
        Match match = findFirstAtOrAfter(source, startUtf16);
        return match != null && match.startUtf16() == startUtf16 && match.endUtf16() == endUtf16;
    }

    private void scanMatches(String source, MatchVisitor visitor) {
        if (source == null || source.isEmpty() || (pattern == null && wildcard == null)) {
            return;
        }
        if (wildcard != null) {
            wildcard.scanMatches(source, visitor);
            return;
        }
        Matcher matcher = pattern.matcher(source);
        int from = 0;
        while (matcher.find(from)) {
            int start = matcher.start();
            int end = matcher.end();
            if (start == end) {
                break;
            }
            if (!acceptsBoundaries(source, start, end)) {
                // A rejected candidate must not hide a later valid occurrence
                // overlapping it. Only accepted matches determine non-overlap.
                from = start + Character.charCount(source.codePointAt(start));
                continue;
            }
            if (!visitor.visit(start, end)) {
                return;
            }
            from = end;
        }
    }

    @FunctionalInterface
    private interface MatchVisitor {
        /** Returns false when no more results are needed. */
        boolean visit(int startUtf16, int endUtf16);
    }

    private boolean acceptsBoundaries(String source, int start, int end) {
        return matchMode != SearchOptions.MatchMode.WHOLE_WORD || isWholeWord(source, start, end);
    }

    private static Pattern compilePattern(
        String query,
        boolean caseInsensitive
    ) {
        int flags = Pattern.DOTALL;
        if (caseInsensitive) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        return Pattern.compile(Pattern.quote(query), flags);
    }

    /** Glob matching over code points, without regex backtracking. */
    private static final class WildcardPattern {
        private final List<WildcardSegment> segments;
        private final boolean caseInsensitive;
        private final boolean hasStar;
        private final boolean leadingStar;
        private final boolean trailingStar;
        private final int minimumLength;

        private WildcardPattern(String query, boolean caseInsensitive) {
            int[] tokens = query.codePoints().toArray();
            this.caseInsensitive = caseInsensitive;
            this.leadingStar = tokens[0] == '*';
            this.trailingStar = tokens[tokens.length - 1] == '*';
            List<WildcardSegment> compiledSegments = new ArrayList<>();
            boolean foundStar = false;
            int required = 0;
            int segmentStart = 0;
            for (int index = 0; index <= tokens.length; index++) {
                if (index == tokens.length || tokens[index] == '*') {
                    if (index > segmentStart) {
                        compiledSegments.add(new WildcardSegment(Arrays.copyOfRange(tokens, segmentStart, index)));
                    }
                    foundStar |= index < tokens.length;
                    // Empty segments are omitted: adjacent stars are one star.
                    segmentStart = index + 1;
                } else {
                    required++;
                    if (caseInsensitive) {
                        tokens[index] = foldCase(tokens[index]);
                    }
                }
            }
            this.segments = List.copyOf(compiledSegments);
            this.hasStar = foundStar;
            this.minimumLength = required;
        }

        private void scanMatches(String source, MatchVisitor visitor) {
            if (minimumLength > source.codePointCount(0, source.length())) {
                return;
            }
            int[] codePoints = source.codePoints().toArray();
            int length = codePoints.length;
            int[] utf16Offsets = new int[length + 1];
            for (int index = 0; index < length; index++) {
                utf16Offsets[index + 1] = utf16Offsets[index] + Character.charCount(codePoints[index]);
                if (caseInsensitive) {
                    codePoints[index] = foldCase(codePoints[index]);
                }
            }

            if (!hasStar) {
                WildcardSegment segment = segments.getFirst();
                int from = 0;
                while (from <= length - segment.length) {
                    int start = segment.find(codePoints, from, false);
                    if (start < 0) {
                        return;
                    }
                    int end = start + segment.length;
                    if (!visitor.visit(utf16Offsets[start], utf16Offsets[end])) {
                        return;
                    }
                    from = end;
                }
                return;
            }

            // Earliest intermediate segments leave the most room for everything
            // after them. The final segment can then use its latest occurrence.
            // Any pattern containing '*' has at most one leftmost-longest,
            // non-overlapping result: a later result would need a later final
            // segment, which this result would already have consumed.
            int start = 0;
            int end = 0;
            for (int index = 0; index < segments.size(); index++) {
                WildcardSegment segment = segments.get(index);
                boolean last = index == segments.size() - 1;
                // A single unanchored segment also determines the leftmost
                // start, so only a preceding star permits choosing it last.
                boolean findLast = last && !trailingStar && (index > 0 || leadingStar);
                int found = segment.find(codePoints, end, findLast);
                if (found < 0) {
                    return;
                }
                if (index == 0 && !leadingStar) {
                    start = found;
                }
                end = found + segment.length;
            }
            if (trailingStar) {
                end = length;
            }
            if (end > start) {
                visitor.visit(utf16Offsets[start], utf16Offsets[end]);
            }
        }

        /**
         * A fixed-width segment between stars. Literal segments use KMP, so
         * separated stars and long repetitive literals scan in O(n + m).
         * Internal '?' uses Shift-And in O(n * ceil(m / 64)) time and O(m)
         * space. Sparse literal masks avoid quadratic space for Unicode text.
         * Leading/trailing '?' need no automaton states.
         */
        private static final class WildcardSegment {
            private final int length;
            private final int leadingQuestions;
            private final int trailingQuestions;
            private final int[] literals;
            private final int[] prefix;
            private final long[] questionMasks;
            private final Map<Integer, WordMask[]> literalMasks;

            private WildcardSegment(int[] tokens) {
                length = tokens.length;
                int left = 0;
                while (left < length && tokens[left] == '?') {
                    left++;
                }
                int right = length;
                while (right > left && tokens[right - 1] == '?') {
                    right--;
                }
                leadingQuestions = left;
                trailingQuestions = length - right;
                literals = Arrays.copyOfRange(tokens, left, right);
                boolean hasQuestion = false;
                for (int token : literals) {
                    hasQuestion |= token == '?';
                }
                if (!hasQuestion) {
                    prefix = new int[literals.length];
                    for (int index = 1, matched = 0; index < literals.length; index++) {
                        while (matched > 0 && literals[index] != literals[matched]) {
                            matched = prefix[matched - 1];
                        }
                        if (literals[index] == literals[matched]) {
                            matched++;
                        }
                        prefix[index] = matched;
                    }
                    questionMasks = null;
                    literalMasks = null;
                    return;
                }
                prefix = null;
                questionMasks = new long[(literals.length - 1) / Long.SIZE + 1];
                Map<Integer, List<WordMask>> masks = new HashMap<>();
                for (int index = 0; index < literals.length; index++) {
                    int word = index / Long.SIZE;
                    long bit = 1L << (index % Long.SIZE);
                    if (literals[index] == '?') {
                        questionMasks[word] |= bit;
                        continue;
                    }
                    List<WordMask> entries = masks.computeIfAbsent(literals[index], ignored -> new ArrayList<>());
                    if (!entries.isEmpty() && entries.getLast().word == word) {
                        WordMask previous = entries.removeLast();
                        entries.add(new WordMask(word, previous.bits | bit));
                    } else {
                        entries.add(new WordMask(word, bit));
                    }
                }
                literalMasks = new HashMap<>();
                masks.forEach((codePoint, entries) -> literalMasks.put(codePoint, entries.toArray(WordMask[]::new)));
            }

            private int find(int[] source, int from, boolean last) {
                if (from > source.length - length) {
                    return -1;
                }
                if (literals.length == 0) {
                    return last ? source.length - length : from;
                }
                int limit = source.length - trailingQuestions;
                int found = -1;
                if (prefix != null) {
                    for (int index = from + leadingQuestions, matched = 0; index < limit; index++) {
                        while (matched > 0 && source[index] != literals[matched]) {
                            matched = prefix[matched - 1];
                        }
                        if (source[index] == literals[matched]) {
                            matched++;
                        }
                        if (matched == literals.length) {
                            found = index + 1 - literals.length - leadingQuestions;
                            if (!last) {
                                return found;
                            }
                            matched = prefix[matched - 1];
                        }
                    }
                    return found;
                }
                long[] state = new long[questionMasks.length];
                long finalBit = 1L << ((literals.length - 1) % Long.SIZE);
                for (int index = from + leadingQuestions; index < limit; index++) {
                    WordMask[] masks = literalMasks.get(source[index]);
                    int entry = 0;
                    long carry = 1;
                    for (int word = 0; word < state.length; word++) {
                        long previous = state[word];
                        long mask = questionMasks[word];
                        if (masks != null && entry < masks.length && masks[entry].word == word) {
                            mask |= masks[entry++].bits;
                        }
                        state[word] = ((previous << 1) | carry) & mask;
                        carry = previous >>> (Long.SIZE - 1);
                    }
                    if ((state[state.length - 1] & finalBit) != 0) {
                        found = index + 1 - literals.length - leadingQuestions;
                        if (!last) {
                            return found;
                        }
                    }
                }
                return found;
            }

            private record WordMask(int word, long bits) {}
        }

        /** The simple Unicode case folding used by CASE_INSENSITIVE | UNICODE_CASE. */
        private static int foldCase(int codePoint) {
            return Character.toLowerCase(Character.toUpperCase(codePoint));
        }
    }

    private static boolean isWholeWord(String source, int start, int end) {
        boolean leftBoundary = start == 0 || !isWordCodePoint(source.codePointBefore(start));
        boolean rightBoundary = end == source.length() || !isWordCodePoint(source.codePointAt(end));
        return leftBoundary && rightBoundary;
    }

    private static boolean isWordCodePoint(int codePoint) {
        if (Character.isLetterOrDigit(codePoint) || codePoint == '_') {
            return true;
        }
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
            || type == Character.COMBINING_SPACING_MARK
            || type == Character.ENCLOSING_MARK;
    }

    public record Match(int startUtf16, int endUtf16) {
        public Match {
            if (startUtf16 < 0 || endUtf16 < startUtf16) {
                throw new IllegalArgumentException("Invalid UTF-16 match range");
            }
        }

        public int lengthUtf16() {
            return endUtf16 - startUtf16;
        }
    }
}
