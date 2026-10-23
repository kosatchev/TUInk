package dev.tuink.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Compiled single-line search whose match ranges always address the original
 * source string in UTF-16 offsets.
 */
public final class SearchMatcher {
    private static final SearchLimitException MEMORY_LIMIT = SearchLimitException.memory();
    private final SearchOptions.MatchMode matchMode;
    private final LiteralPattern literal;
    private final WildcardPattern wildcard;

    private SearchMatcher(String query, SearchOptions options, int codePoints) {
        String safeQuery = query == null ? "" : query;
        SearchOptions safeOptions = options == null ? SearchOptions.DEFAULT : options;
        this.matchMode = safeOptions.matchMode() == null
            ? SearchOptions.MatchMode.SUBSTRING
            : safeOptions.matchMode();
        this.wildcard = !safeQuery.isEmpty() && matchMode == SearchOptions.MatchMode.WILDCARD
            ? new WildcardPattern(safeQuery, safeOptions.caseInsensitive(), codePoints)
            : null;
        this.literal = safeQuery.isEmpty() || wildcard != null
            ? null
            : new LiteralPattern(safeQuery, safeOptions.caseInsensitive(), codePoints);
    }

    public static SearchMatcher compile(String query, SearchOptions options) {
        try {
            String safeQuery = query == null ? "" : query;
            SearchOptions safeOptions = options == null ? SearchOptions.DEFAULT : options;
            int codePoints = safeQuery.codePointCount(0, safeQuery.length());
            if (codePoints > 0) {
                SearchMemoryBudget.requireCompilation(compilationBytes(safeQuery, safeOptions, codePoints));
            }
            return new SearchMatcher(safeQuery, safeOptions, codePoints);
        } catch (OutOfMemoryError exhausted) {
            throw MEMORY_LIMIT;
        }
    }

    /**
     * Preflight without query-sized storage. Besides the retained matcher, allow
     * for its construction copies and the largest query-sized matching scratch.
     * A wildcard word contains at most 64 distinct literal masks, so a bounded
     * set suffices to count an upper bound on masks and map keys across segments.
     */
    private static long compilationBytes(String query, SearchOptions options, int codePoints) {
        if (options.matchMode() != SearchOptions.MatchMode.WILDCARD) {
            // Tokens, prefix table and original-source offset ring.
            return SearchMemoryBudget.FIXED_OVERHEAD + 12L * codePoints;
        }
        SearchMemoryBudget.requireScratch(SearchMemoryBudget.FIXED_OVERHEAD);
        int[] wordLiterals = new int[Long.SIZE];
        long bytes = SearchMemoryBudget.FIXED_OVERHEAD + 4L * codePoints;
        int segmentLength = 0;
        int firstLiteral = -1;
        int lastLiteral = -1;
        int word = -1;
        int distinct = 0;
        long maskEntries = 0;
        boolean internalQuestion = false;
        for (int offset = 0; offset <= query.length();) {
            int token = offset < query.length() ? query.codePointAt(offset) : '*';
            if (token == '*') {
                if (segmentLength > 0) {
                    int coreLength = firstLiteral < 0 ? 0 : lastLiteral - firstLiteral + 1;
                    // Segment object, both segment reference arrays, array
                    // headers and temporary token slice copied by its constructor.
                    bytes += 256L + 4L * segmentLength + 4L * coreLength;
                    if (internalQuestion) {
                        long words = (coreLength + 63L) / Long.SIZE;
                        // Question masks, scan state, WordMask objects/references,
                        // boxed keys, both maps, construction lists and prefilter set.
                        bytes += 16L * words + 320L * maskEntries;
                    } else {
                        bytes += 4L * coreLength;
                    }
                }
                segmentLength = 0;
                firstLiteral = -1;
                lastLiteral = -1;
                word = -1;
                distinct = 0;
                maskEntries = 0;
                internalQuestion = false;
            } else {
                if (token != '?') {
                    if (firstLiteral < 0) firstLiteral = segmentLength;
                    if (lastLiteral >= 0 && segmentLength > lastLiteral + 1) internalQuestion = true;
                    lastLiteral = segmentLength;
                    int nextWord = (segmentLength - firstLiteral) / Long.SIZE;
                    if (word != nextWord) {
                        word = nextWord;
                        distinct = 0;
                    }
                    if (options.caseInsensitive()) token = WildcardPattern.foldCase(token);
                    int index = 0;
                    while (index < distinct && wordLiterals[index] != token) index++;
                    if (index == distinct) {
                        wordLiterals[distinct++] = token;
                        maskEntries++;
                    }
                }
                segmentLength++;
            }
            if (offset == query.length()) break;
            offset += Character.charCount(query.codePointAt(offset));
        }
        return bytes;
    }

