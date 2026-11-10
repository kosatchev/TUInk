package dev.tuink.core;

import dev.tuink.editor.search.SearchOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchNavigationTest {
    private static final SearchOptions WILDCARD = new SearchOptions(false, SearchOptions.MatchMode.WILDCARD);

    @Test
    void sequentialNavigationAndSelectionChecksScanEachLiteralCodePointOnce() {
        String source = "ab ".repeat(20_000);
        SearchNavigation navigation = navigation(source, "ab", SearchOptions.DEFAULT);

        for (int start = 0; start < source.length(); start += 3) {
            assertEquals(new SearchMatcher.Match(start, start + 2), navigation.firstAtOrAfter(0, start));
            assertTrue(navigation.matchesRange(0, start, start + 2));
        }
        assertNull(navigation.firstAtOrAfter(0, source.length()));
        assertEquals(source.length(), navigation.scanSteps());
        assertTrue(navigation.retainedResults() <= SearchNavigation.RESULT_CAPACITY);
        assertTrue(navigation.retainedCheckpoints() <= SearchNavigation.CHECKPOINT_CAPACITY);
    }

    @Test
    void questionWildcardContinuesItsOwnAutomatonAcrossSupplementaryCodePoints() {
        String source = "a🙂b ".repeat(20_000);
        SearchNavigation navigation = navigation(source, "a?b", WILDCARD);

        for (int start = 0; start < source.length(); start += 5) {
            assertEquals(new SearchMatcher.Match(start, start + 4), navigation.firstAtOrAfter(0, start));
            assertTrue(navigation.matchesRange(0, start, start + 4));
        }
        assertNull(navigation.firstAtOrAfter(0, source.length()));
        assertEquals(source.codePointCount(0, source.length()), navigation.scanSteps());
    }

    @Test
    void arbitraryOffsetsAndDirectionChangesKeepCanonicalNonOverlappingMatches() {
        List<String> sources = List.of("aaaaa", "ba-a-a a-a-a", "𝔸x x e\u0301 e", "a🙂b a1b a2b tail", "İx Ix ıx ix");
        List<String> queries = List.of("aa", "a-a", "x", "e", "?", "??", "a?b", "?x", "a*b", "*", "İ?");
        for (String source : sources) {
            for (String query : queries) {
                for (SearchOptions.MatchMode mode : SearchOptions.MatchMode.values()) {
                    String pattern = mode == SearchOptions.MatchMode.REGEX
                        ? query.replace("*", ".*").replace("?", ".") : query;
                    SearchOptions options = new SearchOptions(true, mode);
                    SearchNavigation navigation = navigation(source, pattern, options);
                    List<SearchMatcher.Match> expected = SearchMatcher.compile(pattern, options).findAll(source);
                    for (int offset = source.length() + 1; offset >= -1; offset--) {
                        assertNavigation(expected, navigation, offset);
                        assertNavigation(expected, navigation, source.length() - offset);
                    }
                    for (SearchMatcher.Match match : expected) {
                        assertTrue(navigation.matchesRange(0, match.startUtf16(), match.endUtf16()));
                    }
                }
            }
        }
        SearchNavigation overlapping = navigation("aaaaa", "aa", SearchOptions.DEFAULT);
        assertEquals(new SearchMatcher.Match(2, 4), overlapping.firstAtOrAfter(0, 1));
        assertFalse(overlapping.matchesRange(0, 1, 3));
    }

    @Test
    void backwardBlocksAndThinnedCheckpointsStayBoundedAndExact() {
        String source = "aa ".repeat(100_000);
        SearchNavigation navigation = navigation(source, "aa", SearchOptions.DEFAULT);
        // Fill more than the checkpoint capacity, including its thinning path.
        assertEquals(new SearchMatcher.Match(source.length() - 3, source.length() - 1),
            navigation.lastBefore(0, source.length()));
        assertTrue(navigation.retainedCheckpoints() <= SearchNavigation.CHECKPOINT_CAPACITY);

        long beforeBlock = navigation.scanSteps();
        for (int index = 1; index < SearchNavigation.RESULT_CAPACITY; index++) {
            int start = source.length() - 3 * (index + 1);
            assertEquals(new SearchMatcher.Match(start, start + 2), navigation.lastBefore(0, start + 3));
        }
        assertEquals(beforeBlock, navigation.scanSteps(), "recent backward results must use the retained block");

        for (int start = source.length() - 3 * (SearchNavigation.RESULT_CAPACITY + 1); start >= 0; start -= 3) {
            assertEquals(new SearchMatcher.Match(start, start + 2), navigation.lastBefore(0, start + 3));
        }
        assertNull(navigation.lastBefore(0, 0));
        assertTrue(navigation.retainedResults() <= SearchNavigation.RESULT_CAPACITY);
        assertTrue(navigation.retainedCheckpoints() <= SearchNavigation.CHECKPOINT_CAPACITY);
        assertEquals(new SearchMatcher.Match(0, 2), navigation.firstAtOrAfter(0, 0));
    }

    @Test
    void unchangedQueryReusesCompilationAndEditsInvalidateNavigation() {
        TextModel model = model("aaaaa");
        SearchNavigation navigation = new SearchNavigation(model);
        SearchMatcher compiled = navigation.configure("aa", SearchOptions.DEFAULT);
        assertEquals(new SearchMatcher.Match(2, 4), navigation.firstAtOrAfter(0, 2));
        assertSame(compiled, navigation.configure("aa", null));

        model.replaceLines(List.of("baaaa"));
        assertEquals(new SearchMatcher.Match(1, 3), navigation.firstAtOrAfter(0, 0));
        assertFalse(navigation.matchesRange(0, 2, 4));
        model.replaceLines(List.of("ab", "aA"));
        navigation.configure("aa", new SearchOptions(true, SearchOptions.MatchMode.SUBSTRING));
        assertNull(navigation.firstAtOrAfter(0, 0));
        assertEquals(new SearchMatcher.Match(0, 2), navigation.firstAtOrAfter(1, 0));
        navigation.configure("ab", SearchOptions.DEFAULT);
        assertEquals(new SearchMatcher.Match(0, 2), navigation.firstAtOrAfter(0, 0));
    }

    @Test
    void refusedWildcardWorkDiscardsPartiallyAdvancedState() {
        String source = "a".repeat(512) + "b";
        SearchNavigation navigation = navigation(source, "a".repeat(64) + "?b", WILDCARD);
        assertThrows(SearchLimitException.class, () -> SearchWorkBudget.run(1, () -> navigation.firstAtOrAfter(0, 0)));
        assertEquals(new SearchMatcher.Match(source.length() - 66, source.length()), navigation.firstAtOrAfter(0, 0));
    }

    @Test
    void engineAndCrossTabCommandsReuseNavigationWithoutRescanningTheSelection() {
        TextModel model = model("aa ".repeat(2_000));
        EditorState state = new EditorState();
        EditorTextLayout layout = new LogicalEditorTextLayout(model);
        EditorEngine engine = new EditorEngine(model, state, layout);
        TabSession tab = new TabSession(model, state, layout, engine);
        for (int start = 0; start < model.getLine(0).length(); start += 3) {
            if (start % 2 == 0) assertTrue(engine.findNext("aa"));
            else assertEquals(0, TabSearch.findNextAcrossTabs(List.of(tab), 0, "aa", SearchOptions.DEFAULT));
            assertEquals(new Position(0, start), state.getSelectionAnchor());
        }
        SearchNavigation navigation = engine.searchNavigation("aa", SearchOptions.DEFAULT);
        assertEquals(model.getLine(0).length() - 1, navigation.scanSteps());
        assertEquals(0, TabSearch.findNextAcrossTabs(List.of(tab), 0, "aa", SearchOptions.DEFAULT));
        assertEquals(new Position(0, 0), state.getSelectionAnchor());
    }

    private static void assertNavigation(List<SearchMatcher.Match> expected, SearchNavigation navigation, int offset) {
        SearchMatcher.Match first = null;
        SearchMatcher.Match last = null;
        for (SearchMatcher.Match match : expected) {
            if (first == null && match.startUtf16() >= offset) first = match;
            if (match.startUtf16() < offset) last = match;
        }
        assertEquals(first, navigation.firstAtOrAfter(0, offset), "forward from " + offset);
        assertEquals(last, navigation.lastBefore(0, offset), "backward before " + offset);
    }

    private static SearchNavigation navigation(String source, String query, SearchOptions options) {
        SearchNavigation navigation = new SearchNavigation(model(source));
        navigation.configure(query, options);
        return navigation;
    }

    private static TextModel model(String source) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(source));
        return model;
    }
}
