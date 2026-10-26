package dev.tuink.core;

import dev.tuink.editor.search.SearchOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        return compile(query, options, SearchWorkBudget.current());
    }

    public static SearchMatcher compile(String query, SearchOptions options, SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        try {
            String safeQuery = query == null ? "" : query;
            SearchOptions safeOptions = options == null ? SearchOptions.DEFAULT : options;
            int codePoints = safeQuery.codePointCount(0, safeQuery.length());
            if (codePoints > 0) {
                SearchMemoryBudget.requireCompilation(compilationBytes(safeQuery, safeOptions, codePoints), context);
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
    public List<Match> findAll(CharSequence source) {
        return findAll(source, SearchWorkBudget.current());
    }

    public List<Match> findAll(CharSequence source, SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        try {
            List<Match> matches = new ArrayList<>();
            scanMatches(source, (start, end) -> {
                matches.add(new Match(start, end));
                return true;
            }, context);
            return List.copyOf(matches);
        } catch (OutOfMemoryError exhausted) {
            throw MEMORY_LIMIT;
        }
    }

    /**
     * Returns only results intersecting {@code [startUtf16, endUtf16)}.
     * Ranges retain their full source offsets rather than being clipped to the
     * interval, including a result that starts before its left edge. Earlier
     * results still determine non-overlap, as in {@link #findAll(CharSequence)}.
     */
    public List<Match> findIntersecting(CharSequence source, int startUtf16, int endUtf16) {
        return findIntersecting(source, startUtf16, endUtf16, SearchWorkBudget.current());
    }

    public List<Match> findIntersecting(CharSequence source, int startUtf16, int endUtf16, SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
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
            }, context);
            return List.copyOf(matches);
        } catch (OutOfMemoryError exhausted) {
            throw MEMORY_LIMIT;
        }
    }

    /**
     * Returns the first result whose start is at least {@code fromUtf16}, or null.
     * Results retain the same non-overlapping ranges as {@link #findAll(CharSequence)},
     * even when the requested offset lies inside an earlier result.
     */
    public Match findFirstAtOrAfter(CharSequence source, int fromUtf16) {
        return findFirstAtOrAfter(source, fromUtf16, SearchWorkBudget.current());
    }

    public Match findFirstAtOrAfter(CharSequence source, int fromUtf16, SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        try {
            int[] found = {-1, -1};
            scanMatches(source, (start, end) -> {
                if (start < fromUtf16) {
                    return true;
                }
                found[0] = start;
                found[1] = end;
                return false;
            }, context);
            return found[0] < 0 ? null : new Match(found[0], found[1]);
        } catch (OutOfMemoryError exhausted) {
            throw MEMORY_LIMIT;
        }
    }

    /** Returns the last result starting before {@code beforeUtf16}, or null. */
    public Match findLastBefore(CharSequence source, int beforeUtf16) {
        return findLastBefore(source, beforeUtf16, SearchWorkBudget.current());
    }

    public Match findLastBefore(CharSequence source, int beforeUtf16, SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        try {
            int[] found = {-1, -1};
            scanMatches(source, (start, end) -> {
                if (start >= beforeUtf16) {
                    return false;
                }
                found[0] = start;
                found[1] = end;
                return true;
            }, context);
            return found[0] < 0 ? null : new Match(found[0], found[1]);
        } catch (OutOfMemoryError exhausted) {
            throw MEMORY_LIMIT;
        }
    }

    /** Counts results without allocating a range object for each occurrence. */
    public int countMatches(CharSequence source) {
        return countMatches(source, SearchWorkBudget.current());
    }

    public int countMatches(CharSequence source, SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        try {
            int[] count = {0};
            scanMatches(source, (start, end) -> {
                count[0]++;
                return true;
            }, context);
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
    public boolean matchesRange(CharSequence source, int startUtf16, int endUtf16) {
        return matchesRange(source, startUtf16, endUtf16, SearchWorkBudget.current());
    }

    public boolean matchesRange(CharSequence source, int startUtf16, int endUtf16, SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        if (source == null
            || startUtf16 < 0
            || endUtf16 < startUtf16
            || endUtf16 > source.length()
            || (literal == null && wildcard == null)) {
            return false;
        }
        Match match = findFirstAtOrAfter(source, startUtf16, context);
        return match != null && match.startUtf16() == startUtf16 && match.endUtf16() == endUtf16;
    }

    /** Visits the same ranges as findAll without retaining a Match object for each result. */
    void scanMatches(CharSequence source, MatchVisitor visitor) {
        scanMatches(source, visitor, SearchWorkBudget.current());
    }

    void scanMatches(CharSequence source, MatchVisitor visitor, SearchOperationContext context) {
        Objects.requireNonNull(context, "context");
        if (source == null || source.length() == 0 || (literal == null && wildcard == null)) {
            return;
        }
        try {
            if (wildcard != null) {
                wildcard.scanMatches(source, visitor, context);
                return;
            }
            literal.scanMatches(source, visitor, matchMode == SearchOptions.MatchMode.WHOLE_WORD, context);
        } catch (OutOfMemoryError exhausted) {
            throw MEMORY_LIMIT;
        }
    }

    @FunctionalInterface
    interface MatchVisitor {
        /** Returns false when no more results are needed. */
        boolean visit(int startUtf16, int endUtf16);
    }

    /**
     * Resumes at zero or at the end of a previously accepted match. Arbitrary
     * offsets are deliberately handled by SearchNavigation: restarting there
     * would change the canonical non-overlapping sequence.
     */
    Cursor cursor(CharSequence source, int acceptedEnd) {
        try {
            if (source == null || source.length() == 0 || (literal == null && wildcard == null)) {
                return new Cursor() { @Override boolean advance(SearchOperationContext context) { return false; } };
            }
            if (literal != null) {
                return literal.cursor(source, acceptedEnd, matchMode == SearchOptions.MatchMode.WHOLE_WORD);
            }
            if (!wildcard.hasStar) {
                return wildcard.segments.get(0).cursor(source, acceptedEnd, true, wildcard.caseInsensitive);
            }
            // A starred pattern has at most one result on a line. Its greedy
            // suffix needs the complete line, so retain the existing scan.
            return new Cursor() {
                private boolean done = acceptedEnd != 0;

                @Override boolean advance(SearchOperationContext context) {
                    if (done) return false;
                    done = true;
                    int[] result = {-1, -1};
                    wildcard.scanMatches(source, (start, end) -> {
                        result[0] = start;
                        result[1] = end;
                        return false;
                    }, context);
                    return result[0] >= 0 && found(result[0], result[1]);
                }
            };
        } catch (OutOfMemoryError exhausted) {
            throw MEMORY_LIMIT;
        }
    }

    abstract static class Cursor {
        // Counts primary literal/fixed-segment scan steps for deterministic
        // navigation regressions, excluding starred scans and prefilters.
        long scannedCodePoints;
        int startUtf16;
        int endUtf16;

        final Match next() {
            return next(SearchWorkBudget.current());
        }

        final Match next(SearchOperationContext context) {
            return moveNext(context) ? new Match(startUtf16, endUtf16) : null;
        }

        final boolean moveNext(SearchOperationContext context) {
            try {
                return advance(context);
            } catch (OutOfMemoryError exhausted) {
                throw MEMORY_LIMIT;
            }
        }

        final boolean found(int start, int end) {
            startUtf16 = start;
            endUtf16 = end;
            return true;
        }

        abstract boolean advance(SearchOperationContext context);
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

        private void scanMatches(CharSequence source, MatchVisitor visitor, boolean wholeWord,
                                 SearchOperationContext context) {
            if (tokens.length > source.length()) {
                return;
            }
            Cursor cursor = cursor(source, 0, wholeWord);
            while (cursor.moveNext(context)) {
                if (!visitor.visit(cursor.startUtf16, cursor.endUtf16)) return;
            }
        }

        private int fold(int codePoint) {
            return caseInsensitive ? Character.toLowerCase(Character.toUpperCase(codePoint)) : codePoint;
        }

        private Cursor cursor(CharSequence source, int from, boolean wholeWord) {
            SearchMemoryBudget.requireScratch(SearchMemoryBudget.FIXED_OVERHEAD + 4L * tokens.length);
            return new Cursor() {
                private final int[] offsets = new int[tokens.length];
                private int offset = from;
                private int codePointIndex;
                private int matched;

                @Override boolean advance(SearchOperationContext context) {
                    while (offset < source.length()) {
                        offsets[codePointIndex % tokens.length] = offset;
                        int codePoint = Character.codePointAt(source, offset);
                        offset += Character.charCount(codePoint);
                        scannedCodePoints++;
                        int token = fold(codePoint);
                        while (matched > 0 && token != tokens[matched]) matched = prefix[matched - 1];
                        if (token == tokens[matched]) matched++;
                        codePointIndex++;
                        if (matched == tokens.length) {
                            int start = offsets[(codePointIndex - tokens.length) % tokens.length];
                            if (!wholeWord || isWholeWord(source, start, offset)) {
                                matched = 0;
                                return found(start, offset);
                            }
                            matched = prefix[matched - 1];
                        }
                    }
                    return false;
                }
            };
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

        private void scanMatches(CharSequence source, MatchVisitor visitor, SearchOperationContext context) {
            // UTF-16 length is a cheap upper bound on the available code points.
            // A source-sized code-point/offset index would defeat bounded searches
            // on long lines; each segment walks the original String instead.
            if (minimumLength > source.length()
                || WildcardSegment.advance(source, 0, minimumLength) < 0) {
                return;
            }

            if (!hasStar) {
                segments.get(0).scanMatches(source, 0, true, caseInsensitive, visitor, context);
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
                Match found = segment.find(source, end, findLast, caseInsensitive, context);
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
                    if (!entries.isEmpty() && entries.get(entries.size() - 1).word == word) {
                        WordMask previous = entries.remove(entries.size() - 1);
                        entries.add(new WordMask(word, previous.bits | bit));
                    } else {
                        entries.add(new WordMask(word, bit));
                    }
                }
                literalMasks = new HashMap<>();
                masks.forEach((codePoint, entries) -> literalMasks.put(codePoint, entries.toArray(WordMask[]::new)));
            }

            private Match find(CharSequence source, int from, boolean last, boolean caseInsensitive,
                               SearchOperationContext context) {
                if (literals.length == 0) {
                    int end = advance(source, from, length);
                    if (end < 0) {
                        return null;
                    }
                    return last ? new Match(Character.offsetByCodePoints(source, source.length(), -length), source.length())
                        : new Match(from, end);
                }
                int[] found = {-1, -1};
                scanMatches(source, from, false, caseInsensitive, (start, end) -> {
                    found[0] = start;
                    found[1] = end;
                    return last;
                }, context);
                return found[0] < 0 ? null : new Match(found[0], found[1]);
            }

            private void scanMatches(
                CharSequence source, int from, boolean nonOverlapping, boolean caseInsensitive, MatchVisitor visitor,
                SearchOperationContext context
            ) {
                Cursor cursor = cursor(source, from, nonOverlapping, caseInsensitive);
                while (cursor.moveNext(context)) {
                    if (!visitor.visit(cursor.startUtf16, cursor.endUtf16)) return;
                }
            }

            private Cursor cursor(CharSequence source, int from, boolean nonOverlapping, boolean caseInsensitive) {
                if (literals.length == 0) {
                    // Used for a pattern consisting only of '?'. Each source code
                    // point is visited once, even when the query itself is long.
                    return new Cursor() {
                        private int start = from;

                        @Override boolean advance(SearchOperationContext context) {
                            if (start >= source.length()) return false;
                            int end = WildcardSegment.advance(source, start, length);
                            if (end < 0) {
                                start = source.length();
                                return false;
                            }
                            scannedCodePoints += length;
                            int previous = start;
                            start = end;
                            return found(previous, end);
                        }
                    };
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
                    return new Cursor() { @Override boolean advance(SearchOperationContext context) { return false; } };
                }

                return new Cursor() {
                    private int offset = WildcardSegment.advance(source, from, leadingQuestions);
                    private int suffixEnd = WildcardSegment.advance(source, offset, trailingQuestions);
                    private int start = from;
                    private int untilFullWindow = literals.length;
                    private int matched;
                    private final long[] state = prefix == null ? new long[questionMasks.length] : null;
                    private final long finalBit = 1L << ((literals.length - 1) % Long.SIZE);

                    @Override boolean advance(SearchOperationContext context) {
                        while (suffixEnd >= 0 && suffixEnd < source.length()) {
                            int codePoint = Character.codePointAt(source, offset);
                            int next = offset + Character.charCount(codePoint);
                            scannedCodePoints++;
                            suffixEnd += Character.charCount(Character.codePointAt(source, suffixEnd));
                            // Keep the full-window start and trailing lookahead
                            // in original UTF-16 coordinates without a line index.
                            if (untilFullWindow > 0) {
                                untilFullWindow--;
                            } else {
                                start += Character.charCount(Character.codePointAt(source, start));
                            }
                            if (caseInsensitive) codePoint = foldCase(codePoint);
                            boolean complete;
                            if (prefix != null) {
                                while (matched > 0 && codePoint != literals[matched]) matched = prefix[matched - 1];
                                if (codePoint == literals[matched]) matched++;
                                complete = matched == literals.length;
                            } else {
                                if (state.length > 1) context.consume(state.length - 1L);
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
                                int matchStart = start;
                                int matchEnd = suffixEnd;
                                if (nonOverlapping) {
                                    start = suffixEnd;
                                    offset = WildcardSegment.advance(source, start, leadingQuestions);
                                    suffixEnd = WildcardSegment.advance(source, offset, trailingQuestions);
                                    untilFullWindow = literals.length;
                                    matched = 0;
                                    if (state != null) Arrays.fill(state, 0L);
                                } else {
                                    if (prefix != null) matched = prefix[matched - 1];
                                    offset = next;
                                }
                                return found(matchStart, matchEnd);
                            }
                            offset = next;
                        }
                        return false;
                    }
                };
            }

            private boolean containsRequiredLiterals(CharSequence source, int from, boolean caseInsensitive) {
                var missing = new HashSet<>(literalMasks.keySet());
                for (int offset = from; offset < source.length();) {
                    int codePoint = Character.codePointAt(source, offset);
                    offset += Character.charCount(codePoint);
                    missing.remove(caseInsensitive ? foldCase(codePoint) : codePoint);
                    if (missing.isEmpty()) {
                        return true;
                    }
                }
                return false;
            }

            /** Advances at most a query-sized prefix; -1 means the source ended first. */
            private static int advance(CharSequence source, int from, int codePoints) {
                if (from < 0) {
                    return -1;
                }
                int offset = from;
                while (codePoints-- > 0) {
                    if (offset == source.length()) {
                        return -1;
                    }
                    offset += Character.charCount(Character.codePointAt(source, offset));
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

    private static boolean isWholeWord(CharSequence source, int start, int end) {
        boolean leftBoundary = start == 0 || !isWordCodePoint(Character.codePointBefore(source, start));
        boolean rightBoundary = end == source.length() || !isWordCodePoint(Character.codePointAt(source, end));
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