    private static int[] tokens(String query, int codePoints) {
        int[] result = new int[codePoints];
        for (int offset = 0, index = 0; offset < query.length(); index++) {
            int codePoint = query.codePointAt(offset);
            result[index] = codePoint;
            offset += Character.charCount(codePoint);
        }
        return result;
    }

    /** Returns non-overlapping half-open UTF-16 ranges in {@code source}. */
    public List<Match> findAll(String source) {
        try {
            List<Match> matches = new ArrayList<>();
            scanMatches(source, (start, end) -> {
                matches.add(new Match(start, end));
                return true;
            });
            return List.copyOf(matches);
        } catch (OutOfMemoryError exhausted) {
            throw MEMORY_LIMIT;
        }
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
        try {
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
        } catch (OutOfMemoryError exhausted) {
            throw MEMORY_LIMIT;
        }
    }

    /**
     * Returns the first result whose start is at least {@code fromUtf16}, or null.
     * Results retain the same non-overlapping ranges as {@link #findAll(String)},
     * even when the requested offset lies inside an earlier result.
     */
    public Match findFirstAtOrAfter(String source, int fromUtf16) {
        try {
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
        } catch (OutOfMemoryError exhausted) {
            throw MEMORY_LIMIT;
        }
    }

    /** Returns the last result starting before {@code beforeUtf16}, or null. */
    public Match findLastBefore(String source, int beforeUtf16) {
        try {
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
        } catch (OutOfMemoryError exhausted) {
            throw MEMORY_LIMIT;
        }
    }

