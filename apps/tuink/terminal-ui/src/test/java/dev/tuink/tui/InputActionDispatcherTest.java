package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TabSession;
import dev.tuink.core.TextBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.render.EditorViewNavigation;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.ui.ViewportController.CursorPolicy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InputActionDispatcherTest {
    @Test
    void rejectedKeyboardEditsReportAnErrorAndKeepTheDocumentViewUsable() {
        for (TuinkCommand command : List.of(TuinkCommand.EDIT_INSERT_TAB, TuinkCommand.EDIT_INSERT_NEWLINE,
            TuinkCommand.EDIT_BACKSPACE, TuinkCommand.EDIT_DELETE_FORWARD, TuinkCommand.EDIT_CUT)) {
            AtomicBoolean refuseStorage = new AtomicBoolean();
            TextModel model = new TextModel(new RefusingBuffer(refuseStorage));
            model.replaceLines(List.of("first", "second"));
            EditorState state = new EditorState();
            EditorEngine engine = new EditorEngine(model, state, new TerminalEditorTextLayout(model));
            engine.setGutterDiffMode(GutterDiffMode.DISABLED);
            engine.restoreSelection(new Position(0, 3), new Position(0, 0));
            var before = state.viewMemento();
            var metadata = model.fileMetadata();
            long revision = model.revision();
            var context = TuiSessionTestSupport.context(engine, state, null,
                ignored -> {}, 3, 20, dev.tuink.tui.TuiSessionTestSupport.navigation(engine));
            refuseStorage.set(true);

            var result = InputActionDispatcher.dispatch(command, new UiSessionState(), context);

            assertTrue(result.handled(), command.name());
            assertEquals(InputActionDispatcher.StatusLevel.ERROR, result.statusLevel(), command.name());
            assertTrue(result.statusMessage().contains("available memory"), command.name());
            assertEquals(CursorPolicy.PRESERVE, result.viewportPolicy(), command.name());
            assertEquals(List.of("first", "second"), model.snapshotLines(), command.name());
            assertEquals(before, state.viewMemento(), command.name());
            assertEquals(metadata, model.fileMetadata(), command.name());
            assertEquals(revision, model.revision(), command.name());
            assertFalse(state.isDirty(), command.name());
            refuseStorage.set(false);
            assertFalse(engine.undo(), command.name());
            engine.insertInput("X");
            assertEquals("Xst", model.getLine(0), command.name());
        }
    }

    /** Simulates failure to prepare replacement storage without exhausting the test JVM. */
    private static final class RefusingBuffer extends SimpleLineBuffer {
        private final AtomicBoolean refuse;

        private RefusingBuffer(AtomicBoolean refuse) {
            this.refuse = refuse;
        }

        @Override
        public TextBuffer createEmptySibling() {
            if (refuse.get()) throw new OutOfMemoryError("replacement storage refused");
            return new RefusingBuffer(refuse);
        }
    }

    @Test
    void selectingDownThroughTheTerminalSessionClampsAGraphemeInteriorAnchor() {
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        tab.model().replaceLines(List.of("a\u0301", "next"));
        tab.state().setCursor(new Position(0, 1));
        var session = TuiDocumentSession.adopt(tab);
        var navigation = new EditorViewNavigation(tab.engine(), session::navigate);
        var context = new InputActionDispatcher.DispatchContext(tab.engine(), tab.state(), null,
            ignored -> {}, 3, 20, navigation, session, TestSurfaces.source(false));

        var result = InputActionDispatcher.dispatch(TuinkCommand.CURSOR_SELECT_DOWN, new UiSessionState(), context);

        assertTrue(result.handled());
        assertEquals(new Position(0, 0), tab.state().getSelectionAnchor());
        assertEquals(1, tab.state().getCursor().line());
        assertEquals(List.of("a\u0301", "next"), tab.model().snapshotLines());
    }

    @Test
    void successiveDispatchContextsReuseTheViewColumnAndRejectedEditsDoNotResetIt() {
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        tab.engine().insertInput("0123456789\nx\nabcdefghij");
        tab.engine().moveCursorTo(new Position(0, 8));
        var navigation = dev.tuink.tui.TuiSessionTestSupport.navigation(tab.engine());
        UiSessionState ui = new UiSessionState();
        var firstFrame = TuiSessionTestSupport.context(tab.engine(), tab.state(), null,
            ignored -> {}, 3, 20, navigation);
        InputActionDispatcher.dispatch(TuinkCommand.CURSOR_DOWN, ui, firstFrame);
        assertEquals(new Position(1, 1), tab.state().getCursor());
        ui.readingModeSetting = true;
        InputActionDispatcher.dispatch(TuinkCommand.EDIT_CUT, ui, firstFrame);

        var nextFrame = TuiSessionTestSupport.context(tab.engine(), tab.state(), null,
            ignored -> {}, 3, 40, navigation);
        InputActionDispatcher.dispatch(TuinkCommand.CURSOR_DOWN, ui, nextFrame);

        assertEquals(new Position(2, 8), tab.state().getCursor());
        assertEquals(List.of("0123456789", "x", "abcdefghij"), tab.model().snapshotLines());
    }

    @Test
    void verticalCommandsUseTheCurrentWrappedViewport() {
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        tab.engine().insertInput("abcdefghijklmnopqrstuvwxyz1234");
        tab.engine().moveHome();
        UiSessionState ui = new UiSessionState();
        ui.wrapLinesSetting = true;
        InputActionDispatcher.DispatchContext context = TuiSessionTestSupport.context(
            tab.engine(), tab.state(), null, ignored -> {}, 3, 4, dev.tuink.tui.TuiSessionTestSupport.navigation(tab.engine())
        );

        InputActionDispatcher.dispatch(TuinkCommand.CURSOR_DOWN, ui, context);
        assertEquals(new Position(0, 4), tab.state().getCursor());
        InputActionDispatcher.dispatch(TuinkCommand.CURSOR_SELECT_DOWN, ui, context);
        assertEquals(new Position(0, 8), tab.state().getCursor());
        assertEquals(new Position(0, 4), tab.state().getSelectionAnchor());
        InputActionDispatcher.dispatch(TuinkCommand.CURSOR_UP, ui, context);
        assertEquals(new Position(0, 4), tab.state().getCursor());
        assertFalse(tab.engine().hasSelection());
        InputActionDispatcher.dispatch(TuinkCommand.CURSOR_PAGE_DOWN, ui, context);
        assertEquals(new Position(0, 16), tab.state().getCursor());
        InputActionDispatcher.dispatch(TuinkCommand.CURSOR_PAGE_UP, ui, context);
        assertEquals(new Position(0, 4), tab.state().getCursor());

        ui.wrapLinesSetting = false;
        InputActionDispatcher.dispatch(TuinkCommand.CURSOR_DOWN, ui, context);
        assertEquals(new Position(0, 4), tab.state().getCursor());
    }

    @Test
    void readingModeRejectsEditingCommandsBeforeTheyReachTheEngine() {
        UiSessionState ui = new UiSessionState();
        ui.readingModeSetting = true;
        List<TuinkCommand> edits = List.of(
            TuinkCommand.EDIT_CUT,
            TuinkCommand.EDIT_PASTE,
            TuinkCommand.EDIT_UNDO,
            TuinkCommand.EDIT_REDO,
            TuinkCommand.EDIT_INSERT_TAB,
            TuinkCommand.EDIT_INSERT_NEWLINE,
            TuinkCommand.EDIT_BACKSPACE,
            TuinkCommand.EDIT_DELETE_FORWARD,
            TuinkCommand.EDIT_TOGGLE_LINE_COMMENT,
            TuinkCommand.SEARCH_REPLACE,
            TuinkCommand.SEARCH_REPLACE_ALL
        );

        for (TuinkCommand command : edits) {
            InputActionDispatcher.DispatchResult result = InputActionDispatcher.dispatch(
                command,
                ui,
                context(ignored -> {})
            );

            assertTrue(result.handled(), command.toString());
            assertEquals(CursorPolicy.PRESERVE, result.viewportPolicy(), command.toString());
            assertEquals("Reading mode: editing is disabled", result.statusMessage(), command.toString());
        }
    }

    @Test
    void readingModePreservesTextAndHistoryUntilEditingIsEnabledAgain() {
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        UiSessionState ui = new UiSessionState();
        InputActionDispatcher.DispatchContext context = TuiSessionTestSupport.context(
            tab.engine(), tab.state(), null, ignored -> {}, 24, 80, dev.tuink.tui.TuiSessionTestSupport.navigation(tab.engine())
        );
        tab.engine().insertInput("abc");
        long editedRevision = tab.model().revision();
        ui.readingModeSetting = true;

        InputActionDispatcher.dispatch(TuinkCommand.EDIT_UNDO, ui, context);

        assertEquals("abc", tab.model().getLine(0));
        assertEquals(editedRevision, tab.model().revision());
        assertTrue(tab.state().isDirty());

        ui.readingModeSetting = false;
        InputActionDispatcher.dispatch(TuinkCommand.EDIT_UNDO, ui, context);
        assertEquals("", tab.model().getLine(0));
        assertFalse(tab.state().isDirty());
        long undoneRevision = tab.model().revision();
        ui.readingModeSetting = true;

        InputActionDispatcher.dispatch(TuinkCommand.EDIT_REDO, ui, context);

        assertEquals("", tab.model().getLine(0));
        assertEquals(undoneRevision, tab.model().revision());
        assertFalse(tab.state().isDirty());

        ui.readingModeSetting = false;
        InputActionDispatcher.dispatch(TuinkCommand.EDIT_REDO, ui, context);
        assertEquals("abc", tab.model().getLine(0));
        assertTrue(tab.state().isDirty());
    }

    @Test
    void saveCommandsRemainAvailableForTheDocumentWorkflow() {
        UiSessionState ui = new UiSessionState();

        assertFalse(InputActionDispatcher.dispatch(TuinkCommand.DOCUMENT_SAVE, ui, context(ignored -> {})).handled());
        assertFalse(InputActionDispatcher.dispatch(TuinkCommand.DOCUMENT_SAVE_AS, ui, context(ignored -> {})).handled());
    }

    @Test
    void unavailableMouseModePreservesPreferenceAndReportsTheFallback() {
        UiSessionState ui = new UiSessionState();
        ui.mouseModeSetting = true;
        AtomicBoolean controllerCalled = new AtomicBoolean();

        InputActionDispatcher.DispatchResult result = InputActionDispatcher.dispatchUi(
            TuinkCommand.SETTINGS_TOGGLE_MOUSE, ui, TestSurfaces.source(ui).withRuntime(false, true), null,
            ignored -> controllerCalled.set(true), () -> {});

        assertTrue(result.handled());
        assertEquals("Mouse mode is unavailable in this terminal", result.statusMessage());
        assertEquals(InputActionDispatcher.StatusLevel.WARNING, result.statusLevel());
        assertTrue(ui.mouseModeSetting);
        assertFalse(controllerCalled.get());
    }

    @Test
    void availableMouseModeStillTogglesThePreferenceAndTerminalController() {
        UiSessionState ui = new UiSessionState();
        ui.mouseModeSetting = true;
        AtomicBoolean enabled = new AtomicBoolean(true);

        InputActionDispatcher.DispatchResult result = InputActionDispatcher.dispatch(
            TuinkCommand.SETTINGS_TOGGLE_MOUSE,
            ui,
            context(enabled::set)
        );

        assertTrue(result.handled());
        assertEquals(InputActionDispatcher.StatusLevel.ERROR, result.statusLevel());
        assertFalse(ui.mouseModeSetting);
        assertFalse(enabled.get());
    }

    @Test
    void viewportPolicyComesFromExecutedMutationRatherThanCommandName() {
        EditorClipboard clipboard = new EditorClipboard();
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, clipboard);
        UiSessionState ui = new UiSessionState();
        var context = TuiSessionTestSupport.context(tab.engine(), tab.state(), null,
            ignored -> {}, 3, 8, dev.tuink.tui.TuiSessionTestSupport.navigation(tab.engine()));
        for (TuinkCommand noEffect : List.of(TuinkCommand.EDIT_PASTE, TuinkCommand.EDIT_UNDO,
            TuinkCommand.EDIT_REDO, TuinkCommand.EDIT_DELETE_FORWARD, TuinkCommand.EDIT_BACKSPACE,
            TuinkCommand.EDIT_COPY)) {
            assertEquals(CursorPolicy.PRESERVE,
                InputActionDispatcher.dispatch(noEffect, ui, context).viewportPolicy(), noEffect.name());
        }
        clipboard.setText("inserted");
        assertEquals(CursorPolicy.FOLLOW_CURSOR,
            InputActionDispatcher.dispatch(TuinkCommand.EDIT_PASTE, ui, context).viewportPolicy());
        assertEquals("inserted", tab.model().getLine(0));
        assertEquals(CursorPolicy.PRESERVE,
            InputActionDispatcher.dispatch(TuinkCommand.EDIT_COPY, ui, context).viewportPolicy());
        assertEquals(CursorPolicy.FOLLOW_CURSOR,
            InputActionDispatcher.dispatch(TuinkCommand.EDIT_UNDO, ui, context).viewportPolicy());
    }

    @Test
    void pageWithSelectionReturnsTheVerticalPanEffectAfterNavigation() {
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        tab.engine().insertInput("abcdefghijklmnopqrstuvwxyz1234");
        tab.engine().moveHome();
        UiSessionState ui = new UiSessionState();
        ui.wrapLinesSetting = true;
        var context = TuiSessionTestSupport.context(tab.engine(), tab.state(), null,
            ignored -> {}, 3, 4, dev.tuink.tui.TuiSessionTestSupport.navigation(tab.engine()));
        InputActionDispatcher.dispatch(TuinkCommand.CURSOR_SELECT_DOWN, ui, context);
        Position cursor = tab.state().getCursor();

        var result = InputActionDispatcher.dispatch(TuinkCommand.CURSOR_PAGE_DOWN, ui, context);

        assertEquals(CursorPolicy.PAGE_SCROLL, result.viewportPolicy());
        assertEquals(cursor, tab.state().getCursor());
        assertTrue(tab.state().getTopWrapSegment() > 0);
    }

    private static InputActionDispatcher.DispatchContext context(
        java.util.function.Consumer<Boolean> mouseTrackingController
    ) {
        return TuiSessionTestSupport.context(
            null,
            null,
            null,
            mouseTrackingController,
            0,
            0,
            null
        );
    }
}
