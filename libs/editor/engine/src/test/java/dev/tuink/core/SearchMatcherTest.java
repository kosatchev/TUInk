package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchMatcherTest {
    private static final SearchOptions CASE_INSENSITIVE = new SearchOptions(
        true,
        SearchOptions.MatchMode.SUBSTRING
    );

    @Test
    void caseInsensitiveLiteralRangesAddressTheOriginalSource() {
        String source = "İx";
        SearchMatcher matcher = SearchMatcher.compile("x", CASE_INSENSITIVE);

        assertEquals(List.of(new SearchMatcher.Match(1, 2)), matcher.findAll(source));
        assertTrue(matcher.matchesRange(source, 1, 2));
        assertEquals("x", source.substring(1, 2));
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

    private static boolean splitsSurrogatePair(String source, int boundary) {
        return boundary > 0
            && boundary < source.length()
            && Character.isHighSurrogate(source.charAt(boundary - 1))
            && Character.isLowSurrogate(source.charAt(boundary));
    }
}
