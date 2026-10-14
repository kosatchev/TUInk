package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Point;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.TextField;
import dev.tuink.core.Position;
import dev.tuink.core.ViewportState;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.ui.SaveAsBarView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Uses the production event entry point and production frame composition. */
class EditorWindowControllerTest {
    @TempDir
    Path directory;

    @Test
    void rejectedReadingCommandsPreserveTheManualViewportThroughTheRealEventRoute() throws Exception {
        try (EditorWindowFixture fixture = new EditorWindowFixture(directory, true)) {
            fixture.wheel(PointerEvent.Kind.SCROLL_DOWN, 4);
            fixture.wheel(PointerEvent.Kind.SCROLL_RIGHT, 2);
            ViewportState before = fixture.active().state().viewport();
            Position caret = fixture.active().state().getCursor();
            List<String> text = fixture.active().tabSession().model().snapshotLines();
            assertTrue(before.topLine() > 0);
            assertTrue(before.leftVisualColumn() > 0);

            for (TuiInputEvent event : List.of(ctrl(KeyStroke.Key.X), ctrl(KeyStroke.Key.Z), new TextInput("q"))) {
                fixture.event(event);
                assertEquals(before, fixture.active().state().viewport());
                assertEquals(caret, fixture.active().state().getCursor());
                assertEquals(text, fixture.active().tabSession().model().snapshotLines());
                assertFalse(fixture.active().state().isDirty());
                assertEquals("Reading mode: editing is disabled", fixture.notification.message);
            }

            fixture.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            assertEquals(0, fixture.active().state().getTopLine());
            assertEquals(new Position(0, 1), fixture.active().state().getCursor());
        }
    }

