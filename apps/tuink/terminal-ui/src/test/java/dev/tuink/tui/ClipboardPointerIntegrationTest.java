package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.geometry.Point;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuiframe.widget.TextField;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class ClipboardPointerIntegrationTest {
    @TempDir Path directory;

    @Test void sourceCopiesSelectionThenPastesAtTheUnmovedCaretAndKeepsCopyOutOfUndo() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("abc tail"))) {
            window.ui.rightClickClipboardSetting = true;
            selectFirstThree(window);
            var state = window.active().state();
            var caret = state.getCursor();
            long revision = window.active().tabSession().model().revision();
            right(window, 3, 40);
            assertEquals("abc", window.host.clipboard.text());
            assertFalse(state.hasSelection());
            assertEquals(caret, state.getCursor());
            assertEquals(revision, window.active().tabSession().model().revision());
            assertFalse(state.isDirty());
            right(window, 3, 60);
            assertEquals(List.of("abcabc tail"), window.active().tabSession().model().snapshotLines());
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("abc tail"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().tabSession().engine().undo());
        }
    }

    @ParameterizedTest @EnumSource(ActiveSurface.Kind.class)
    void disabledSettingLeavesSelectionAndClipboardUntouched(ActiveSurface.Kind kind) throws Exception {
        try (var window = window(kind)) {
            assertFalse(window.ui.rightClickClipboardSetting);
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            window.host.clipboard.setText("retained");
            right(window, 4, 20);
            assertEquals("retained", window.host.clipboard.text());
            assertTrue(selected(window));
            assertEquals(List.of("# Original", "text for selection"), Files.readAllLines(window.document));
        }
    }

    @ParameterizedTest @EnumSource(value = ActiveSurface.Kind.class, names = {"LIVE_PREVIEW", "FROZEN_PREVIEW"})
    void previewCopiesOnlyASelectionAndNeverPastesIntoReadOnlyContent(ActiveSurface.Kind kind) throws Exception {
        try (var window = window(kind)) {
            window.ui.rightClickClipboardSetting = true;
            var preview = window.selected().uiState().preview;
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            var caret = preview.selection.caret();
            var visual = preview.selection.visualPosition(preview.result);
            right(window, 4, 20);
            assertTrue(window.host.clipboard.text().contains("Original"));
            assertFalse(preview.selection.snapshot().selected());
            assertEquals(caret, preview.selection.caret());
            assertEquals(visual, preview.selection.visualPosition(preview.result));
            window.host.clipboard.setText("cannot insert");
            right(window, 4, 20);
            assertEquals("cannot insert", window.host.clipboard.text());
            assertEquals(List.of("# Original", "text for selection"), Files.readAllLines(window.document));
        }
    }

    @ParameterizedTest
    @CsvSource({"SOURCE,FIND", "LIVE_PREVIEW,FIND", "FROZEN_PREVIEW,FIND",
        "SOURCE,SAVE_AS", "LIVE_PREVIEW,SAVE_AS", "FROZEN_PREVIEW,SAVE_AS",
        "SOURCE,PICKER", "LIVE_PREVIEW,PICKER", "FROZEN_PREVIEW,PICKER"})
    void everyFieldUsesTheSameCopyClearPasteGestureEvenWhenItAlreadyHasFocus(
        ActiveSurface.Kind kind, String owner) throws Exception {
        try (var window = window(kind)) {
            window.ui.rightClickClipboardSetting = true;
            TextField field;
            switch (owner) {
                case "FIND" -> {
                    window.command(TuinkCommand.SEARCH_OPEN);
                    field = kind == ActiveSurface.Kind.SOURCE ? window.query : window.selected().uiState().preview.searchField;
                }
                case "SAVE_AS" -> { window.command(TuinkCommand.DOCUMENT_SAVE_AS); field = window.filename; }
                case "PICKER" -> {
                    window.command(TuinkCommand.DOCUMENT_OPEN);
                    window.event(ctrl(KeyStroke.Key.L));
                    field = window.selected().uiState().openFileFocusState.location();
                }
                default -> throw new AssertionError(owner);
            }
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            window.event(new TextInput("picked😀"));
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            Point point = point(window, "picked😀");
            int cursor = field.cursor();
            right(window, point.y(), point.x());
            assertEquals("picked😀", window.host.clipboard.text());
            assertFalse(field.hasSelection());
            assertEquals(cursor, field.cursor());
            window.host.clipboard.setText("next");
            right(window, point.y(), point.x());
            assertEquals("picked😀next", field.text());
        }
    }

    @Test void rightClickFocusesAnUnfocusedSearchFieldWithoutReplacingItsSelectionOrMovingItsCaret() throws Exception {
        try (var window = window(ActiveSurface.Kind.SOURCE)) {
            window.ui.rightClickClipboardSetting = true;
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("needle"));
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            Point point = point(window, "needle");
            window.focusSearch(SearchFocus.CASE);
            right(window, point.y(), point.x());
            assertEquals("needle", window.host.clipboard.text());
            assertEquals(SearchFocus.FIND, window.host.currentSearchFocus());
            assertFalse(window.query.hasSelection());
            assertEquals(6, window.query.cursor());
        }
    }

    @Test void guttersChromeScrollbarsDisabledMouseAndModifiersDoNotTriggerTheGesture() throws Exception {
        try (var window = new EditorWindowFixture(directory, false)) {
            window.ui.rightClickClipboardSetting = true;
            selectFirstThree(window);
            window.host.clipboard.setText("retained");
            for (Point point : List.of(new Point(0, 3), new Point(79, 5), new Point(15, 0),
                new Point(15, 23))) {
                right(window, point.y(), point.x());
                assertEquals("retained", window.host.clipboard.text(), "Non-text target: " + point);
            }
            window.event(new PointerEvent(3, 20, PointerEvent.Kind.PRESS, PointerEvent.Button.RIGHT,
                Set.of(KeyStroke.Modifier.SHIFT)));
            window.ui.mouseModeSetting = false;
            right(window, 3, 20);
            window.ui.mouseModeSetting = true;
            window.mouseAvailable = false;
            right(window, 3, 20);
            assertEquals("retained", window.host.clipboard.text());
            assertTrue(window.active().state().hasSelection());
        }
    }

    @Test void aQuitConfirmationAllowsCopyInReadableContentButBlocksPasteAndButtonGestures() throws Exception {
        try (var window = window(ActiveSurface.Kind.SOURCE)) {
            window.ui.rightClickClipboardSetting = true;
            window.event(new TextInput("dirty"));
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            window.host.clipboard.setText("retained");
            window.command(TuinkCommand.APP_QUIT);
            Point button = point(window, "Discard");
            right(window, button.y(), button.x());
            assertEquals("retained", window.host.clipboard.text());
            right(window, 3, 20);
            assertTrue(window.host.clipboard.text().startsWith("dirty# Original"));
            assertFalse(window.active().state().hasSelection());
            var before = window.active().tabSession().model().snapshotLines();
            window.host.clipboard.setText("cannot insert");
            right(window, 3, 20);
            assertEquals(before, window.active().tabSession().model().snapshotLines());
            assertTrue(window.controller.running());
        }
    }

    @Test void exclusiveHelpDoesNotExposeTheSelectedDocumentToTheGesture() throws Exception {
        try (var window = window(ActiveSurface.Kind.SOURCE)) {
            window.ui.rightClickClipboardSetting = true;
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            window.host.clipboard.setText("retained");
            window.event(KeyStroke.of(KeyStroke.Key.F1));
            right(window, 3, 20);
            assertEquals("retained", window.host.clipboard.text());
            assertTrue(window.active().state().hasSelection());
        }
    }

    private EditorWindowFixture window(ActiveSurface.Kind kind) throws Exception {
        var window = new EditorWindowFixture(directory.resolve(kind.name()), "document.md", false,
            List.of("# Original", "text for selection"));
        if (kind != ActiveSurface.Kind.SOURCE) {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.selected().uiState().preview.ready());
            if (kind == ActiveSurface.Kind.FROZEN_PREVIEW) {
                window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
                window.command(TuinkCommand.TAB_CLOSE);
                window.await(() -> window.selected().uiState().preview.ready());
            }
        }
        return window;
    }

    private static boolean selected(EditorWindowFixture window) {
        return window.selected() instanceof PreviewTab ? window.selected().uiState().preview.selection.snapshot().selected()
            : window.active().state().hasSelection();
    }
    private static Point point(EditorWindowFixture window, String text) {
        for (int row = 0; row < window.height; row++) {
            int column = window.screenRow(row).indexOf(text);
            if (column >= 0) return new Point(column + 1, row);
        }
        throw new AssertionError("Field not visible: " + text);
    }
    private static void selectFirstThree(EditorWindowFixture window) {
        for (int i = 0; i < 3; i++) window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT));
    }
    private static void right(EditorWindowFixture window, int row, int column) {
        window.event(new PointerEvent(row, column, PointerEvent.Kind.PRESS, PointerEvent.Button.RIGHT, Set.of()));
        window.event(new PointerEvent(row, column, PointerEvent.Kind.RELEASE, PointerEvent.Button.RIGHT, Set.of()));
    }
    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }
}