    /** Counts results without allocating a range object for each occurrence. */
    public int countMatches(String source) {
        try {
            int[] count = {0};
            scanMatches(source, (start, end) -> {
                count[0]++;
                return true;
            });
            return count[0];
        } catch (OutOfMemoryError exhausted) {
            throw MEMORY_LIMIT;
        }
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
            || (literal == null && wildcard == null)) {
            return false;
        }
        Match match = findFirstAtOrAfter(source, startUtf16);
        return match != null && match.startUtf16() == startUtf16 && match.endUtf16() == endUtf16;
    }

    /** Visits the same ranges as findAll without retaining a Match object for each result. */
    void scanMatches(String source, MatchVisitor visitor) {
        if (source == null || source.isEmpty() || (literal == null && wildcard == null)) {
            return;
        }
        try {
            if (wildcard != null) {
                wildcard.scanMatches(source, visitor);
                return;
            }
            literal.scanMatches(source, visitor, matchMode == SearchOptions.MatchMode.WHOLE_WORD);
        } catch (OutOfMemoryError exhausted) {
            throw MEMORY_LIMIT;
        }
    }

    @FunctionalInterface
    interface MatchVisitor {
        /** Returns false when no more results are needed. */
        boolean visit(int startUtf16, int endUtf16);
    }

    /** Literal KMP matching with linear preprocessing and original UTF-16 ranges. */
    private static final class LiteralPattern {
        private final int[] tokens;
        private final int[] prefix;
        private final boolean caseInsensitive;

        private LiteralPattern(String query, boolean caseInsensitive, int codePoints) {
            this.caseInsensitive = caseInsensitive;
            tokens = tokens(query, codePoints);
            if (caseInsensitive) {
                for (int index = 0; index < tokens.length; index++) tokens[index] = fold(tokens[index]);
            }
            prefix = new int[tokens.length];
            for (int index = 1, matched = 0; index < tokens.length; index++) {
                while (matched > 0 && tokens[index] != tokens[matched]) {
                    matched = prefix[matched - 1];
                }
                if (tokens[index] == tokens[matched]) {
                    matched++;
                }
                prefix[index] = matched;
            }
        }

        private void scanMatches(String source, MatchVisitor visitor, boolean wholeWord) {
            if (tokens.length > source.length()) {
                return;
            }
            // Retain only the last query-length code-point offsets. Case folding
            // never changes the positions reported in the original source.
            SearchMemoryBudget.requireScratch(SearchMemoryBudget.FIXED_OVERHEAD + 4L * tokens.length);
            int[] offsets = new int[tokens.length];
            int matched = 0;
            int codePointIndex = 0;
            for (int offset = 0; offset < source.length(); codePointIndex++) {
                offsets[codePointIndex % tokens.length] = offset;
                int codePoint = source.codePointAt(offset);
                int next = offset + Character.charCount(codePoint);
                int token = fold(codePoint);
                while (matched > 0 && token != tokens[matched]) {
                    matched = prefix[matched - 1];
                }
                if (token == tokens[matched]) {
                    matched++;
                }
                if (matched == tokens.length) {
                    int start = offsets[(codePointIndex + 1 - tokens.length) % tokens.length];
                    if (!wholeWord || isWholeWord(source, start, next)) {
                        if (!visitor.visit(start, next)) {
                            return;
                        }
                        matched = 0;
                    } else {
                        // Only accepted matches determine non-overlap. Preserve
                        // overlapping candidates after a rejected word boundary.
                        matched = prefix[matched - 1];
                    }
                }
                offset = next;
            }
        }

        private int fold(int codePoint) {
            return caseInsensitive ? Character.toLowerCase(Character.toUpperCase(codePoint)) : codePoint;
        }
    }

    /** Glob matching over code points, without regex backtracking. */
    private static final class WildcardPattern {
        private final List<WildcardSegment> segments;
        private final boolean caseInsensitive;
        private final boolean hasStar;
        private final boolean leadingStar;
        private final boolean trailingStar;
        private final int minimumLength;

        private WildcardPattern(String query, boolean caseInsensitive, int codePoints) {
            int[] tokens = tokens(query, codePoints);
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
            // UTF-16 length is a cheap upper bound on the available code points.
            // A source-sized code-point/offset index would defeat bounded searches
            // on long lines; each segment walks the original String instead.
            if (minimumLength > source.length()
                || WildcardSegment.advance(source, 0, minimumLength) < 0) {
                return;
            }

            if (!hasStar) {
                segments.getFirst().scanMatches(source, 0, true, caseInsensitive, visitor);
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
                Match found = segment.find(source, end, findLast, caseInsensitive);
                if (found == null) {
                    return;
                }
                if (index == 0 && !leadingStar) {
                    start = found.startUtf16();
                }
                end = found.endUtf16();
            }
            if (trailingStar) {
                end = source.length();
            }
            if (end > start) {
                visitor.visit(start, end);
            }
        }

        /**
         * A fixed-width segment between stars. Literal segments use KMP, so
         * separated stars and long repetitive literals scan in O(n + m).
         * Internal '?' uses Shift-And in O(n * ceil(m / 64)) time and O(m)
         * space. Sparse literal masks avoid quadratic space for Unicode text.
         * Three UTF-16 cursors retain the full match start, scanned position,
         * and trailing-question lookahead, without indexing the source.
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

            private Match find(String source, int from, boolean last, boolean caseInsensitive) {
                if (literals.length == 0) {
                    int end = advance(source, from, length);
                    if (end < 0) {
                        return null;
                    }
                    return last ? new Match(source.offsetByCodePoints(source.length(), -length), source.length())
                        : new Match(from, end);
                }
                int[] found = {-1, -1};
                scanMatches(source, from, false, caseInsensitive, (start, end) -> {
                    found[0] = start;
                    found[1] = end;
                    return last;
                });
                return found[0] < 0 ? null : new Match(found[0], found[1]);
            }

            private void scanMatches(
                String source, int from, boolean nonOverlapping, boolean caseInsensitive, MatchVisitor visitor
            ) {
                if (literals.length == 0) {
                    // Used for a pattern consisting only of '?'. Each source code
                    // point is visited once, even when the query itself is long.
                    for (int start = from; start < source.length();) {
                        int end = advance(source, start, length);
                        if (end < 0 || !visitor.visit(start, end)) {
                            return;
                        }
                        start = end;
                    }
                    return;
                }

                if (prefix == null) {
                    SearchMemoryBudget.requireScratch(SearchMemoryBudget.FIXED_OVERHEAD
                        + 8L * questionMasks.length + 64L * literalMasks.size());
                }

                // A long mixed segment can otherwise update hundreds of words
                // for every source code point. Reject a missing mandatory
                // literal with one linear pass before starting that automaton.
                if (prefix == null && questionMasks.length > 4
                    && (long) (source.length() - from) * questionMasks.length > 1_000_000L
                    && !containsRequiredLiterals(source, from, caseInsensitive)) {
                    return;
                }

                int offset = advance(source, from, leadingQuestions);
                int suffixEnd = advance(source, offset, trailingQuestions);
                if (suffixEnd < 0) {
                    return;
                }
                int start = from;
                int untilFullWindow = literals.length;
                int matched = 0;
                long[] state = prefix == null ? new long[questionMasks.length] : null;
                SearchWorkBudget budget = SearchWorkBudget.current();
                long finalBit = 1L << ((literals.length - 1) % Long.SIZE);
                while (suffixEnd < source.length()) {
                    int codePoint = source.codePointAt(offset);
                    int next = offset + Character.charCount(codePoint);
                    suffixEnd += Character.charCount(source.codePointAt(suffixEnd));
                    // Once the fixed core has filled, advance the full-window
                    // start with input; suffixEnd keeps the trailing lookahead.
                    if (untilFullWindow > 0) {
                        untilFullWindow--;
                    } else {
                        start += Character.charCount(source.codePointAt(start));
                    }
                    if (caseInsensitive) {
                        codePoint = foldCase(codePoint);
                    }
                    boolean complete;
                    if (prefix != null) {
                        while (matched > 0 && codePoint != literals[matched]) {
                            matched = prefix[matched - 1];
                        }
                        if (codePoint == literals[matched]) {
                            matched++;
                        }
                        complete = matched == literals.length;
                    } else {
                        if (budget != null && state.length > 1) {
                            budget.consume(state.length - 1L);
                        }
                        WordMask[] masks = literalMasks.get(codePoint);
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
                        complete = (state[state.length - 1] & finalBit) != 0;
                    }
                    if (complete) {
                        if (!visitor.visit(start, suffixEnd)) {
                            return;
                        }
                        if (nonOverlapping) {
                            start = suffixEnd;
                            offset = advance(source, start, leadingQuestions);
                            suffixEnd = advance(source, offset, trailingQuestions);
                            if (suffixEnd < 0) {
                                return;
                            }
                            untilFullWindow = literals.length;
                            matched = 0;
                            if (state != null) {
                                Arrays.fill(state, 0L);
                            }
                            continue;
                        }
                        if (prefix != null) {
                            matched = prefix[matched - 1];
                        }
                    }
                    offset = next;
                }
            }

            private boolean containsRequiredLiterals(String source, int from, boolean caseInsensitive) {
                var missing = new HashSet<>(literalMasks.keySet());
                for (int offset = from; offset < source.length();) {
                    int codePoint = source.codePointAt(offset);
                    offset += Character.charCount(codePoint);
                    missing.remove(caseInsensitive ? foldCase(codePoint) : codePoint);
                    if (missing.isEmpty()) {
                        return true;
                    }
                }
                return false;
            }

            /** Advances at most a query-sized prefix; -1 means the source ended first. */
            private static int advance(String source, int from, int codePoints) {
                if (from < 0) {
                    return -1;
                }
                int offset = from;
                while (codePoints-- > 0) {
                    if (offset == source.length()) {
                        return -1;
                    }
                    offset += Character.charCount(source.codePointAt(offset));
                }
                return offset;
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
