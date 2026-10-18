package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalText;
import org.junit.jupiter.api.Test;

class TextFieldSelectionTest {
    private static final Rect BOUNDS = new Rect(0, 0, 30, 1);
    private static final FocusContext FOCUSED = new FocusContext(true, true, FocusSpace.root(BOUNDS));

    @Test
    void shiftSelectionAndCollapseRespectWholeGraphemes() {
        TextField field = new TextField();
        field.set("A👩‍💻e\u0301中");
        key(field, KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT);
        key(field, KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT);
        assertEquals("e\u0301中", field.selectedText());
        key(field, KeyStroke.Key.ARROW_LEFT);
        assertEquals("A👩‍💻".length(), field.cursor());
        assertFalse(field.hasSelection());

        key(field, KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT);
        assertEquals("👩‍💻", field.selectedText());
        key(field, KeyStroke.Key.ARROW_RIGHT);
        assertEquals("A👩‍💻".length(), field.cursor());
        assertFalse(field.hasSelection());
    }

    @Test
    void wordMotionAndWordSelectionHandleUnicodeWhitespaceAndPunctuation() {
        TextField field = new TextField();
        field.set("один  e\u0301_two.txt");
        key(field, KeyStroke.Key.HOME);
        key(field, KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.CTRL);
        assertEquals("один  ".length(), field.cursor());
        key(field, KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT);
        assertEquals("e\u0301_two", field.selectedText());
        key(field, KeyStroke.Key.END);
        key(field, KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.CTRL);
        assertEquals("один  e\u0301_two.".length(), field.cursor());
        key(field, KeyStroke.Key.B, KeyStroke.Modifier.ALT);
        assertEquals("один  e\u0301_two".length(), field.cursor());
        key(field, KeyStroke.Key.HOME, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT);
        assertEquals("один  e\u0301_two", field.selectedText());
        assertTrue(TerminalText.metrics(field.text(), 4).isBoundary(field.cursor()));
    }

    @Test
    void typingPasteAndDeleteReplaceOnlyTheSelectedText() {
        TextField field = new TextField();
        field.set("before 👩‍💻 after");
        key(field, KeyStroke.Key.HOME);
        key(field, KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.CTRL);
        key(field, KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT);
        assertEquals(TextField.Action.VALUE_CHANGED, handle(field, new TextInput("中")).output().orElseThrow());
        assertEquals("before 中 after", field.text());
        assertFalse(field.hasSelection());

        key(field, KeyStroke.Key.A, KeyStroke.Modifier.CTRL);
        assertEquals(TextField.Action.INPUT_REJECTED,
            handle(field, new PasteEvent("partial", true)).output().orElseThrow());
        assertEquals("before 中 after", field.selectedText());
        handle(field, new PasteEvent("new\r\n\tvalue"));
        assertEquals("new value", field.text());
        assertFalse(field.hasSelection());
        key(field, KeyStroke.Key.A, KeyStroke.Modifier.CTRL);
        key(field, KeyStroke.Key.DELETE);
        assertEquals("", field.text());
        assertFalse(field.hasSelection());
    }

    @Test
    void wordDeletionAndCollapsedSelectionDoNotLeaveStaleAnchors() {
        TextField field = new TextField();
        field.set("first second");
        key(field, KeyStroke.Key.BACKSPACE, KeyStroke.Modifier.CTRL);
        assertEquals("first ", field.text());
        key(field, KeyStroke.Key.HOME);
        key(field, KeyStroke.Key.DELETE, KeyStroke.Modifier.CTRL);
        assertEquals("", field.text());

        field.set("ab");
        key(field, KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT);
        key(field, KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT);
        key(field, KeyStroke.Key.BACKSPACE);
        assertEquals("a", field.text());
        assertFalse(field.hasSelection());
        key(field, KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT);
        assertEquals("a", field.selectedText());
    }

    @Test
    void selectionPaintIsClippedAndThePlacementKeepsItsOwnSnapshot() {
        TextField field = new TextField();
        field.set("ab中cd");
        key(field, KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT);
        key(field, KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT);
        key(field, KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT);
        var snapshot = field.layout(new Rect(1, 0, 7, 1));
        field.clear();
        CellStyle normal = CellStyle.fg(CellColor.WHITE);
        CellStyle selected = new CellStyle(CellColor.BLACK, CellColor.CYAN, false, false);
        Theme theme = role -> WidgetThemeKeys.TEXT_FIELD_SELECTION.equals(role) ? selected : normal;
        CellBuffer cells = new CellBuffer(1, 9);
        snapshot.paint(new Canvas(cells), theme, WidgetState.FOCUSED);
        assertEquals(normal, cells.get(0, 2).style());
        assertEquals("中", cells.get(0, 3).grapheme());
        assertTrue(cells.get(0, 4).continuation());
        assertEquals(selected, cells.get(0, 3).style());
        assertEquals(selected, cells.get(0, 4).style());
        assertEquals(selected, cells.get(0, 6).style());
        assertEquals(normal, cells.get(0, 7).style());
    }

    private static void key(TextField field, KeyStroke.Key key, KeyStroke.Modifier... modifiers) {
        assertTrue(handle(field, KeyStroke.of(key, modifiers)).consumed());
    }

    private static InteractionResult<TextField.Action> handle(TextField field, TuiInputEvent event) {
        return field.layout(BOUNDS).handle(event, FOCUSED);
    }
}
