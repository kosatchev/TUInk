package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.TabSession;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.tui.ui.ViewportController.CursorPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UiActionDispatcherTest {
    @Test void everyTopLevelMenuOpensWithoutADocumentContext() {
        var commands = List.of(TuinkCommand.MENU_FILE, TuinkCommand.MENU_EXPLORER, TuinkCommand.MENU_EDIT, TuinkCommand.MENU_VIEW,
            TuinkCommand.MENU_TABS, TuinkCommand.MENU_SETTINGS, TuinkCommand.MENU_HELP);
        var ui = new UiSessionState();
        ui.readingModeSetting = true;
        var menu = new TopMenuController();
        for (int index = 0; index < commands.size(); index++) {
            var result = InputActionDispatcher.dispatchUi(commands.get(index), ui, TestSurfaces.source(ui), menu, ignored -> {}, () -> {});
            assertTrue(result.handled());
            assertTrue(menu.isOpen());
            assertEquals(index, menu.state().openMenuIndex());
            assertEquals(CursorPolicy.PRESERVE, result.viewportPolicy());
        }
    }

    @Test void engineFreePreferencesInvokeOnlyTheirExplicitHostCallbacksAndKeepExistingMessages() {
        var ui = new UiSessionState();
        AtomicInteger wraps = new AtomicInteger();
        List<Boolean> mouse = new ArrayList<>();
        var enabled = InputActionDispatcher.dispatchUi(TuinkCommand.VIEW_TOGGLE_LINE_WRAP, ui, TestSurfaces.source(ui), null,
            mouse::add, wraps::incrementAndGet);
        assertEquals("Wrap lines enabled", enabled.statusMessage());
        assertTrue(ui.wrapLinesSetting);
        InputActionDispatcher.dispatchUi(TuinkCommand.VIEW_TOGGLE_LINE_WRAP, ui, TestSurfaces.source(ui), null, mouse::add, wraps::incrementAndGet);
        assertFalse(ui.wrapLinesSetting);
        assertEquals(1, wraps.get());

        ui.mouseModeSetting = true;
        var unavailable = InputActionDispatcher.dispatchUi(TuinkCommand.SETTINGS_TOGGLE_MOUSE, ui, TestSurfaces.source(ui).withRuntime(false, true), null,
            mouse::add, wraps::incrementAndGet);
        assertEquals(InputActionDispatcher.StatusLevel.WARNING, unavailable.statusLevel());
        assertTrue(ui.mouseModeSetting);
        assertTrue(mouse.isEmpty());
        var disabled = InputActionDispatcher.dispatchUi(TuinkCommand.SETTINGS_TOGGLE_MOUSE, ui, TestSurfaces.source(ui), null,
            mouse::add, wraps::incrementAndGet);
        assertEquals(InputActionDispatcher.StatusLevel.ERROR, disabled.statusLevel());
        assertEquals(List.of(false), mouse);
        assertFalse(ui.mouseModeSetting);
        assertTrue(InputActionDispatcher.dispatchUi(TuinkCommand.SETTINGS_TOGGLE_READING_MODE, ui, TestSurfaces.source(ui), null,
            mouse::add, wraps::incrementAndGet).handled());
        assertTrue(ui.readingModeSetting);
        var help = InputActionDispatcher.dispatchUi(TuinkCommand.HELP_KEY_HINTS, ui, TestSurfaces.source(ui), null, mouse::add, wraps::incrementAndGet);
        assertEquals(InputActionDispatcher.Intent.HELP_HINTS, help.intent());
    }

    @Test void sourceWrapUsesTheSharedPreferenceActionAndResetsOnlyItsViewport() {
        var tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        tab.engine().insertInput("original");
        tab.state().setTopWrapSegment(3);
        tab.state().setLeftColumn(4);
        tab.state().setLeftVisualColumn(7);
        long revision = tab.model().revision();
        var cursor = tab.state().getCursor();
        var ui = new UiSessionState();

        var result = InputActionDispatcher.dispatchUi(TuinkCommand.VIEW_TOGGLE_LINE_WRAP, ui,
            TestSurfaces.source(ui), null, ignored -> {}, () -> {
                tab.state().setTopWrapSegment(0); tab.state().setLeftColumn(0); tab.state().setLeftVisualColumn(0);
            });

        assertTrue(ui.wrapLinesSetting);
        assertEquals(CursorPolicy.PRESERVE, result.viewportPolicy());
        assertEquals(0, tab.state().getTopWrapSegment());
        assertEquals(0, tab.state().getLeftColumn());
        assertEquals(0, tab.state().getLeftVisualColumn());
        assertEquals(cursor, tab.state().getCursor());
        assertEquals(revision, tab.model().revision());
        assertEquals("original", tab.model().getLine(0));
        assertTrue(tab.engine().undo());
        assertEquals("", tab.model().getLine(0));
        assertFalse(tab.engine().undo(), "UI preferences must not enter document history");
    }

    @Test void documentSearchPreviewAndThemeCommandsRemainOwnedByTheirApplicationWorkflows() {
        var ui = new UiSessionState();
        var before = ui.settingsSnapshot(UiStyles.Theme.CYAN);
        var menu = new TopMenuController();
        for (var command : List.of(TuinkCommand.DOCUMENT_SAVE, TuinkCommand.DOCUMENT_SAVE_AS,
            TuinkCommand.EDIT_COPY, TuinkCommand.EDIT_INSERT_NEWLINE, TuinkCommand.CURSOR_DOWN,
            TuinkCommand.SEARCH_OPEN, TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW, TuinkCommand.VIEW_CYCLE_THEME)) {
            var result = InputActionDispatcher.dispatchUi(command, ui, TestSurfaces.source(ui), menu,
                ignored -> fail("unhandled command invoked terminal callback"),
                () -> fail("unhandled command invoked source viewport callback"));
            assertFalse(result.handled(), command.name());
            assertEquals(before, ui.settingsSnapshot(UiStyles.Theme.CYAN));
            assertEquals(InputActionDispatcher.Intent.NONE, result.intent());
            assertFalse(menu.isOpen());
        }
    }
}
