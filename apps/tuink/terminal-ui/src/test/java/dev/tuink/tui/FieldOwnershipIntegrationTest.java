package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FieldOwnershipIntegrationTest {
    @TempDir Path directory;

    @Test void previewQueryKeepsItsLogicalCopyRecipientWhileTheMenuOwnsPhysicalInput() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("# Heading", "alpha body"))) {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW); window.await(() -> window.preview().ready());
            window.command(TuinkCommand.SEARCH_OPEN); window.event(new TextInput("alpha"));
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            var selection = window.preview().selection.snapshot();
            for (boolean pointer : new boolean[] {false, true}) {
                window.host.clipboard.setText("old");
                window.command(TuinkCommand.MENU_EDIT);
                assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());
                assertTrue(window.controller.activeSurface().allows(TuinkCommand.EDIT_COPY));
                if (pointer) {
                    int row = -1;
                    for (int y = 0; y < window.height; y++) if (window.screenRow(y).contains("Copy")) { row = y; break; }
                    assertTrue(row >= 0);
                    int column = window.screenRow(row).indexOf("Copy") + 1;
                    window.pointer(PointerEvent.Kind.PRESS, row, column);
                    window.pointer(PointerEvent.Kind.RELEASE, row, column);
                } else {
                    window.selectMenuCommand(TuinkCommand.EDIT_COPY);
                    window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                }
                assertFalse(window.menu.isOpen());
                assertEquals("alpha", window.host.clipboard.text());
                assertEquals("alpha", window.preview().searchField.selectedText());
                assertEquals(selection, window.preview().selection.snapshot());
            }
        }
    }

    @Test void previewFindFieldAndBodyShareActualFocusForNavigationCopyTraversalAndCursor() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("# Heading", "", "alpha beta alpha gamma"))) {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW); window.await(() -> window.preview().ready());
            window.command(TuinkCommand.SEARCH_OPEN); window.event(new TextInput("alpha"));
            var preview = window.preview();
            var field = preview.searchPlacement().bounds();
            var matches = preview.searchMatches;
            assertEquals(field.y(), window.controller.previewFrame().cursor().position().y());
            int row = -1;
            for (int y = 0; y < window.height; y++) if (window.screenRow(y).contains("alpha beta")) { row = y; break; }
            assertTrue(row >= 0);
            int column = window.screenRow(row).indexOf("alpha beta");
            window.pointer(PointerEvent.Kind.PRESS, row, column + 1);
            window.pointer(PointerEvent.Kind.DRAG, row, column + 4);
            window.pointer(PointerEvent.Kind.RELEASE, row, column + 4);
            assertEquals(ActiveSurface.Focus.CONTENT, window.controller.activeSurface().focus());
            assertTrue(window.selected().uiState().searchEditorFocused);
            window.command(TuinkCommand.EDIT_COPY);
            assertEquals(preview.selectedText(), window.host.clipboard.text());
            assertNotEquals("alpha", window.host.clipboard.text());
            var before = preview.selection.snapshot();
            int fieldCursor = preview.searchField.cursor();
            window.command(TuinkCommand.CURSOR_RIGHT);
            assertNotEquals(before, preview.selection.snapshot());
            assertEquals(fieldCursor, preview.searchField.cursor());
            assertEquals(row, window.controller.previewFrame().cursor().position().y());
            var bodySelection = preview.selection.snapshot();
            int top = preview.top, left = preview.left;
            window.pointer(PointerEvent.Kind.PRESS, field.y(), field.x() + 1);
            window.pointer(PointerEvent.Kind.RELEASE, field.y(), field.x() + 1);
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());
            window.command(TuinkCommand.CURSOR_HOME);
            assertEquals(0, preview.searchField.cursor());
            window.command(TuinkCommand.EDIT_SELECT_ALL); window.command(TuinkCommand.EDIT_COPY);
            assertEquals("alpha", window.host.clipboard.text());
            assertEquals(bodySelection, preview.selection.snapshot());
            window.command(TuinkCommand.EDIT_INSERT_TAB);
            assertFalse(window.selected().uiState().searchEditorFocused);
            assertEquals(SearchFocus.CASE, window.selected().uiState().searchFocusManager.focused().orElseThrow());
            assertEquals(ActiveSurface.Focus.CONTROL, window.controller.activeSurface().focus());
            assertFalse(window.controller.previewFrame().cursor().visible(), "Toolbar buttons show focus without an editing caret");
            for (int index = 0; index < 5; index++) window.command(TuinkCommand.EDIT_INSERT_TAB);
            assertTrue(window.selected().uiState().searchEditorFocused);
            assertEquals(row, window.controller.previewFrame().cursor().position().y());
            window.event(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT));
            assertFalse(window.selected().uiState().searchEditorFocused);
            assertEquals(SearchFocus.PREV_BUTTON, window.selected().uiState().searchFocusManager.focused().orElseThrow());
            assertEquals(ActiveSurface.Focus.CONTROL, window.controller.activeSurface().focus());
            for (int index = 0; index < 5; index++) window.event(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT));
            assertEquals(SearchFocus.FIND, window.selected().uiState().searchFocusManager.focused().orElseThrow());
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());
            assertEquals(field.y(), window.controller.previewFrame().cursor().position().y());
            assertEquals("alpha", preview.searchQuery);
            assertEquals(matches, preview.searchMatches);
            assertEquals(top, preview.top); assertEquals(left, preview.left);
        }
    }

    @Test void previewQueryCaptureNeverMovesContentAndIsCancelledBeforeAModalTakesOwnership() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("# Heading", "alpha beta gamma delta epsilon"))) {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW); window.await(() -> window.preview().ready());
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("alpha beta gamma delta epsilon"));
            var preview = window.preview();
            var selection = preview.selection.snapshot();
            var bounds = preview.searchPlacement().bounds();
            window.pointer(PointerEvent.Kind.PRESS, bounds.y(), bounds.x() + 2);
            window.pointer(PointerEvent.Kind.DRAG, bounds.y(), bounds.x() + 8);
            assertTrue(preview.searchPlacement().pointerCaptureActive());
            window.pointer(PointerEvent.Kind.RELEASE, bounds.y() + 3, bounds.x() + 12);
            assertFalse(preview.searchPlacement().pointerCaptureActive());
            assertEquals(selection, preview.selection.snapshot());
            window.pointer(PointerEvent.Kind.PRESS, bounds.y(), bounds.x() + 2);
            window.pointer(PointerEvent.Kind.DRAG, bounds.y(), bounds.x() + 8);
            assertTrue(preview.searchPlacement().pointerCaptureActive());
            window.controller.invokeCommand(CommandInvocation.of(TuinkCommand.DOCUMENT_SAVE_AS.id()),
                UiRecipient.presentation(window.selected(), UiRecipient.Flow.LOCAL));
            window.frame();
            assertTrue(window.selected().uiState().modalState.isSaveAs());
            assertFalse(preview.searchPlacement().pointerCaptureActive());
            String query = preview.searchQuery;
            window.pointer(PointerEvent.Kind.DRAG, bounds.y() + 2, bounds.x() + 20);
            window.pointer(PointerEvent.Kind.RELEASE, bounds.y() + 2, bounds.x() + 20);
            assertEquals(query, preview.searchQuery);
            assertEquals(selection, preview.selection.snapshot());
            window.command(TuinkCommand.UI_ESCAPE);
            window.command(TuinkCommand.SETTINGS_TOGGLE_MOUSE);
            assertFalse(window.ui.mouseModeSetting);
            window.event(new TextInput("x"));
            assertNotEquals(query, preview.searchQuery, "Keyboard editing is independent of mouse availability");
        }
    }

    @Test void saveAsCapabilitiesFollowItsFieldButtonsAndReadonlyDocumentFocus() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("document"))) {
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            assertEquals(ActiveSurface.Focus.EDITABLE_TEXT, window.controller.activeSurface().focus());
            assertTrue(window.controller.activeSurface().allows(TuinkCommand.EDIT_PASTE));
            String filename = window.filename.text();
            window.command(TuinkCommand.EDIT_INSERT_TAB);
            assertEquals(ActiveSurface.Focus.CONTROL, window.controller.activeSurface().focus());
            assertFalse(window.controller.activeSurface().allows(TuinkCommand.EDIT_COPY));
            assertFalse(window.controller.activeSurface().allows(TuinkCommand.EDIT_PASTE));
            window.command(TuinkCommand.EDIT_INSERT_TAB);
            window.command(TuinkCommand.EDIT_INSERT_TAB);
            assertEquals(ActiveSurface.Focus.CONTENT, window.controller.activeSurface().focus());
            assertFalse(window.controller.activeSurface().allows(TuinkCommand.EDIT_PASTE));
            window.host.clipboard.setText("must not be pasted");
            window.controller.invokeCommand(CommandInvocation.of(TuinkCommand.EDIT_PASTE.id()),
                UiRecipient.presentation(window.selected(), UiRecipient.Flow.LOCAL));
            window.frame();
            assertEquals(List.of("document"), window.active().tabSession().model().snapshotLines());
            assertEquals(filename, window.filename.text());
            window.command(TuinkCommand.UI_ESCAPE);
            assertTrue(window.selected().uiState().modalState.isNone());
        }
    }
}
