package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuink.editor.search.SearchOptions;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RegexSearchTest {
    private static final SearchOptions REGEX = new SearchOptions(false, SearchOptions.MatchMode.REGEX);

    @Test void theThreeModesGiveSpecialCharactersTheirOwnMeaning() {
        assertEquals(1, SearchMatcher.compile("a.b", SearchOptions.DEFAULT).countMatches("a.b axb"));
        assertEquals(2, SearchMatcher.compile("a?b", new SearchOptions(false, SearchOptions.MatchMode.WILDCARD)).countMatches("a.b axb"));
        assertEquals(2, SearchMatcher.compile("a.b", REGEX).countMatches("a.b axb"));
    }

    @Test void supportsClassesQuantifiersNamedGroupsLookaroundsAndBackreferences() {
        var matcher = SearchMatcher.compile("(?<=id=)(?<n>\\d+)-\\k<n>(?=;)", REGEX);
        assertEquals(List.of(new SearchMatcher.Match(3, 8)), matcher.findAll("id=12-12; id=12-13;"));
        assertTrue(matcher.matchesRange("id=12-12;", 3, 8));
    }

    @Test void unicodeCaseAndWholeWordAreIndependentOfPatternMode() {
        assertEquals(2, SearchMatcher.compile("привет", new SearchOptions(true, SearchOptions.MatchMode.REGEX)).countMatches("ПРИВЕТ привет"));
        for (var mode : SearchOptions.MatchMode.values()) {
            String query = mode == SearchOptions.MatchMode.WILDCARD ? "c?t" : mode == SearchOptions.MatchMode.REGEX ? "c.t" : "cat";
            assertEquals(List.of(new SearchMatcher.Match(5, 8)), SearchMatcher.compile(query, new SearchOptions(false, mode, true))
                .findAll("xcat cat catx"), mode.name());
        }
    }

    @Test void emptyMatchesIncludeEmptyLinesAndNeverSplitSupplementaryCharacters() {
        assertEquals(List.of(new SearchMatcher.Match(0, 0)), SearchMatcher.compile("^$", REGEX).findAll(""));
        assertEquals(List.of(new SearchMatcher.Match(0, 0), new SearchMatcher.Match(2, 2)),
            SearchMatcher.compile("(?=.)|$", REGEX).findAll("🙂"));
    }

    @Test void resumableCursorsPreserveCanonicalAnchorsAndEmptyMatches() {
        var matcher = SearchMatcher.compile("\\G[a-z],?", REGEX);
        assertEquals(new SearchMatcher.Match(2, 4), matcher.cursor("a,b,c", 2).next());
        var empty = SearchMatcher.compile("a|$", REGEX);
        assertEquals(new SearchMatcher.Match(1, 1), empty.cursor("a", 1).next());
        assertNull(empty.cursor("a", 1, true).next());
    }

    @Test void navigationCountingAndRangeValidationUseTheSameSequence() {
        String source = "🙂 a12 ab b32";
        var matcher = SearchMatcher.compile("[ab]\\d+|(?=b)|$", REGEX);
        var all = matcher.findAll(source);
        assertEquals(all.size(), matcher.countMatches(source));
        for (var match : all) assertTrue(matcher.matchesRange(source, match.startUtf16(), match.endUtf16()));
        for (int offset = 0; offset <= source.length(); offset++) {
            int from = offset;
            assertEquals(all.stream().filter(match -> match.startUtf16() >= from).findFirst().orElse(null), matcher.findFirstAtOrAfter(source, offset));
            assertEquals(all.stream().filter(match -> match.startUtf16() < from).reduce((first, last) -> last).orElse(null), matcher.findLastBefore(source, offset));
        }
    }

    @Test void invalidAndOversizedExpressionsReportTypedFailures() {
        assertThrows(SearchPatternException.class, () -> SearchMatcher.compile("[", REGEX));
        assertThrows(SearchPatternException.class, () -> SearchMatcher.compile("a".repeat(4097), REGEX));
    }

    @Test void pathologicalBacktrackingCannotExceedTheSharedWorkBudget() {
        var context = SearchOperationContext.bounded(3000);
        var matcher = SearchMatcher.compile("(a+)+$", REGEX, context);
        assertThrows(SearchLimitException.class, () -> matcher.findAll("a".repeat(5000) + "!", context));
    }

    @Test void cancellationIsCheckedDuringRegexEvaluation() {
        AtomicInteger checks = new AtomicInteger();
        var context = SearchOperationContext.cancellable(1_000_000, () -> checks.incrementAndGet() > 100);
        var matcher = SearchMatcher.compile("(a+)+$", REGEX, context);
        assertThrows(CancellationException.class, () -> matcher.findAll("a".repeat(5000) + "!", context));
    }
}