    @Test
    void openingSearchEndsDocumentCaptureBeforeLaterDragDelivery() throws Exception {
        try (EditorWindowFixture fixture = new EditorWindowFixture(directory, false)) {
            fixture.pointer(PointerEvent.Kind.PRESS, 3, 15);
            assertTrue(fixture.editorView.isMouseSelecting());

            fixture.event(ctrl(KeyStroke.Key.F));
            fixture.event(new TextInput("needle"));
            assertTrue(fixture.ui.searchMode);
            assertFalse(fixture.editorView.isMouseSelecting());
            Position caret = fixture.active().state().getCursor();
            Position anchor = fixture.active().state().getSelectionAnchor();

            fixture.pointer(PointerEvent.Kind.DRAG, 10, 40);
            fixture.pointer(PointerEvent.Kind.RELEASE, 10, 40);
            fixture.event(new TextInput("X"));

            assertEquals("needleX", fixture.query.text());
            assertEquals(caret, fixture.active().state().getCursor());
            assertEquals(anchor, fixture.active().state().getSelectionAnchor());
            assertFalse(fixture.active().state().isDirty());

            fixture.pointer(PointerEvent.Kind.PRESS, 4, 15);
            fixture.pointer(PointerEvent.Kind.DRAG, 6, 30);
            fixture.pointer(PointerEvent.Kind.RELEASE, 6, 30);
            assertTrue(fixture.active().state().hasSelection());
            assertTrue(fixture.ui.searchEditorFocused);
            assertEquals("needleX", fixture.query.text());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void editMenuCutUsesTheFocusedSearchFieldInEditAndReadingModes(boolean reading) throws Exception {
        try (EditorWindowFixture fixture = new EditorWindowFixture(directory, reading)) {
            List<String> document = fixture.active().tabSession().model().snapshotLines();
            fixture.event(ctrl(KeyStroke.Key.F));
            fixture.event(new TextInput("field value😀"));
            fixture.event(ctrl(KeyStroke.Key.A));
            assertEquals("field value😀", fixture.query.selectedText());

            fixture.event(KeyStroke.of(KeyStroke.Key.E, KeyStroke.Modifier.ALT));
            assertTrue(fixture.menu.isOpen());
            fixture.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            fixture.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            fixture.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertFalse(fixture.menu.isOpen());
            assertEquals("", fixture.query.text());
            assertEquals("field value😀", fixture.host.clipboard.text());
            assertEquals(document, fixture.active().tabSession().model().snapshotLines());
            assertFalse(fixture.active().state().isDirty());
            assertEquals(SearchFocus.FIND, fixture.host.searchFocusManager.focused().orElseThrow());
        }
    }

    @Test
    void keyboardTabSwitchingRestoresEachIndependentViewportAndEndsCapture() throws Exception {
        try (EditorWindowFixture fixture = new EditorWindowFixture(directory, false)) {
            fixture.event(ctrl(KeyStroke.Key.N));
            assertEquals(2, fixture.tabs.size());
            fixture.active().tabSession().engine().open(fixture.document);
            fixture.frame();
            fixture.wheel(PointerEvent.Kind.SCROLL_DOWN, 7);
            fixture.wheel(PointerEvent.Kind.SCROLL_RIGHT, 2);
            TerminalEditorApp.EditorTab second = fixture.active();
            ViewportState secondViewport = second.state().viewport();

            fixture.event(ctrl(KeyStroke.Key.TAB));
            TerminalEditorApp.EditorTab first = fixture.active();
            assertEquals(0, first.state().getTopLine());
            fixture.wheel(PointerEvent.Kind.SCROLL_DOWN, 3);
            ViewportState firstViewport = first.state().viewport();
            assertTrue(firstViewport.topLine() > 0);
            assertTrue(secondViewport.topLine() > firstViewport.topLine());

            fixture.event(ctrl(KeyStroke.Key.TAB));
            assertEquals(second, fixture.active());
            assertEquals(secondViewport, second.state().viewport());
            fixture.event(ctrl(KeyStroke.Key.TAB));
            assertEquals(first, fixture.active());
            assertEquals(firstViewport, first.state().viewport());

            fixture.pointer(PointerEvent.Kind.PRESS, 4, 15);
            assertTrue(fixture.editorView.isMouseSelecting());
            fixture.event(ctrl(KeyStroke.Key.TAB));
            assertFalse(fixture.editorView.isMouseSelecting());
        }
    }

    @Test
    void resizeAndHelpTerminateFieldCaptureWithoutReplayingTheOldDrag() throws Exception {
        try (EditorWindowFixture fixture = new EditorWindowFixture(directory, false)) {
            fixture.event(ctrl(KeyStroke.Key.F));
            fixture.event(new TextInput("0123456789".repeat(15)));
            fixture.event(KeyStroke.of(KeyStroke.Key.HOME));
            fixture.pointer(PointerEvent.Kind.PRESS, fixture.height - 3, 8);
            fixture.pointer(PointerEvent.Kind.DRAG, fixture.height - 3, 35);
            assertTrue(fixture.query.mouseSelecting());

            fixture.width = 100;
            fixture.event(new ResizeEvent(fixture.height, fixture.width));
            assertFalse(fixture.query.mouseSelecting());
            int caret = fixture.query.cursor();
            fixture.pointer(PointerEvent.Kind.DRAG, 0, 79);
            fixture.pointer(PointerEvent.Kind.RELEASE, 0, 79);
            assertEquals(caret, fixture.query.cursor());

            fixture.pointer(PointerEvent.Kind.PRESS, fixture.height - 3, 8);
            assertTrue(fixture.query.mouseSelecting());
            fixture.event(KeyStroke.of(KeyStroke.Key.F6));
            fixture.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertFalse(fixture.query.mouseSelecting());
            fixture.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(fixture.ui.searchMode);
            assertEquals(SearchFocus.FIND, fixture.host.searchFocusManager.focused().orElseThrow());
            fixture.event(ctrl(KeyStroke.Key.A));
            fixture.event(new TextInput("resumed"));
            assertEquals("resumed", fixture.query.text());
            assertFalse(fixture.active().state().isDirty());
        }
    }

    @ParameterizedTest
    @EnumSource(FieldTarget.class)
    void fieldDragCrossesMenuAndDocumentBoundsThenReleaseEndsDelivery(FieldTarget target) throws Exception {
        try (EditorWindowFixture fixture = new EditorWindowFixture(directory, false)) {
            FieldInteraction interaction = prepareField(fixture, target);
            TextField field = interaction.field();
            int row = fixture.rowStartingWith(interaction.label());
            Position documentCaret = fixture.active().state().getCursor();
            fixture.pointer(PointerEvent.Kind.PRESS, row, interaction.label().length() + 2);
            int pressedCaret = field.cursor();
            assertTrue(field.mouseSelecting());

            fixture.pointer(PointerEvent.Kind.DRAG, 0, fixture.width + 15);
            assertTrue(field.mouseSelecting());
            assertTrue(field.cursor() > pressedCaret);
            assertFalse(field.selectedText().isEmpty());
            assertFieldFocus(fixture, target);
            assertFalse(fixture.menu.isOpen());
            assertEquals(documentCaret, fixture.active().state().getCursor());

            fixture.event(new PointerEvent(0, 0, PointerEvent.Kind.RELEASE, PointerEvent.Button.OTHER, Set.of()));
            assertFalse(field.mouseSelecting());
            int releasedCaret = field.cursor();
            String selection = field.selectedText();
            fixture.pointer(PointerEvent.Kind.DRAG, fixture.height + 5, fixture.width + 15);
            assertEquals(releasedCaret, field.cursor());
            assertEquals(selection, field.selectedText());
            assertEquals(documentCaret, fixture.active().state().getCursor());
            assertFalse(fixture.editorView.isMouseSelecting());
            assertFalse(fixture.active().state().isDirty());
        }
    }

    @ParameterizedTest
    @EnumSource(FieldTarget.class)
    void copyEndsFieldCaptureAndRetainsItsSelectionCaretAndViewport(FieldTarget target) throws Exception {
        try (EditorWindowFixture fixture = new EditorWindowFixture(directory, false)) {
            FieldInteraction interaction = prepareField(fixture, target);
            TextField field = interaction.field();
            int row = fixture.rowStartingWith(interaction.label());
            fixture.pointer(PointerEvent.Kind.PRESS, row, interaction.label().length() + 2);
            fixture.pointer(PointerEvent.Kind.DRAG, row, fixture.width + 15);
            assertTrue(field.mouseSelecting());
            String selection = field.selectedText();
            int caret = field.cursor();
            String visible = fixture.screenRow(row);
            assertFalse(selection.isEmpty());

            fixture.event(ctrl(KeyStroke.Key.C));
            assertFalse(field.mouseSelecting());
            assertEquals(selection, fixture.host.clipboard.text());
            assertEquals(selection, field.selectedText());
            assertEquals(caret, field.cursor());
            assertEquals(visible, fixture.screenRow(row));

            fixture.pointer(PointerEvent.Kind.DRAG, 0, 0);
            fixture.pointer(PointerEvent.Kind.RELEASE, 0, 0);
            assertEquals(selection, field.selectedText());
            assertEquals(caret, field.cursor());
            assertFieldFocus(fixture, target);
            assertFalse(fixture.active().state().isDirty());
        }
    }

    @Test
    void documentScrollbarCrossesMenuAndReleaseOrKeyboardEndsTheDrag() throws Exception {
        try (EditorWindowFixture fixture = new EditorWindowFixture(directory, false)) {
            Point thumb = fixture.verticalThumb(true);
            Position caret = fixture.active().state().getCursor();
            fixture.pointer(PointerEvent.Kind.PRESS, thumb.y(), thumb.x());
            fixture.pointer(PointerEvent.Kind.DRAG, fixture.height + 10, 0);
            int draggedTop = fixture.active().state().getTopLine();
            assertTrue(draggedTop > 80);
            assertEquals(caret, fixture.active().state().getCursor());

            fixture.pointer(PointerEvent.Kind.RELEASE, 0, 0);
            fixture.pointer(PointerEvent.Kind.DRAG, 0, fixture.width - 1);
            assertEquals(draggedTop, fixture.active().state().getTopLine());

            thumb = fixture.verticalThumb(true);
            fixture.pointer(PointerEvent.Kind.PRESS, thumb.y(), thumb.x());
            fixture.event(ctrl(KeyStroke.Key.C));
            fixture.pointer(PointerEvent.Kind.DRAG, 0, fixture.width - 1);
            assertEquals(draggedTop, fixture.active().state().getTopLine());
            assertEquals(caret, fixture.active().state().getCursor());
            assertFalse(fixture.active().state().isDirty());
        }
    }

    @Test
    void helpScrollbarDeliversOutsideDragAndCancelsOnReleaseAndKeyboard() throws Exception {
        try (EditorWindowFixture fixture = new EditorWindowFixture(directory, false)) {
            fixture.event(KeyStroke.of(KeyStroke.Key.F6));
            fixture.event(KeyStroke.of(KeyStroke.Key.ENTER));
            Point thumb = fixture.verticalThumb(false);
            fixture.pointer(PointerEvent.Kind.PRESS, thumb.y(), thumb.x());
            assertNotNull(fixture.host.helpScrollState.drag());
            fixture.pointer(PointerEvent.Kind.DRAG, fixture.height + 5, 0);
            int draggedTop = fixture.host.helpScrollState.firstVisibleIndex();
            assertTrue(draggedTop > 0);
            fixture.pointer(PointerEvent.Kind.RELEASE, 0, 0);
            assertNull(fixture.host.helpScrollState.drag());
            fixture.pointer(PointerEvent.Kind.DRAG, 0, fixture.width - 1);
            assertEquals(draggedTop, fixture.host.helpScrollState.firstVisibleIndex());

            thumb = fixture.verticalThumb(false);
            fixture.pointer(PointerEvent.Kind.PRESS, thumb.y(), thumb.x());
            assertNotNull(fixture.host.helpScrollState.drag());
            fixture.event(KeyStroke.of(KeyStroke.Key.HOME));
            assertNull(fixture.host.helpScrollState.drag());
            assertEquals(0, fixture.host.helpScrollState.firstVisibleIndex());
            fixture.pointer(PointerEvent.Kind.DRAG, fixture.height + 5, 0);
            assertEquals(0, fixture.host.helpScrollState.firstVisibleIndex());
            assertFalse(fixture.active().state().isDirty());
        }
    }

    @Test
    void tinySettingsMenuScrollbarKeepsCaptureOutsidePopupAndCancelsItBeforeKeyboard() throws Exception {
        try (EditorWindowFixture fixture = new EditorWindowFixture(directory, false)) {
            fixture.resize(8, 80);
            fixture.event(KeyStroke.of(KeyStroke.Key.F5));
            Point thumb = fixture.verticalThumb(false);
            fixture.pointer(PointerEvent.Kind.PRESS, thumb.y(), thumb.x());
            assertNotNull(fixture.menu.state().drag());
            fixture.pointer(PointerEvent.Kind.DRAG, fixture.height + 5, 0);
            int draggedTop = fixture.menu.state().firstVisibleEntryIndex();
            assertTrue(draggedTop > 0);
            fixture.pointer(PointerEvent.Kind.RELEASE, 0, fixture.width - 1);
            assertNull(fixture.menu.state().drag());
            fixture.pointer(PointerEvent.Kind.DRAG, 0, 0);
            assertEquals(draggedTop, fixture.menu.state().firstVisibleEntryIndex());

            thumb = fixture.verticalThumb(false);
            fixture.pointer(PointerEvent.Kind.PRESS, thumb.y(), thumb.x());
            assertNotNull(fixture.menu.state().drag());
            fixture.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            assertNull(fixture.menu.state().drag());
            int keyboardTop = fixture.menu.state().firstVisibleEntryIndex();
            fixture.pointer(PointerEvent.Kind.DRAG, fixture.height + 5, 0);
            assertEquals(keyboardTop, fixture.menu.state().firstVisibleEntryIndex());
            assertTrue(fixture.menu.isOpen());
            assertFalse(fixture.active().state().isDirty());
        }
    }

    @Test
    void filePickerScrollbarDeliversOutsideDragAndCancelsItBeforeKeyboardNavigation() throws Exception {
        for (int index = 0; index < 80; index++) {
            Files.writeString(directory.resolve("file-%03d.txt".formatted(index)), "fixture");
        }
        try (EditorWindowFixture fixture = new EditorWindowFixture(directory, false)) {
            fixture.event(ctrl(KeyStroke.Key.O));
            fixture.await(() -> !fixture.tree.loading());
            Point thumb = fixture.verticalThumb(false);
            var selected = fixture.tree.state().selectedId();
            Position caret = fixture.active().state().getCursor();
            fixture.pointer(PointerEvent.Kind.PRESS, thumb.y(), thumb.x());
            assertNotNull(fixture.tree.state().widgetState().drag());
            fixture.pointer(PointerEvent.Kind.DRAG, fixture.height + 5, 0);
            int draggedTop = fixture.tree.state().widgetState().firstVisibleIndex();
            assertTrue(draggedTop > 0);
            assertEquals(selected, fixture.tree.state().selectedId());
            fixture.pointer(PointerEvent.Kind.RELEASE, 0, 0);
            assertNull(fixture.tree.state().widgetState().drag());
            fixture.pointer(PointerEvent.Kind.DRAG, 0, fixture.width - 1);
            assertEquals(draggedTop, fixture.tree.state().widgetState().firstVisibleIndex());

            thumb = fixture.verticalThumb(false);
            fixture.pointer(PointerEvent.Kind.PRESS, thumb.y(), thumb.x());
            assertNotNull(fixture.tree.state().widgetState().drag());
            fixture.event(KeyStroke.of(KeyStroke.Key.HOME));
            assertNull(fixture.tree.state().widgetState().drag());
            assertEquals(0, fixture.tree.state().widgetState().firstVisibleIndex());
            fixture.pointer(PointerEvent.Kind.DRAG, fixture.height + 5, 0);
            assertEquals(0, fixture.tree.state().widgetState().firstVisibleIndex());
            assertEquals(caret, fixture.active().state().getCursor());
            assertEquals(1, fixture.tabs.size());
            assertFalse(fixture.active().state().isDirty());
        }
    }

    private static FieldInteraction prepareField(EditorWindowFixture fixture, FieldTarget target) {
        TextField field;
        String label;
        if (target == FieldTarget.SAVE_AS) {
            fixture.event(ctrl(KeyStroke.Key.K));
            field = fixture.filename;
            label = "Save As: ";
        } else {
            fixture.event(ctrl(KeyStroke.Key.F));
            fixture.focusSearch(target == FieldTarget.FIND ? SearchFocus.FIND : SearchFocus.REPLACE);
            field = target == FieldTarget.FIND ? fixture.query : fixture.replacement;
            label = target == FieldTarget.FIND ? "Find: " : "Replace: ";
        }
        fixture.event(ctrl(KeyStroke.Key.A));
        fixture.event(new TextInput("0123456789".repeat(15)));
        fixture.event(KeyStroke.of(KeyStroke.Key.HOME));
        return new FieldInteraction(field, label);
    }

    private static void assertFieldFocus(EditorWindowFixture fixture, FieldTarget target) {
        if (target == FieldTarget.SAVE_AS) {
            assertEquals(SaveAsBarView.Target.FIELD, fixture.host.saveAsFocusManager.focused().orElseThrow());
        } else {
            assertFalse(fixture.ui.searchEditorFocused);
            assertEquals(target == FieldTarget.FIND ? SearchFocus.FIND : SearchFocus.REPLACE,
                fixture.host.searchFocusManager.focused().orElseThrow());
        }
    }

    private enum FieldTarget { FIND, REPLACE, SAVE_AS }

    private record FieldInteraction(TextField field, String label) {}

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }

}
