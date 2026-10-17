package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TabSession;
import dev.tuink.core.TextModel;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.ui.ViewportController.CursorPolicy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SearchCommandHandlerTest {
    @Test
    void nextRevealsACurrentLongMatchBeforeAdvancingToAnotherTab() {
        String query = "q".repeat(300);
        TabSession current = tab(query);
        TabSession other = tab(query);
        current.engine().restoreSelection(new Position(0, query.length()), new Position(0, 0));
        SearchCommandHandler.Context context = context(List.of(current, other), true, false, query, "");
        AtomicInteger reveals = new AtomicInteger();

        SearchCommandHandler.Result revealed = SearchCommandHandler.execute(TuinkCommand.SEARCH_NEXT, context,
            () -> { reveals.incrementAndGet(); return true; });

        assertEquals(1, reveals.get());
        assertEquals(0, revealed.activeTabIndex());
        assertEquals(1, revealed.matchedCount());
        assertFalse(revealed.documentChanged());
        assertEquals(CursorPolicy.FOLLOW_CURSOR, revealed.cursorPolicy());
        assertEquals(new Position(0, query.length()), current.state().getCursor());
        SearchCommandHandler.Result advanced = SearchCommandHandler.execute(TuinkCommand.SEARCH_NEXT, context, () -> false);
        assertEquals(1, advanced.activeTabIndex());
        assertEquals(1, advanced.matchedCount());
        assertFalse(advanced.documentChanged());
        assertEquals(new Position(0, query.length()), other.state().getCursor());
    }

    @Test
    void previousAndAnUnmatchedSelectionDoNotAskTheViewToRevealIt() {
        TabSession current = tab("needle other needle");
        current.engine().restoreSelection(new Position(0, 12), new Position(0, 7));
        var context = context(List.of(current), false, false, "needle", "");

        SearchCommandHandler.Result next = SearchCommandHandler.execute(TuinkCommand.SEARCH_NEXT, context,
            () -> { throw new AssertionError("selection does not match"); });
        assertEquals("Found next match", next.status());
        assertEquals(1, next.matchedCount());
        assertFalse(next.documentChanged());
        assertEquals(new Position(0, 19), current.state().getCursor());
        SearchCommandHandler.Result previous = SearchCommandHandler.execute(TuinkCommand.SEARCH_PREVIOUS, context,
            () -> { throw new AssertionError("Previous advances directly"); });
        assertEquals("Found previous match", previous.status());
        assertEquals(1, previous.matchedCount());
        assertFalse(previous.documentChanged());
        assertEquals(new Position(0, 6), current.state().getCursor());
    }

    @Test
    void navigationWrapsAcrossTabsInBothDirections() {
        TabSession first = tab("needle first");
        TabSession second = tab("second needle");
        List<TabSession> tabs = List.of(first, second);
        assertTrue(first.engine().findNext("needle"));
        var initial = context(tabs, true, false, "needle", "");

        SearchCommandHandler.Result next = SearchCommandHandler.execute(TuinkCommand.SEARCH_NEXT, initial, () -> false);

        assertEquals(1, next.activeTabIndex());
        assertEquals(new Position(0, 13), second.state().getCursor());
        var fromSecond = new SearchCommandHandler.Context(tabs, 1, false, initial.options(), "needle", "");
        SearchCommandHandler.Result wrapped = SearchCommandHandler.execute(TuinkCommand.SEARCH_NEXT, fromSecond, () -> false);
        assertEquals(0, wrapped.activeTabIndex());
        assertEquals(new Position(0, 6), first.state().getCursor());
        SearchCommandHandler.Result backward = SearchCommandHandler.execute(TuinkCommand.SEARCH_PREVIOUS, initial, () -> false);
        assertEquals(1, backward.activeTabIndex());
        assertEquals(new Position(0, 7), second.state().getSelectionAnchor());
    }

    @Test
    void replacementResultsDistinguishMatchedTextFromChangesAndRespectReadingMode() {
        TabSession current = tab("needle needle");
        long revision = current.model().revision();
        var unchanged = context(List.of(current), false, false, "needle", "needle");

        SearchCommandHandler.Result matched = SearchCommandHandler.execute(TuinkCommand.SEARCH_REPLACE_ALL, unchanged,
            () -> { throw new AssertionError("replacement does not reveal an old selection"); });

        assertEquals("Found 2 occurrence(s); text unchanged", matched.status());
        assertEquals(2, matched.matchedCount());
        assertFalse(matched.documentChanged());
        assertEquals(CursorPolicy.PRESERVE, matched.cursorPolicy());
        assertEquals(revision, current.model().revision());
        assertFalse(current.engine().undo());
        var reading = context(List.of(current), false, true, "needle", "replacement");
        var rejected = SearchCommandHandler.execute(TuinkCommand.SEARCH_REPLACE_ALL, reading, () -> false);
        assertEquals("Reading mode: replace is disabled", rejected.status());
        assertEquals(0, rejected.matchedCount());
        assertFalse(rejected.documentChanged());
        assertEquals(revision, current.model().revision());

        var changing = context(List.of(current), false, false, "needle", "\treplaced\t");
        SearchCommandHandler.Result replaced = SearchCommandHandler.execute(TuinkCommand.SEARCH_REPLACE_ALL, changing, () -> false);
        assertEquals(2, replaced.matchedCount());
        assertTrue(replaced.documentChanged());
        assertEquals(CursorPolicy.FOLLOW_CURSOR, replaced.cursorPolicy());
        assertEquals(InputActionDispatcher.StatusLevel.WARNING, replaced.statusLevel());
        assertEquals("\treplaced\t \treplaced\t", current.model().getLine(0));
        assertTrue(current.engine().undo());
        assertEquals("needle needle", current.model().getLine(0));
    }

    private static SearchCommandHandler.Context context(List<TabSession> tabs, boolean allTabs,
        boolean reading, String query, String replacement) {
        return new SearchCommandHandler.Context(tabs, 0, reading,
            new SearchController.SearchUiOptions(false, dev.tuink.core.SearchOptions.MatchMode.SUBSTRING, allTabs),
            query, replacement);
    }

    private static TabSession tab(String text) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(text));
        EditorState state = new EditorState();
        var layout = new TerminalEditorTextLayout(model);
        EditorEngine engine = new EditorEngine(model, state, layout, new EditorClipboard());
        return new TabSession(model, state, layout, engine);
    }
}
