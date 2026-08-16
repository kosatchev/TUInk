package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class TabSearchNavigationTest {
    @Test
    void emptyAndUnsuccessfulSearchesDoNotMutateAnyTab() {
        TabSession first = tab("alpha");
        TabSession second = tab("beta");
        first.engine().moveCursorTo(new Position(0, 2));
        second.engine().restoreSelection(new Position(0, 3), new Position(0, 1));
        List<TabSession> tabs = List.of(first, second);

        assertEquals(-1, TabSearch.findNextAcrossTabs(tabs, 0, "", SearchOptions.DEFAULT));
        assertEquals(-1, TabSearch.findNextAcrossTabs(tabs, 0, "missing", SearchOptions.DEFAULT));

        assertEquals(new Position(0, 2), first.state().getCursor());
        assertNull(first.state().getSelectionAnchor());
        assertEquals(new Position(0, 3), second.state().getCursor());
        assertEquals(new Position(0, 1), second.state().getSelectionAnchor());
    }

    @Test
    void crossTabProbeMutatesOnlyTheTabContainingTheCommittedMatch() {
        TabSession active = tab("no match");
        TabSession skipped = tab("still absent");
        TabSession found = tab("prefix NEEDLE suffix");
        skipped.engine().restoreSelection(new Position(0, 4), new Position(0, 1));

        assertEquals(
            2,
            TabSearch.findNextAcrossTabs(
                List.of(active, skipped, found),
                0,
                "needle",
                new SearchOptions(true, SearchOptions.MatchMode.SUBSTRING)
            )
        );

        assertEquals(new Position(0, 4), skipped.state().getCursor());
        assertEquals(new Position(0, 1), skipped.state().getSelectionAnchor());
        assertEquals(new Position(0, 13), found.state().getCursor());
        assertEquals(new Position(0, 7), found.state().getSelectionAnchor());
    }

    @Test
    void globalTraversalVisitsOtherTabsBeforeWrappingTheActiveTab() {
        TabSession activeNext = tab("head needle");
        TabSession next = tab("needle elsewhere");
        activeNext.engine().restoreSelection(new Position(0, 11), new Position(0, 5));

        assertEquals(
            1,
            TabSearch.findNextAcrossTabs(
                List.of(activeNext, next),
                0,
                "needle",
                SearchOptions.DEFAULT
            )
        );
        assertEquals(new Position(0, 6), next.state().getCursor());
        assertEquals(new Position(0, 0), next.state().getSelectionAnchor());

        TabSession activePrevious = tab("needle tail");
        TabSession previous = tab("other needle");
        activePrevious.engine().restoreSelection(new Position(0, 6), new Position(0, 0));

        assertEquals(
            1,
            TabSearch.findPreviousAcrossTabs(
                List.of(activePrevious, previous),
                0,
                "needle",
                SearchOptions.DEFAULT
            )
        );
        assertEquals(new Position(0, 12), previous.state().getCursor());
        assertEquals(new Position(0, 6), previous.state().getSelectionAnchor());
    }

    @Test
    void crossTabSelectionPreservesExactUtf16MatchInsideACombiningSequence() {
        TabSession active = tab("xxxx");
        TabSession found = tab("e\u0301");

        assertEquals(
            1,
            TabSearch.findNextAcrossTabs(
                List.of(active, found),
                0,
                "e",
                SearchOptions.DEFAULT
            )
        );

        assertEquals(new Position(0, 0), found.state().getSelectionAnchor());
        assertEquals(new Position(0, 1), found.state().getCursor());
    }

    private static TabSession tab(String line) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(line));
        EditorState state = new EditorState();
        EditorTextLayout layout = new LogicalEditorTextLayout(model);
        return new TabSession(model, state, layout, new EditorEngine(model, state, layout));
    }
}
