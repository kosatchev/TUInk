package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.Position;
import dev.tuink.core.TabSession;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.command.TuinkCommand;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InputActionDispatcherTest {
    @Test
    void verticalCommandsUseTheCurrentWrappedViewport() {
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        tab.engine().insertInput("abcdefghijklmnopqrstuvwxyz1234");
        tab.engine().moveHome();
        UiSessionState ui = new UiSessionState();
        ui.wrapLinesSetting = true;
        InputActionDispatcher.DispatchContext context = new InputActionDispatcher.DispatchContext(
            tab.engine(), tab.state(), null, ignored -> {}, 3, 4
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
            assertEquals("Reading mode: editing is disabled", result.statusMessage(), command.toString());
        }
    }

    @Test
    void readingModePreservesTextAndHistoryUntilEditingIsEnabledAgain() {
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        UiSessionState ui = new UiSessionState();
        InputActionDispatcher.DispatchContext context = new InputActionDispatcher.DispatchContext(
            tab.engine(), tab.state(), null, ignored -> {}, 24, 80
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
        ui.mouseModeAvailable = false;
        AtomicBoolean controllerCalled = new AtomicBoolean();

        InputActionDispatcher.DispatchResult result = InputActionDispatcher.dispatch(
            TuinkCommand.SETTINGS_TOGGLE_MOUSE,
            ui,
            context(ignored -> controllerCalled.set(true))
        );

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

    private static InputActionDispatcher.DispatchContext context(
        java.util.function.Consumer<Boolean> mouseTrackingController
    ) {
        return new InputActionDispatcher.DispatchContext(
            null,
            null,
            null,
            mouseTrackingController,
            0,
            0
        );
    }
}
