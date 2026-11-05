package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.Position;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Replacement feedback follows document changes while unchanged single matches still advance. */
class SearchReplaceFeedbackIntegrationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedReplaceAllShowsAPersistentErrorAndKeepsTheDocument(boolean mouse) throws Exception {
        String original = "a".repeat(4096);
        try (EditorWindowFixture window = window(List.of(original))) {
            search(window, "a", "b".repeat(1_000_000));
            long revision = window.active().tabSession().model().revision();

            if (mouse) {
                int row = window.rowStartingWith("Replace:");
                int column = window.screenRow(row).indexOf("[Replace all]") + 2;
                assertTrue(column >= 2);
                window.pointer(PointerEvent.Kind.PRESS, row, column);
                assertEquals(window.host.styles.windows().notifyError(), window.notification.style());
                window.pointer(PointerEvent.Kind.RELEASE, row, column);
                window.pointer(PointerEvent.Kind.MOVE, row, column);
            } else {
                replace(window, SearchFocus.ALL_BUTTON);
            }

            assertTrue(window.notification.message.startsWith("Replacement result is too large"));
            assertEquals(window.host.styles.windows().notifyError(), window.notification.style());
            assertEquals(-1L, window.notification.nanosUntilExpire(System.nanoTime()));
            window.frame();
            assertEquals(window.host.styles.windows().notifyError(), window.notification.style());
            assertEquals(original, window.active().tabSession().model().getLine(0));
            assertEquals(revision, window.active().tabSession().model().revision());
            assertFalse(window.active().state().isDirty());
            assertFalse(window.active().tabSession().engine().undo());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void identicalReplaceAllPreservesSelectionManualViewportAndCleanHistory(boolean wrapped) throws Exception {
        List<String> lines = new ArrayList<>(Collections.nCopies(120, "alpha " + "x".repeat(240)));
        try (EditorWindowFixture window = window(lines)) {
            window.ui.wrapLinesSetting = wrapped;
            window.ui.wrapWordsSetting = false;
            search(window, "alpha", "alpha");
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            Position cursor = window.active().state().getCursor();
            Position anchor = window.active().state().getSelectionAnchor();
            long revision = window.active().tabSession().model().revision();
            window.wheel(PointerEvent.Kind.SCROLL_DOWN, 8);
            if (!wrapped) {
                window.wheel(PointerEvent.Kind.SCROLL_RIGHT, 8);
            }
            int top = window.active().state().getTopLine();
            int segment = window.active().state().getTopWrapSegment();
            int left = window.active().state().getLeftVisualColumn();
            assertTrue(top > 0 || segment > 0);
            assertTrue(wrapped || left > 0);

            replace(window, SearchFocus.ALL_BUTTON);

            assertEquals(revision, window.active().tabSession().model().revision());
            assertEquals(cursor, window.active().state().getCursor());
            assertEquals(anchor, window.active().state().getSelectionAnchor());
            assertEquals(top, window.active().state().getTopLine());
            assertEquals(segment, window.active().state().getTopWrapSegment());
            assertEquals(left, window.active().state().getLeftVisualColumn());
            assertEquals("Found 120 occurrence(s); text unchanged", window.notification.message);
            assertEquals(window.host.styles.windows().notifyInfo(), window.notification.style());
            assertFalse(window.active().state().isDirty());
            assertFalse(window.active().tabSession().engine().undo());
            assertEquals(lines, window.active().tabSession().model().snapshotLines());
        }
    }

    @Test
    void anIdenticalSingleReplacementAdvancesWithoutClaimingAChange() throws Exception {
        try (EditorWindowFixture window = window(List.of("alpha middle alpha"))) {
            search(window, "alpha", "alpha");
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(new Position(0, 5), window.active().state().getCursor());
            long revision = window.active().tabSession().model().revision();

            replace(window, SearchFocus.REPLACE_BUTTON);

            assertEquals("Match found; text unchanged", window.notification.message);
            assertEquals(new Position(0, 18), window.active().state().getCursor());
            assertEquals(new Position(0, 13), window.active().state().getSelectionAnchor());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertFalse(window.active().state().isDirty());
            assertFalse(window.active().tabSession().engine().undo());
        }
    }

    @Test
    void mixedIdenticalAndChangedMatchesReportTheMatchedCountAndUndoOnlyTheReplacement() throws Exception {
        try (EditorWindowFixture window = window(List.of("Alpha alpha ALPHA"))) {
            window.event(new TextInput("X"));
            search(window, "alpha", "alpha");

            replace(window, SearchFocus.ALL_BUTTON);

            assertEquals("Matched 3 occurrence(s); replacement applied. Undo: Esc, then Ctrl+Z", window.notification.message);
            assertEquals("Xalpha alpha alpha", window.active().tabSession().model().getLine(0));
            assertTrue(window.active().state().isDirty());
            assertTrue(window.active().tabSession().engine().undo());
            assertEquals("XAlpha alpha ALPHA", window.active().tabSession().model().getLine(0));
            assertTrue(window.active().state().isDirty(), "the earlier unrelated edit must remain");
            assertTrue(window.active().tabSession().engine().undo());
            assertEquals("Alpha alpha ALPHA", window.active().tabSession().model().getLine(0));
            assertFalse(window.active().state().isDirty());
            assertFalse(window.active().tabSession().engine().undo());
        }
    }

    @Test
    void replaceAllAdvertisesAnUndoSequenceThatWorksFromItsRetainedButtonFocus() throws Exception {
        try (EditorWindowFixture window = window(List.of("alpha alpha"))) {
            search(window, "alpha", "beta");
            replace(window, SearchFocus.ALL_BUTTON);
            assertEquals("Matched 2 occurrence(s); replacement applied. Undo: Esc, then Ctrl+Z",
                window.notification.message);
            assertEquals(SearchFocus.ALL_BUTTON, window.host.searchFocusManager().focused().orElseThrow());

            window.event(KeyStroke.of(KeyStroke.Key.Z, KeyStroke.Modifier.CTRL));
            assertEquals(List.of("beta beta"), window.active().tabSession().model().snapshotLines());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.event(KeyStroke.of(KeyStroke.Key.Z, KeyStroke.Modifier.CTRL));

            assertEquals(List.of("alpha alpha"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void identicalReplaceAllKeepsAnExistingRedoChainAndSavepoint() throws Exception {
        try (EditorWindowFixture window = window(List.of("alpha"))) {
            var engine = window.active().tabSession().engine();
            engine.insertInput("X");
            engine.insertNewline();
            assertTrue(engine.undo());
            engine.save();
            window.frame();
            search(window, "alpha", "alpha");
            long revision = window.active().tabSession().model().revision();

            replace(window, SearchFocus.ALL_BUTTON);

            assertEquals("Found 1 occurrence(s); text unchanged", window.notification.message);
            assertEquals(revision, window.active().tabSession().model().revision());
            assertFalse(window.active().state().isDirty());
            assertEquals("Xalpha", Files.readString(window.document));
            assertTrue(engine.redo());
            assertEquals(List.of("X", "alpha"), window.active().tabSession().model().snapshotLines());
            assertTrue(window.active().state().isDirty());
            assertTrue(engine.undo());
            assertEquals(List.of("Xalpha"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void anUnmatchedReplaceAllReportsAnInformationalNoOp() throws Exception {
        try (EditorWindowFixture window = window(List.of("alpha"))) {
            search(window, "absent", "replacement");
            long revision = window.active().tabSession().model().revision();

            replace(window, SearchFocus.ALL_BUTTON);

            assertEquals("Nothing to replace", window.notification.message);
            assertEquals(window.host.styles.windows().notifyInfo(), window.notification.style());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void allTabsOptionAndReplaceAllButtonHaveDistinctLabelsAndScopes() throws Exception {
        try (EditorWindowFixture window = window(List.of("alpha"))) {
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("alpha alpha"));
            search(window, "alpha", "beta");

            assertTrue(window.screenRow(window.height - 3).contains("[All tabs: ]"));
            assertTrue(window.screenRow(window.height - 2).contains("[Replace all]"));
            window.focusSearch(SearchFocus.ALL);
            window.event(new TextInput(" "));
            assertTrue(window.screenRow(window.height - 3).contains("[All tabs:x]"));
            assertTrue(window.screenRow(window.height - 1).contains("Find 3 in 2 tabs"));

            replace(window, SearchFocus.ALL_BUTTON);

            assertEquals(List.of("alpha"), first.tabSession().model().snapshotLines());
            assertFalse(first.state().isDirty());
            assertEquals(List.of("beta beta"), window.active().tabSession().model().snapshotLines());
            assertTrue(window.screenRow(window.height - 1).contains("Find 1 in 2 tabs"));
        }
    }

    private EditorWindowFixture window(List<String> lines) throws Exception {
        return new EditorWindowFixture(directory, false, lines);
    }

    private static void search(EditorWindowFixture window, String query, String replacement) {
        window.event(KeyStroke.of(KeyStroke.Key.F, KeyStroke.Modifier.CTRL));
        window.query.set(query);
        window.replacement.set(replacement);
        window.frame();
    }

    private static void replace(EditorWindowFixture window, SearchFocus target) {
        window.focusSearch(target);
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }
}
