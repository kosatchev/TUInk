package dev.tuink.core;

import dev.tuink.editor.search.SearchOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchWorkBudgetTest {
    private static final SearchOptions WILDCARD = new SearchOptions(false, SearchOptions.MatchMode.WILDCARD);

    @Test
    void resourceLimitSummariesKeepWorkAndMemoryFailuresDistinct() {
        var work = new SearchLimitException();
        var memory = SearchLimitException.memory();
        assertEquals("Search is too complex", work.summary());
        assertTrue(work.getMessage().contains("use literal search"));
        assertEquals("Not enough memory to search", memory.summary());
        assertTrue(memory.getMessage().contains("shorten the query"));
        assertSame(memory, SearchLimitException.memory(), "Memory failure reporting must reuse its exception");
    }

    @Test
    void missingMandatoryLiteralAvoidsTheLongQueryTimesSourceAutomaton() {
        String source = "a".repeat(4 * 1024 * 1024);
        SearchMatcher matcher = SearchMatcher.compile("a".repeat(50_000) + "?b", WILDCARD);

        // Zero extra automaton work proves this case uses the linear rejection
        // path, without relying on a deadline sensitive to machine load.
        assertEquals(0, SearchWorkBudget.run(0, () -> matcher.countMatches(source)));
    }

    @Test
    void mandatoryLiteralPrefilterUsesTheSameUnicodeCaseFoldingAsMatching() {
        String query = "𐐀".repeat(300) + "?İ";
        String source = "𐐨".repeat(301) + "i";
        SearchMatcher matcher = SearchMatcher.compile(query, new SearchOptions(true, SearchOptions.MatchMode.WILDCARD));

        assertEquals(List.of(new SearchMatcher.Match(0, source.length())), matcher.findAll(source));
        assertEquals(0, SearchWorkBudget.run(0, () -> matcher.countMatches("𐐨".repeat(400_000))));
    }

    @Test
    void denseCandidatesStopExplicitlyAndAnUnboundedSearchRemainsExact() {
        String source = "a".repeat(512) + "b";
        SearchMatcher matcher = SearchMatcher.compile("a".repeat(64) + "?b", WILDCARD);

        assertThrows(SearchLimitException.class, () -> SearchWorkBudget.run(128, () -> matcher.findAll(source)));
        assertEquals(List.of(new SearchMatcher.Match(source.length() - 66, source.length())), matcher.findAll(source));
    }

    @Test
    void budgetIsSharedAcrossScansAndNestedScopesCannotResetIt() {
        String source = "a".repeat(66);
        SearchMatcher matcher = SearchMatcher.compile("a".repeat(64) + "?a", WILDCARD);
        assertEquals(1, SearchWorkBudget.run(66, () -> matcher.countMatches(source)));

        assertThrows(SearchLimitException.class, () -> SearchWorkBudget.run(131, () -> {
            matcher.countMatches(source);
            return matcher.countMatches(source);
        }));
        assertThrows(SearchLimitException.class, () -> SearchWorkBudget.run(131,
            () -> SearchWorkBudget.run(Long.MAX_VALUE, () -> {
                matcher.countMatches(source);
                return matcher.countMatches(source);
            })));
        assertEquals(1, matcher.countMatches(source));
    }

    @Test
    void linearLiteralAndShortWildcardSearchesDoNotSpendTheNonlinearBudget() {
        String source = "a".repeat(10_000);

        assertEquals(source.length() / 3, SearchWorkBudget.run(0,
            () -> SearchMatcher.compile("a?a", WILDCARD).countMatches(source)));
        assertEquals(1, SearchWorkBudget.run(0,
            () -> SearchMatcher.compile("a".repeat(source.length()), WILDCARD).countMatches(source)));
        assertEquals(source.length(), SearchWorkBudget.run(0,
            () -> SearchMatcher.compile("?", WILDCARD).countMatches(source)));
    }
}
