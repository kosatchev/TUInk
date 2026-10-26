package dev.tuink.core;

import dev.tuink.editor.search.SearchOptions;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SearchOperationContextTest {
    private static final SearchOptions WILDCARD = new SearchOptions(false, SearchOptions.MatchMode.WILDCARD);
    private static final String QUERY = "a".repeat(64) + "?a";
    private static final String MATCH = "a".repeat(66);

    @Test
    void explicitContextSharesWorkAcrossScansAndChildren() {
        SearchMatcher matcher = SearchMatcher.compile(QUERY, WILDCARD);
        SearchOperationContext operation = SearchOperationContext.bounded(131);
        assertEquals(1, matcher.countMatches(MATCH, operation.child(Long.MAX_VALUE)));
        assertThrows(SearchLimitException.class,
            () -> matcher.countMatches(MATCH, operation.child(Long.MAX_VALUE)));

        assertThrows(SearchLimitException.class,
            () -> matcher.countMatches(MATCH, SearchOperationContext.bounded(66).child(65)));
        assertEquals(1, matcher.countMatches(MATCH, SearchOperationContext.bounded(66)));
    }

    @Test
    void starredWildcardSegmentsReceiveTheExplicitWorkAllowance() {
        SearchMatcher matcher = SearchMatcher.compile("*" + QUERY, WILDCARD);
        assertThrows(SearchLimitException.class,
            () -> matcher.countMatches(MATCH, SearchOperationContext.bounded(65)));
        assertEquals(1, matcher.countMatches(MATCH, SearchOperationContext.bounded(66)));
    }

    @Test
    void explicitContextSharesCompilationAllowanceAcrossDifferentQueriesAndChildren() {
        long firstCompilation = SearchMemoryBudget.FIXED_OVERHEAD + 12L * 6;
        long secondCompilation = SearchMemoryBudget.FIXED_OVERHEAD + 12L * 7;
        SearchOperationContext operation = SearchOperationContext.bounded(
            Long.MAX_VALUE, firstCompilation + secondCompilation - 1);

        assertEquals(1, SearchMatcher.compile("needle", SearchOptions.DEFAULT, operation.child(Long.MAX_VALUE))
            .countMatches("needle", operation));
        assertThrows(SearchLimitException.class,
            () -> SearchMatcher.compile("another", SearchOptions.DEFAULT, operation.child(Long.MAX_VALUE)));
        assertEquals(1, SearchMatcher.compile("another", SearchOptions.DEFAULT,
            SearchOperationContext.bounded(Long.MAX_VALUE, secondCompilation)).countMatches("another"));
    }

    @Test
    void compatibilityScopeRestoresItsParentAfterAnExceptionOrError() {
        SearchOperationContext outside = SearchWorkBudget.current();
        SearchMatcher matcher = SearchMatcher.compile(QUERY, WILDCARD);
        RuntimeException failure = new IllegalStateException("nested callback failed");
        AssertionError error = new AssertionError("nested callback failed");

        SearchWorkBudget.run(66, parent -> {
            assertSame(parent, SearchWorkBudget.current());
            assertSame(failure, assertThrows(IllegalStateException.class, () -> SearchWorkBudget.run(0, () -> {
                assertNotSame(parent, SearchWorkBudget.current());
                throw failure;
            })));
            assertSame(parent, SearchWorkBudget.current());
            assertSame(error, assertThrows(AssertionError.class, () -> SearchWorkBudget.run(0, () -> {
                throw error;
            })));
            assertSame(parent, SearchWorkBudget.current());
            assertEquals(1, matcher.countMatches(MATCH));
            assertThrows(SearchLimitException.class, () -> matcher.countMatches(MATCH, parent));
            return null;
        });

        assertSame(outside, SearchWorkBudget.current());
        assertEquals(1, matcher.countMatches(MATCH));
        assertThrows(IllegalArgumentException.class, () -> SearchWorkBudget.run(-1, () -> 0));
        assertSame(outside, SearchWorkBudget.current());
    }

    @Test
    void aThreadStartedInsideACompatibilityScopeReceivesNoMutableBudgetBinding() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<SearchOperationContext> background = new AtomicReference<>();
        AtomicReference<Thread> worker = new AtomicReference<>();

        SearchWorkBudget.run(0, context -> {
            Thread thread = new Thread(() -> {
                background.set(SearchWorkBudget.current());
                observed.countDown();
            });
            worker.set(thread);
            thread.start();
            try {
                assertTrue(observed.await(5, TimeUnit.SECONDS), "the worker must observe the live scope");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            assertSame(context, SearchWorkBudget.current());
            return null;
        });

        worker.get().join();
        assertSame(SearchOperationContext.unlimited(), background.get());
    }

    @Test
    void anExplicitNestedCallbackCannotResetTheLegacyOuterScope() {
        SearchMatcher matcher = SearchMatcher.compile(QUERY, WILDCARD);
        assertThrows(SearchLimitException.class, () -> SearchWorkBudget.run(131,
            () -> SearchWorkBudget.run(Long.MAX_VALUE, child -> {
                matcher.countMatches(MATCH, child);
                return matcher.countMatches(MATCH, child);
            })));
    }

    @Test
    void retainedNavigationUsesTheNextActionsContextAndRecoversAfterARefusal() {
        TabSession tab = tab(MATCH + MATCH + MATCH);
        SearchNavigation navigation = new SearchNavigation(tab.model());
        navigation.configure(QUERY, WILDCARD, SearchOperationContext.bounded(0));
        assertEquals(new SearchMatcher.Match(0, 66),
            navigation.firstAtOrAfter(0, 0, SearchOperationContext.bounded(66)));
        assertEquals(new SearchMatcher.Match(66, 132),
            navigation.firstAtOrAfter(0, 66, SearchOperationContext.bounded(66)));
        assertThrows(SearchLimitException.class,
            () -> navigation.firstAtOrAfter(0, 132, SearchOperationContext.bounded(65)));
        assertEquals(new SearchMatcher.Match(132, 198),
            navigation.firstAtOrAfter(0, 132, SearchOperationContext.bounded(198)));
    }

    @Test
    void engineNavigationAndReplacementAcceptContextsWithoutAnAmbientScope() {
        TabSession tab = tab(MATCH + MATCH);
        assertTrue(tab.engine().findNext(QUERY, WILDCARD, SearchOperationContext.bounded(66)));
        assertEquals(new Position(0, 0), tab.state().getSelectionAnchor());
        assertTrue(tab.engine().findNext(QUERY, WILDCARD, SearchOperationContext.bounded(66)));
        assertEquals(new Position(0, 66), tab.state().getSelectionAnchor());
        assertTrue(tab.engine().findPrevious(QUERY, WILDCARD, SearchOperationContext.bounded(0)));
        assertEquals(new Position(0, 0), tab.state().getSelectionAnchor());
        assertTrue(tab.engine().replaceCurrentOrNext(QUERY, "changed", WILDCARD, SearchOperationContext.bounded(0)));
        assertEquals("changed" + MATCH, tab.model().getLine(0));
        assertTrue(tab.engine().undo());
        assertEquals(MATCH + MATCH, tab.model().getLine(0));
    }

    @Test
    void countAndTabMarkerOperationsShareOneAllowanceAcrossDocuments() {
        List<TabSession> tabs = List.of(tab(MATCH), tab(MATCH));
        assertThrows(SearchLimitException.class,
            () -> TabSearch.countMatches(tabs, QUERY, WILDCARD, SearchOperationContext.bounded(131)));
        assertThrows(SearchLimitException.class,
            () -> TabSearch.findMatchTabs(tabs, QUERY, WILDCARD, SearchOperationContext.bounded(131)));
        assertEquals(2, TabSearch.countMatches(tabs, QUERY, WILDCARD, SearchOperationContext.bounded(132)));
        assertArrayEquals(new int[] {1, 1},
            TabSearch.findMatchTabs(tabs, QUERY, WILDCARD, SearchOperationContext.bounded(132)));
    }

    @Test
    void crossTabNavigationDoesNotResetWorkOrPublishASelectionAfterRefusal() {
        String query = "a".repeat(64) + "?b";
        for (boolean forward : new boolean[] {true, false}) {
            TabSession first = tab(MATCH);
            TabSession second = tab("a".repeat(65) + "b");
            if (!forward) first.engine().moveCursorTo(new Position(0, MATCH.length()));
            var firstView = first.state().viewMemento();
            var secondView = second.state().viewMemento();
            List<TabSession> tabs = List.of(first, second);

            assertThrows(SearchLimitException.class, () -> {
                SearchOperationContext operation = SearchOperationContext.bounded(131);
                if (forward) TabSearch.findNextAcrossTabs(tabs, 0, query, WILDCARD, operation);
                else TabSearch.findPreviousAcrossTabs(tabs, 0, query, WILDCARD, operation);
            });

            assertEquals(firstView, first.state().viewMemento());
            assertEquals(secondView, second.state().viewMemento());
            int found = forward
                ? TabSearch.findNextAcrossTabs(tabs, 0, query, WILDCARD, SearchOperationContext.bounded(132))
                : TabSearch.findPreviousAcrossTabs(tabs, 0, query, WILDCARD, SearchOperationContext.bounded(132));
            assertEquals(1, found);
            assertEquals(new Position(0, 0), second.state().getSelectionAnchor());
            assertEquals(new Position(0, 66), second.state().getCursor());
        }
    }

    @Test
    void replacementCountingAndBuildingUseOneAllowanceBeforeAnyPublication() {
        TabSession tab = tab(MATCH);
        tab.engine().restoreSelection(new Position(0, 3), new Position(0, 1));
        var view = tab.state().viewMemento();
        long revision = tab.model().revision();

        assertThrows(SearchLimitException.class, () -> tab.engine().replaceAll(
            QUERY, "changed", WILDCARD, SearchOperationContext.bounded(66)));

        assertEquals(MATCH, tab.model().getLine(0));
        assertEquals(revision, tab.model().revision());
        assertEquals(view, tab.state().viewMemento());
        assertFalse(tab.state().isDirty());
        assertFalse(tab.engine().undo());
        assertFalse(tab.engine().redo());
        assertEquals(1, tab.engine().replaceAll(QUERY, "changed", WILDCARD, SearchOperationContext.bounded(132)));
        assertEquals("changed", tab.model().getLine(0));
        assertTrue(tab.engine().undo());
        assertEquals(MATCH, tab.model().getLine(0));
    }

    private static TabSession tab(String text) {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, text);
        TextModel model = new TextModel(buffer);
        EditorState state = new EditorState();
        EditorTextLayout layout = new LogicalEditorTextLayout(model);
        EditorEngine engine = new EditorEngine(model, state, layout);
        return new TabSession(model, state, layout, engine);
    }
}
