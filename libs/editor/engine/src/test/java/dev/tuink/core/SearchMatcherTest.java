package dev.tuink.core;

import dev.tuink.editor.search.SearchOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchMatcherTest {
    private static final SearchOptions CASE_INSENSITIVE = new SearchOptions(
        true,
        SearchOptions.MatchMode.SUBSTRING
    );

    @Test
    void cappedResultsStopBeforeReadingTheRestOfTheSourceAndPreserveCanonicalUnicodeRanges() {
        CharSequence source = new CharSequence() {
            @Override public int length() { return 1_000_000; }
            @Override public char charAt(int index) {
                if (index > 0) throw new AssertionError("Reading beyond the admitted first match");
                return 'x';
            }
            @Override public CharSequence subSequence(int start, int end) { throw new AssertionError("Copying the source"); }
        };
        var matcher = SearchMatcher.compile("x", SearchOptions.DEFAULT);
        assertEquals(List.of(new SearchMatcher.Match(0, 1)),
            matcher.findAll(source, 1, SearchOperationContext.bounded(0)));
        assertEquals(List.of(), matcher.findAll(source, 0, SearchOperationContext.bounded(0)));
        assertThrows(IllegalArgumentException.class, () -> matcher.findAll(source, -1, SearchOperationContext.bounded(0)));
        assertThrows(NullPointerException.class, () -> matcher.findAll(source, 0, null));
        var unicode = SearchMatcher.compile("𐐀?", new SearchOptions(true, SearchOptions.MatchMode.WILDCARD));
        String text = "𐐨x 𐐀X 𐐨z";
        var capped = unicode.findAll(text, 2, SearchOperationContext.bounded(100));
        assertEquals(unicode.findAll(text).subList(0, 2), capped);
        assertThrows(UnsupportedOperationException.class, () -> capped.add(new SearchMatcher.Match(0, 0)));
    }

    @Test
    void cappedResultsRespectTheExplicitWorkContext() {
        var context = SearchOperationContext.bounded(0);
        var matcher = SearchMatcher.compile("a".repeat(64) + "?a",
            new SearchOptions(false, SearchOptions.MatchMode.WILDCARD), context);
        assertThrows(SearchLimitException.class, () -> matcher.findAll("a".repeat(132), 1, context));
    }

    @Test
    void caseInsensitiveLiteralRangesAddressTheOriginalSource() {
        String source = "İx";
        SearchMatcher matcher = SearchMatcher.compile("x", CASE_INSENSITIVE);

        assertEquals(List.of(new SearchMatcher.Match(1, 2)), matcher.findAll(source));
        assertTrue(matcher.matchesRange(source, 1, 2));
        assertEquals("x", source.substring(1, 2));
    }

    @Test
    void longRepetitiveLiteralQueriesCompileAndSearchWithinTheInteractiveBudget() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            String query = "a".repeat(200_000);
            for (boolean insensitive : List.of(false, true)) {
                for (SearchOptions.MatchMode mode : List.of(
                    SearchOptions.MatchMode.SUBSTRING, SearchOptions.MatchMode.WHOLE_WORD
                )) {
                    SearchMatcher matcher = SearchMatcher.compile(query, new SearchOptions(insensitive, mode));
                    assertEquals(List.of(new SearchMatcher.Match(0, query.length())), matcher.findAll(query));
                    assertEquals(List.of(), matcher.findAll("a".repeat(199_999)));
                    assertEquals(List.of(), SearchMatcher.compile(query + "b", new SearchOptions(insensitive, mode))
                        .findAll(query + "c"));
                }
            }
        });
    }

    @Test
    void literalCaseFoldingPreservesSupplementaryAndCombiningOffsets() {
        SearchMatcher dottedI = SearchMatcher.compile("i", CASE_INSENSITIVE);
        assertEquals(List.of(new SearchMatcher.Match(0, 1), new SearchMatcher.Match(2, 3),
            new SearchMatcher.Match(4, 5), new SearchMatcher.Match(6, 7)), dottedI.findAll("İ I ı i"));
        assertEquals(List.of(new SearchMatcher.Match(0, 3), new SearchMatcher.Match(4, 7)),
            SearchMatcher.compile("𐐀x", CASE_INSENSITIVE).findAll("𐐨X 𐐀x"));
        assertEquals(List.of(new SearchMatcher.Match(0, 1), new SearchMatcher.Match(2, 3)),
            SearchMatcher.compile("σ", CASE_INSENSITIVE).findAll("Σ ς"));
        assertEquals(List.of(), SearchMatcher.compile("ss", CASE_INSENSITIVE).findAll("ß"));
        for (String query : List.of("ß", "ẞ")) {
            assertEquals(List.of(new SearchMatcher.Match(0, 1), new SearchMatcher.Match(2, 3)),
                SearchMatcher.compile(query, CASE_INSENSITIVE).findAll("ß ẞ"));
        }
        assertEquals(List.of(new SearchMatcher.Match(3, 5)),
            SearchMatcher.compile("e\u0301", SearchOptions.DEFAULT).findAll("🙂 e\u0301"));
        assertEquals(List.of(), SearchMatcher.compile("e\u0301", SearchOptions.DEFAULT).findAll("é"));
    }

    @Test
    void literalMatchingKeepsGlobAndRegexCharactersLiteral() {
        String query = "[a].*?\\E";
        String source = query + query;
        SearchMatcher matcher = SearchMatcher.compile(query, SearchOptions.DEFAULT);

        assertEquals(List.of(new SearchMatcher.Match(0, query.length()),
            new SearchMatcher.Match(query.length(), source.length())), matcher.findAll(source));
        assertEquals(2, matcher.countMatches(source));
        assertEquals(List.of(new SearchMatcher.Match(0, query.length())), matcher.findIntersecting(source, 1, 2));
        assertNull(matcher.findFirstAtOrAfter(source, query.length() + 1));
    }

    @Test
    void wildcardQuestionMarkConsumesOneSupplementaryCodePoint() {
        String source = "𝔸x 𝔸X";
        SearchMatcher matcher = SearchMatcher.compile(
            "?x",
            new SearchOptions(true, SearchOptions.MatchMode.WILDCARD)
        );

        assertEquals(
            List.of(new SearchMatcher.Match(0, 3), new SearchMatcher.Match(4, 7)),
            matcher.findAll(source)
        );
    }

    @Test
    void wildcardStarsKeepLeftmostGreedyAndNonOverlappingMatchRanges() {
        SearchOptions wildcard = new SearchOptions(false, SearchOptions.MatchMode.WILDCARD);
        String source = "x a1b a2b tail";
        SearchMatcher greedy = SearchMatcher.compile("a*b", wildcard);

        assertEquals(List.of(new SearchMatcher.Match(2, 9)), greedy.findAll(source));
        assertTrue(greedy.matchesRange(source, 2, 9));
        assertFalse(greedy.matchesRange(source, 2, 5));
        assertFalse(greedy.matchesRange(source, 6, 9));
        assertEquals(
            List.of(new SearchMatcher.Match(2, 5), new SearchMatcher.Match(6, 9)),
            SearchMatcher.compile("a?b", wildcard).findAll(source)
        );
        assertEquals(greedy.findAll(source), SearchMatcher.compile("a**b", wildcard).findAll(source));
    }

    @Test
    void wildcardStarsMayConsumeNoTextAndEmptyMatchesAreNotReported() {
        SearchOptions wildcard = new SearchOptions(false, SearchOptions.MatchMode.WILDCARD);

        assertEquals(
            List.of(new SearchMatcher.Match(1, 3)),
            SearchMatcher.compile("a*b", wildcard).findAll("xabx")
        );
        assertEquals(
            List.of(new SearchMatcher.Match(0, 3)),
            SearchMatcher.compile("**", wildcard).findAll("abc")
        );
        assertEquals(List.of(), SearchMatcher.compile("**", wildcard).findAll(""));
        assertFalse(SearchMatcher.compile("**", wildcard).matchesRange("", 0, 0));
        assertEquals(List.of(), SearchMatcher.compile("", wildcard).findAll("abc"));
        assertEquals(List.of(), SearchMatcher.compile("*a*", wildcard).findAll("bbb"));
        assertEquals(
            List.of(new SearchMatcher.Match(0, 1)),
            SearchMatcher.compile("?", wildcard).findAll("\n")
        );
    }

    @Test
    void wildcardUnicodeCaseFoldingKeepsOriginalUtf16RangesWithoutExpandingLetters() {
        SearchOptions insensitive = new SearchOptions(true, SearchOptions.MatchMode.WILDCARD);

        assertEquals(
            List.of(
                new SearchMatcher.Match(0, 2), new SearchMatcher.Match(3, 5),
                new SearchMatcher.Match(6, 8), new SearchMatcher.Match(9, 11)
            ),
            SearchMatcher.compile("i?", insensitive).findAll("İx Ix ıx ix")
        );
        assertEquals(
            List.of(new SearchMatcher.Match(0, 3), new SearchMatcher.Match(4, 7)),
            SearchMatcher.compile("𐐀?", insensitive).findAll("𐐨x 𐐀X")
        );
        assertEquals(List.of(), SearchMatcher.compile("ss", insensitive).findAll("ß"));
        assertEquals(
            List.of(new SearchMatcher.Match(0, 1), new SearchMatcher.Match(2, 3)),
            SearchMatcher.compile("σ", insensitive).findAll("Σ ς")
        );
    }

    @Test
    void separatedWildcardStarsWithAFailingSuffixFinishWithinTheInteractiveBudget() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            SearchOptions wildcard = new SearchOptions(false, SearchOptions.MatchMode.WILDCARD);
            SearchMatcher matcher = SearchMatcher.compile("*a".repeat(64) + "z", wildcard);
            String source = "a".repeat(20_000);

            assertEquals(List.of(), matcher.findAll(source));
            assertFalse(matcher.matchesRange(source, 0, source.length()));
            String matching = source + "z";
            assertEquals(
                List.of(new SearchMatcher.Match(0, matching.length())),
                matcher.findAll(matching)
            );
            assertTrue(matcher.matchesRange(matching, 0, matching.length()));
        });
    }

    @Test
    void longWildcardQueriesAvoidTheQueryTimesSourceDynamicProgrammingTable() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            SearchOptions wildcard = new SearchOptions(false, SearchOptions.MatchMode.WILDCARD);
            String source = "a".repeat(200_000);
            assertEquals(
                List.of(new SearchMatcher.Match(0, source.length())),
                SearchMatcher.compile("*".repeat(200_000), wildcard).findAll(source)
            );
            SearchMatcher separated = SearchMatcher.compile("*a".repeat(10_000) + "z", wildcard);
            assertEquals(List.of(), separated.findAll(source));
            assertEquals(
                List.of(new SearchMatcher.Match(0, source.length() + 1)),
                separated.findAll(source + "z")
            );
            assertEquals(
                List.of(new SearchMatcher.Match(100_000, 200_001)),
                SearchMatcher.compile("a".repeat(100_000) + "b", wildcard).findAll(source + "b")
            );
            assertEquals(
                List.of(new SearchMatcher.Match(0, 100_001)),
                SearchMatcher.compile("?".repeat(100_000) + "a", wildcard).findAll(source)
            );
            assertEquals(
                List.of(),
                SearchMatcher.compile("a?".repeat(200_000), wildcard).findAll(source)
            );
            assertEquals(
                List.of(),
                SearchMatcher.compile("a?".repeat(5_000) + "z", wildcard).findAll(source)
            );
            assertEquals(
                List.of(),
                SearchMatcher.compile("a?".repeat(150_000), wildcard).findAll("🙂".repeat(200_000))
            );
        });
    }

    @Test
    void wildcardSegmentsAgreeWithAnExhaustiveReferenceIncludingGreedyEndpoints() {
        List<String> sources = stringsUpTo(List.of("a", "İ", "🙂"), 4);
        List<String> queries = stringsUpTo(List.of("a", "i", "🙂", "?", "*"), 4);
        for (boolean insensitive : List.of(false, true)) {
            SearchOptions options = new SearchOptions(insensitive, SearchOptions.MatchMode.WILDCARD);
            for (String query : queries) {
                SearchMatcher matcher = SearchMatcher.compile(query, options);
                for (String source : sources) {
                    assertEquals(
                        referenceWildcardMatches(source, query, insensitive),
                        matcher.findAll(source),
                        () -> "source=" + source + ", query=" + query + ", insensitive=" + insensitive
                    );
                }
            }
        }
    }

    @Test
    void wildcardQuestionSegmentsCarryStatesAcrossMachineWordsAndKeepOverlappingCandidates() {
        SearchOptions wildcard = new SearchOptions(false, SearchOptions.MatchMode.WILDCARD);
        String query = "a?".repeat(70) + "b";
        String source = "a".repeat(300) + "b";
        assertEquals(
            List.of(new SearchMatcher.Match(160, 301)),
            SearchMatcher.compile(query, wildcard).findAll(source)
        );
        assertEquals(
            List.of(new SearchMatcher.Match(0, 301)),
            SearchMatcher.compile("*" + query, wildcard).findAll(source)
        );
        assertEquals(
            List.of(new SearchMatcher.Match(0, 145)),
            SearchMatcher.compile("a*" + "?".repeat(140) + "b", wildcard).findAll("a" + "a".repeat(143) + "b")
        );
        String distinct = new String(java.util.stream.IntStream.range(0x1000, 0x2000).toArray(), 0, 4096);
        String unicodeQuery = distinct + "?" + distinct;
        String unicodeSource = "prefix" + distinct + "🙂" + distinct;
        assertEquals(
            List.of(new SearchMatcher.Match(6, unicodeSource.length())),
            SearchMatcher.compile(unicodeQuery, wildcard).findAll(unicodeSource)
        );
    }

    @Test
    void streamingWildcardWindowsKeepUnicodeOffsetsForLeadingInternalAndTrailingQuestions() {
        List<String> sources = List.of(
            "🙂aĀa🙂aāa🙂aĀa🙂",
            "🙂".repeat(75) + "a🙂b" + "Ā".repeat(75) + "🙂a🙂b" + "🙂".repeat(75)
        );
        List<String> queries = List.of(
            "??a?a??", "*??a?a??", "??a?a??*", "??a?a??*🙂??", "??Ā??",
            "?".repeat(70) + "a?b" + "?".repeat(70),
            "*" + "?".repeat(70) + "a?b" + "?".repeat(70)
        );
        for (boolean insensitive : List.of(false, true)) {
            SearchOptions options = new SearchOptions(insensitive, SearchOptions.MatchMode.WILDCARD);
            for (String source : sources) {
                for (String query : queries) {
                    SearchMatcher matcher = SearchMatcher.compile(query, options);
                    List<SearchMatcher.Match> expected = referenceWildcardMatches(source, query, insensitive);
                    String context = "query=" + query + ", insensitive=" + insensitive;
                    assertEquals(expected, matcher.findAll(source), context);
                    assertEquals(expected.size(), matcher.countMatches(source), context);
                    for (int from = 0; from <= source.length(); from++) {
                        int start = from;
                        assertEquals(expected.stream().filter(match -> match.startUtf16() >= start).findFirst().orElse(null),
                            matcher.findFirstAtOrAfter(source, from), context);
                        assertEquals(expected.stream().filter(match -> match.startUtf16() < start).reduce((a, b) -> b).orElse(null),
                            matcher.findLastBefore(source, from), context);
                        assertEquals(expected.stream().filter(match -> match.endUtf16() > start
                            && match.startUtf16() < start + 2).toList(), matcher.findIntersecting(source, from, from + 2), context);
                    }
                }
            }
        }
    }

    @Test
    void wholeWordBoundariesUseCodePointsAndTreatCombiningMarksAsWordContinuations() {
        String source = "𝔸x x e\u0301 e";
        SearchMatcher wholeWordX = SearchMatcher.compile(
            "x",
            new SearchOptions(false, SearchOptions.MatchMode.WHOLE_WORD)
        );
        SearchMatcher wholeWordE = SearchMatcher.compile(
            "e",
            new SearchOptions(false, SearchOptions.MatchMode.WHOLE_WORD)
        );

        assertEquals(List.of(new SearchMatcher.Match(4, 5)), wholeWordX.findAll(source));
        assertEquals(List.of(new SearchMatcher.Match(9, 10)), wholeWordE.findAll(source));
    }

    @Test
    void wholeWordSearchRetriesOverlappingCandidatesAfterRejectedBoundaries() {
        for (boolean insensitive : List.of(false, true)) {
            SearchOptions options = new SearchOptions(insensitive, SearchOptions.MatchMode.WHOLE_WORD);
            for (String separator : List.of("-", " ")) {
                String query = "a" + separator + "a";
                String source = "ba" + separator + "a" + separator + "a";
                if (insensitive) {
                    source = source.toUpperCase(java.util.Locale.ROOT);
                }
                SearchMatcher matcher = SearchMatcher.compile(query, options);
                SearchMatcher.Match expected = new SearchMatcher.Match(3, 6);

                assertEquals(List.of(expected), matcher.findAll(source));
                assertEquals(1, matcher.countMatches(source));
                assertEquals(expected, matcher.findFirstAtOrAfter(source, 0));
                assertEquals(expected, matcher.findFirstAtOrAfter(source, 3));
                assertNull(matcher.findFirstAtOrAfter(source, 4));
                assertEquals(expected, matcher.findLastBefore(source, 6));
                assertNull(matcher.findLastBefore(source, 3));
                assertEquals(List.of(expected), matcher.findIntersecting(source, 4, 5));
                assertTrue(matcher.matchesRange(source, 3, 6));
                assertFalse(matcher.matchesRange(source, 1, 4));
            }
        }
    }

    @Test
    void wholeWordSearchStillKeepsAcceptedMatchesNonOverlapping() {
        SearchMatcher matcher = SearchMatcher.compile(
            "a-a", new SearchOptions(false, SearchOptions.MatchMode.WHOLE_WORD)
        );

        assertEquals(
            List.of(new SearchMatcher.Match(0, 3), new SearchMatcher.Match(4, 7)),
            matcher.findAll("a-a-a-a-a")
        );
    }

    @Test
    void everyReportedRangeIsBoundedAndDoesNotSplitSurrogatePairs() {
        List<String> sources = List.of("İx", "𝔸x", "e\u0301x", "🙂X", "a_b x");
        List<String> queries = List.of("x", "i", "?x", "*x", "e", "𝔸");
        List<SearchOptions> options = List.of(
            SearchOptions.DEFAULT,
            CASE_INSENSITIVE,
            new SearchOptions(true, SearchOptions.MatchMode.WHOLE_WORD),
            new SearchOptions(true, SearchOptions.MatchMode.WILDCARD)
        );

        for (String source : sources) {
            for (String query : queries) {
                for (SearchOptions option : options) {
                    SearchMatcher matcher = SearchMatcher.compile(query, option);
                    for (SearchMatcher.Match match : matcher.findAll(source)) {
                        assertTrue(match.startUtf16() >= 0);
                        assertTrue(match.startUtf16() < match.endUtf16());
                        assertTrue(match.endUtf16() <= source.length());
                        assertFalse(splitsSurrogatePair(source, match.startUtf16()));
                        assertFalse(splitsSurrogatePair(source, match.endUtf16()));
                        assertTrue(matcher.matchesRange(source, match.startUtf16(), match.endUtf16()));
                    }
                }
            }
        }
    }

    @Test
    void boundedSearchAndCountKeepTheSameNonOverlappingUnicodeAndWildcardResults() {
        List<String> sources = List.of("", "aaaaa", "x a1b a2b tail", "İx ix", "🙂e\u0301x e", "𝔸x x");
        List<String> queries = List.of("", "aa", "x", "e", "?x", "a*b", "**");
        List<SearchOptions> options = List.of(
            SearchOptions.DEFAULT,
            CASE_INSENSITIVE,
            new SearchOptions(true, SearchOptions.MatchMode.WHOLE_WORD),
            new SearchOptions(true, SearchOptions.MatchMode.WILDCARD)
        );
        for (String source : sources) {
            for (String query : queries) {
                for (SearchOptions option : options) {
                    SearchMatcher matcher = SearchMatcher.compile(query, option);
                    List<SearchMatcher.Match> all = matcher.findAll(source);
                    String context = "source=" + source + ", query=" + query + ", options=" + option;
                    assertEquals(all.size(), matcher.countMatches(source), context);
                    for (int offset = -1; offset <= source.length() + 1; offset++) {
                        SearchMatcher.Match first = null;
                        SearchMatcher.Match last = null;
                        for (SearchMatcher.Match match : all) {
                            if (first == null && match.startUtf16() >= offset) {
                                first = match;
                            }
                            if (match.startUtf16() < offset) {
                                last = match;
                            }
                        }
                        assertEquals(first, matcher.findFirstAtOrAfter(source, offset), context + ", offset=" + offset);
                        assertEquals(last, matcher.findLastBefore(source, offset), context + ", offset=" + offset);
                    }
                }
            }
        }
        SearchMatcher matcher = SearchMatcher.compile("x", SearchOptions.DEFAULT);
        assertNull(matcher.findFirstAtOrAfter(null, 0));
        assertNull(matcher.findLastBefore(null, 1));
        assertEquals(0, matcher.countMatches(null));
    }

    @Test
    void visibleMatchesKeepFullRangesAcrossBothEdgesAndRespectEarlierNonOverlappingMatches() {
        SearchMatcher needle = SearchMatcher.compile("needle", SearchOptions.DEFAULT);
        String text = "xneedle needle";
        assertEquals(List.of(new SearchMatcher.Match(1, 7)), needle.findIntersecting(text, 3, 5));
        assertEquals(List.of(new SearchMatcher.Match(1, 7)), needle.findIntersecting(text, 0, 3));
        assertEquals(
            List.of(new SearchMatcher.Match(1, 7), new SearchMatcher.Match(8, 14)),
            needle.findIntersecting(text, 6, 9)
        );
        assertEquals(List.of(), needle.findIntersecting(text, 7, 8));
        assertEquals(List.of(), needle.findIntersecting(text, 3, 3));
        assertEquals(List.of(), needle.findIntersecting(null, 0, 3));

        SearchMatcher overlapping = SearchMatcher.compile("aa", SearchOptions.DEFAULT);
        assertEquals(
            List.of(new SearchMatcher.Match(0, 2), new SearchMatcher.Match(2, 4)),
            overlapping.findIntersecting("aaaaa", 1, 3)
        );
        assertEquals(List.of(new SearchMatcher.Match(2, 4)), overlapping.findIntersecting("aaaaa", 2, 3));
        assertEquals(List.of(), overlapping.findIntersecting("aaaaa", 4, 5));
    }

    @Test
    void visibleMatchesAgreeWithFilteringCompleteUnicodeAndWildcardResults() {
        String source = "𝔸x e\u0301 aa a1b a2b";
        for (SearchOptions.MatchMode mode : SearchOptions.MatchMode.values()) {
            for (String query : List.of("x", "e", "aa", "?", "a*b")) {
                SearchMatcher matcher = SearchMatcher.compile(query, new SearchOptions(true, mode));
                List<SearchMatcher.Match> all = matcher.findAll(source);
                for (int start = 0; start < source.length(); start++) {
                    int from = start;
                    int to = start + 2;
                    List<SearchMatcher.Match> expected = all.stream()
                        .filter(match -> match.endUtf16() > from && match.startUtf16() < to)
                        .toList();
                    assertEquals(expected, matcher.findIntersecting(source, from, to));
                }
            }
        }

        SearchMatcher dense = SearchMatcher.compile("a", SearchOptions.DEFAULT);
        assertEquals(
            List.of(new SearchMatcher.Match(1, 2), new SearchMatcher.Match(2, 3)),
            dense.findIntersecting("a".repeat(2_000_000), 1, 3)
        );
    }

    private static boolean splitsSurrogatePair(String source, int boundary) {
        return boundary > 0
            && boundary < source.length()
            && Character.isHighSurrogate(source.charAt(boundary - 1))
            && Character.isLowSurrogate(source.charAt(boundary));
    }

    private static List<String> stringsUpTo(List<String> alphabet, int maxLength) {
        List<String> strings = new ArrayList<>();
        strings.add("");
        List<String> previous = List.of("");
        for (int length = 1; length <= maxLength; length++) {
            List<String> next = new ArrayList<>();
            for (String prefix : previous) {
                for (String symbol : alphabet) {
                    next.add(prefix + symbol);
                }
            }
            strings.addAll(next);
            previous = next;
        }
        return strings;
    }

    private static List<SearchMatcher.Match> referenceWildcardMatches(String source, String query, boolean insensitive) {
        if (query.isEmpty()) {
            return List.of();
        }
        int[] text = source.codePoints().toArray();
        int[] pattern = query.codePoints().toArray();
        int[] offsets = new int[text.length + 1];
        for (int index = 0; index < text.length; index++) {
            offsets[index + 1] = offsets[index] + Character.charCount(text[index]);
        }
        if (insensitive) {
            for (int index = 0; index < text.length; index++) {
                text[index] = Character.toLowerCase(Character.toUpperCase(text[index]));
            }
            for (int index = 0; index < pattern.length; index++) {
                pattern[index] = Character.toLowerCase(Character.toUpperCase(pattern[index]));
            }
        }
        List<SearchMatcher.Match> matches = new ArrayList<>();
        for (int start = 0; start < text.length;) {
            int end = referenceWildcardEnd(text, pattern, start, 0);
            if (end < 0) {
                start++;
            } else if (end == start) {
                break;
            } else {
                matches.add(new SearchMatcher.Match(offsets[start], offsets[end]));
                start = end;
            }
        }
        return matches;
    }

    private static int referenceWildcardEnd(int[] text, int[] pattern, int sourceIndex, int queryIndex) {
        if (queryIndex == pattern.length) {
            return sourceIndex;
        }
        int token = pattern[queryIndex];
        if (token == '*') {
            int farthest = -1;
            for (int index = sourceIndex; index <= text.length; index++) {
                farthest = Math.max(farthest, referenceWildcardEnd(text, pattern, index, queryIndex + 1));
            }
            return farthest;
        }
        return sourceIndex < text.length && (token == '?' || token == text[sourceIndex])
            ? referenceWildcardEnd(text, pattern, sourceIndex + 1, queryIndex + 1)
            : -1;
    }
}
