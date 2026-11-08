package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import static dev.tuink.tui.command.TuinkCommand.*;

import dev.tuiframe.widget.MenuBar;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettings;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TopMenuGroupingTest {
    @Test void fileAndEditSeparateExistingCommandsIntoTheirUserTasks() {
        var ui = UiSessionState.from(AppSettings.defaults());
        assertEquals(List.of(
            List.of(TAB_NEW, DOCUMENT_OPEN, DOCUMENT_OPEN_ENCODING),
            List.of(DOCUMENT_SAVE, DOCUMENT_SAVE_AS, DOCUMENT_FORMAT, DOCUMENT_REOPEN_ENCODING),
            List.of(DOCUMENT_RECOVER_DRAFTS), List.of(APP_QUIT)), groups(ui, TopMenuController.FILE_INDEX));
        assertEquals(List.of(
            List.of(EDIT_UNDO, EDIT_REDO), List.of(EDIT_CUT, EDIT_COPY, EDIT_PASTE),
            List.of(VIEW_PREVIEW_COPY_SOURCE, VIEW_PREVIEW_COPY_TABLE_TSV),
            List.of(EDIT_SELECT_ALL, EDIT_TOGGLE_LINE_COMMENT), List.of(SEARCH_OPEN)),
            groups(ui, TopMenuController.EDIT_INDEX));
    }

    @Test void viewContainsDocumentModesAppearanceAndMarkdownNavigationWhileSettingsContainsPreferences() {
        var ui = UiSessionState.from(AppSettings.defaults());
        assertEquals(List.of(
            List.of(VIEW_TOGGLE_MARKDOWN_PREVIEW, SETTINGS_TOGGLE_READING_MODE),
            List.of(VIEW_TOGGLE_LINE_WRAP, VIEW_TOGGLE_WORD_WRAP),
            List.of(VIEW_TOGGLE_TAB_BAR, VIEW_TOGGLE_ACTIVE_GUTTER, VIEW_TOGGLE_WHITESPACE,
                VIEW_TOGGLE_SYNTAX, VIEW_TOGGLE_RAINBOW_BRACKETS, VIEW_TOGGLE_SELECTION_HIGHLIGHT,
                VIEW_CYCLE_GUTTER_DIFF, VIEW_CYCLE_THEME),
            List.of(VIEW_PREVIEW_FOLLOW_LINK,
                VIEW_PREVIEW_NEXT_HEADING, VIEW_PREVIEW_PREVIOUS_HEADING)), groups(ui, TopMenuController.VIEW_INDEX));
        assertEquals(List.of(
            List.of(SETTINGS_TOGGLE_CONFIRM_ON_CLOSE),
            List.of(SETTINGS_TOGGLE_MOUSE, SETTINGS_TOGGLE_RIGHT_CLICK_CLIPBOARD,
                SETTINGS_TOGGLE_FILE_HISTORY_NOTIFICATIONS),
            List.of(SETTINGS_TOGGLE_SMART_HOME_END, SETTINGS_TOGGLE_AUTO_INDENT, SETTINGS_TOGGLE_AUTO_PAIRS,
                SETTINGS_TOGGLE_AUTO_LIST, VIEW_CYCLE_TAB_WIDTH, SETTINGS_CYCLE_TAB_INSERT_MODE),
            List.of(SETTINGS_TOGGLE_MARKDOWN_OPEN_BOTH_TABS, SETTINGS_TOGGLE_MARKDOWN_OPEN_IN_PREVIEW,
                SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX, SETTINGS_TOGGLE_MARKDOWN_VISUAL_COPY)),
            groups(ui, TopMenuController.SETTINGS_INDEX));
    }

    @Test void unavailableActionsKeepTheirShortLabelsAndUseTheirEnabledState() {
        var ui = UiSessionState.from(AppSettings.defaults());
        ui.wrapLinesSetting = false;
        ui.readingModeSetting = true;
        for (var kind : ActiveSurface.Kind.values()) {
            var surface = TestSurfaces.source(ui).withKind(kind).withRuntime(false, false);
            var edit = menu(ui, TopMenuController.EDIT_INDEX, surface);
            assertEquals("Undo", item(edit, EDIT_UNDO).label());
            assertFalse(item(edit, EDIT_UNDO).enabled());
            assertEquals("Cut", item(edit, EDIT_CUT).label());
            assertFalse(item(edit, EDIT_CUT).enabled());
            assertEquals("Paste", item(edit, EDIT_PASTE).label());
            assertFalse(item(edit, EDIT_PASTE).enabled());
            var view = menu(ui, TopMenuController.VIEW_INDEX, surface);
            assertEquals("[x] Wrap by words", item(view, VIEW_TOGGLE_WORD_WRAP).label());
            assertFalse(item(view, VIEW_TOGGLE_WORD_WRAP).enabled());
            assertEquals("[x] Syntax highlighting", item(view, VIEW_TOGGLE_SYNTAX).label());
            assertFalse(item(view, VIEW_TOGGLE_SYNTAX).enabled());
            var file = menu(ui, TopMenuController.FILE_INDEX, surface);
            assertEquals("Format...", item(file, DOCUMENT_FORMAT).label());
            assertEquals("Reopen with encoding...", item(file, DOCUMENT_REOPEN_ENCODING).label());
            assertEquals(kind == ActiveSurface.Kind.SOURCE, item(file, DOCUMENT_FORMAT).enabled());
        }
    }

    @Test void tabWidthShowsTheActualValueBesideInsertionModeAndUsesItsExistingCommand() {
        var ui = UiSessionState.from(AppSettings.defaults());
        for (int expected : new int[] {4, 2, 4}) {
            assertEquals(expected, ui.tabWidthSetting);
            var settings = menu(ui, TopMenuController.SETTINGS_INDEX, TestSurfaces.source(ui));
            assertEquals("[>] Tab width: " + expected, item(settings, VIEW_CYCLE_TAB_WIDTH).label());
            var result = InputActionDispatcher.dispatchUi(VIEW_CYCLE_TAB_WIDTH, ui, TestSurfaces.source(ui),
                null, ignored -> fail(), () -> fail());
            assertTrue(result.handled());
        }
    }

    private static List<List<TuinkCommand>> groups(UiSessionState ui, int menuIndex) {
        var entries = menu(ui, menuIndex, TestSurfaces.source(ui)).menus().get(menuIndex).entries();
        List<List<TuinkCommand>> groups = new ArrayList<>();
        List<TuinkCommand> group = new ArrayList<>();
        for (var entry : entries) {
            if (entry instanceof MenuBar.Item<?> item) {
                group.add(TuinkCommand.fromId(((CommandInvocation) item.value()).id()).orElseThrow());
            } else {
                assertFalse(group.isEmpty(), "A separator must divide useful groups");
                groups.add(List.copyOf(group)); group.clear();
            }
        }
        assertFalse(group.isEmpty(), "A menu must not end with a separator");
        groups.add(List.copyOf(group));
        return List.copyOf(groups);
    }

    private static TopMenuView menu(UiSessionState ui, int menuIndex, ActiveSurface surface) {
        return TopMenuView.build(ui, List.of(), -1, "Cyan", new CommandRouter(), MenuBar.State.open(menuIndex), surface);
    }

    private static MenuBar.Item<?> item(TopMenuView view, TuinkCommand command) {
        return view.menus().stream().flatMap(menu -> menu.entries().stream())
            .filter(entry -> entry instanceof MenuBar.Item<?> item
                && ((CommandInvocation) item.value()).id().equals(command.id()))
            .map(entry -> (MenuBar.Item<?>) entry).findFirst().orElseThrow();
    }
}
