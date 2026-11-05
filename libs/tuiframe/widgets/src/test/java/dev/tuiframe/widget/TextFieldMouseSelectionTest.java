package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalText;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TextFieldMouseSelectionTest {
    private static final Rect BOUNDS = new Rect(2, 1, 20, 1);
    private static final FocusContext FOCUSED = new FocusContext(true, true,
        FocusSpace.root(new Rect(0, 0, 30, 4)));

    @Test
    void dragSelectsCompleteGraphemesAndReleaseOutsideEndsCapture() {
        TextField field = new TextField();
        field.set("A👩‍💻e\u0301中Z");
        handle(field, press(1, 3));
        assertTrue(field.mouseSelecting());
        assertTrue(handle(field, drag(3, 9)).consumed());
        assertEquals("👩‍💻e\u0301中Z", field.selectedText());
        assertTrue(TerminalText.metrics(field.text(), 4).isBoundary(field.cursor()));
        assertTrue(handle(field, release(3, 29)).consumed());
        assertFalse(field.mouseSelecting());
        assertFalse(handle(field, drag(1, 2)).consumed());
        assertEquals("👩‍💻e\u0301中Z", field.selectedText());
        field.insert("x");
        assertEquals("Ax", field.text());
    }

    @Test
    void shiftClickExtendsTheExistingAnchorInEitherDirection() {
        TextField field = new TextField();
        field.set("abcdef");
        handle(field, press(1, 4));
        handle(field, release(1, 4));
        handle(field, pointer(1, 7, PointerEvent.Kind.PRESS, KeyStroke.Modifier.SHIFT));
        assertEquals("cde", field.selectedText());
        handle(field, release(1, 7));
        handle(field, pointer(1, 3, PointerEvent.Kind.PRESS, KeyStroke.Modifier.SHIFT));
        assertEquals("b", field.selectedText());
        handle(field, drag(0, 8));
        assertEquals("cdef", field.selectedText());
    }

    @Test
    void translatedCaptureProjectsCoordinatesBeyondTheParentClip() {
        TextField field = new TextField();
        field.set("abcdefgh");
        Rect bounds = new Rect(2, 1, 10, 1);
        FocusContext context = new FocusContext(true, true,
            new FocusSpace(new Point(10, 5), new Rect(0, 0, 14, 3)));
        field.layout(bounds).handle(press(6, 16), context);
        assertEquals(4, field.cursor());
        assertTrue(field.layout(bounds).handle(drag(0, 0), context).consumed());
        assertEquals("abcd", field.selectedText());
        assertTrue(field.layout(bounds).handle(release(23, 79), context).consumed());
        assertFalse(field.mouseSelecting());
    }

    @Test
    void scrolledClickAndStationaryDragKeepTheVisibleTextStill() {
        TextField field = new TextField();
        field.set("0123456789");
        Rect bounds = new Rect(2, 1, 5, 1);
        field.layout(bounds).handle(press(1, 3), FOCUSED);
        assertEquals(7, field.cursor());
        assertEquals(new Point(3, 1), field.layout(bounds).cursorPosition(FOCUSED).orElseThrow());
        field.layout(bounds).handle(drag(1, 3), FOCUSED);
        assertEquals(7, field.cursor());
        assertFalse(field.hasSelection());
        field.layout(bounds).handle(release(1, 3), FOCUSED);
        assertEquals(new Point(3, 1), field.layout(bounds).cursorPosition(FOCUSED).orElseThrow());
    }

    @Test
    void cancellingCaptureAndCopyPreserveTheDraggedViewportAndSelection() {
        Rect bounds = new Rect(2, 1, 5, 1);
        TextField field = dragIntoScrolledWindow(bounds);
        assertEquals("12345", field.selectedText());
        assertEquals(new Point(6, 1), field.layout(bounds).cursorPosition(FOCUSED).orElseThrow());
        List<Cell> before = paintedCells(field, bounds);

        // Hosts cancel field capture before routing shortcuts such as Copy.
        field.cancelMouseSelection();
        assertFalse(field.captures(release(3, 29)));
        assertFalse(field.layout(bounds).handle(
            KeyStroke.of(KeyStroke.Key.C, KeyStroke.Modifier.CTRL), FOCUSED).consumed());

        assertEquals("12345", field.selectedText());
        assertEquals(6, field.cursor());
        assertEquals(new Point(6, 1), field.layout(bounds).cursorPosition(FOCUSED).orElseThrow());
        assertEquals(before, paintedCells(field, bounds));
        assertFalse(field.layout(bounds).handle(drag(1, 2), FOCUSED).consumed());
    }

    @Test
    void explicitSelectionCollapseKeepsTheActiveCaretAndDraggedViewportAndEndsCapture() {
        Rect bounds = new Rect(2, 1, 5, 1);
        TextField field = dragIntoScrolledWindow(bounds);
        assertTrue(field.mouseSelecting());
        assertTrue(field.hasSelection());
        String value = field.text();
        int caret = field.cursor();
        Point cursor = field.layout(bounds).cursorPosition(FOCUSED).orElseThrow();
        var before = paintedCells(field, bounds).stream().map(Cell::grapheme).toList();

        for (int repeat = 0; repeat < 2; repeat++) {
            field.collapseSelection();
            assertSame(value, field.text());
            assertEquals(caret, field.cursor());
            assertFalse(field.hasSelection());
            assertFalse(field.mouseSelecting());
            assertEquals(cursor, field.layout(bounds).cursorPosition(FOCUSED).orElseThrow());
            assertEquals(before, paintedCells(field, bounds).stream().map(Cell::grapheme).toList());
            assertFalse(field.captures(release(1, 8)));
        }
        field.insert("Z");
        assertEquals(value.substring(0, caret) + "Z" + value.substring(caret), field.text(),
            "The next edit inserts at the retained active caret instead of replacing the old selection");
    }

    @Test
    void ignoredAndNonMutatingInputKeepTheRetainedWindow() {
        Rect bounds = new Rect(2, 1, 5, 1);
        TextField field = dragIntoScrolledWindow(bounds);
        field.layout(bounds).handle(release(1, 8), FOCUSED);
        List<Cell> before = paintedCells(field, bounds);
        Point cursorBefore = field.layout(bounds).cursorPosition(FOCUSED).orElseThrow();

        for (TuiInputEvent input : List.of(
            KeyStroke.of(KeyStroke.Key.ARROW_UP),
            KeyStroke.of(KeyStroke.Key.ENTER),
            KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SUPER),
            new PasteEvent("\n\t"),
            new PasteEvent("partial", true)
        )) {
            field.layout(bounds).handle(input, FOCUSED);
            assertEquals(before, paintedCells(field, bounds));
            assertEquals(cursorBefore, field.layout(bounds).cursorPosition(FOCUSED).orElseThrow());
            assertEquals("12345", field.selectedText());
            assertFalse(field.mouseSelecting());
        }
        field.insert("");
        field.paste("\n");
        assertEquals(before, paintedCells(field, bounds));
        assertEquals(cursorBefore, field.layout(bounds).cursorPosition(FOCUSED).orElseThrow());
        field.navigate(KeyStroke.Key.ARROW_UP, false, false);
        // An unsupported navigation request still clears the selection.
        assertFalse(field.hasSelection());
        List<Cell> unselected = paintedCells(field, bounds);
        Point unselectedCursor = field.layout(bounds).cursorPosition(FOCUSED).orElseThrow();
        assertFalse(field.deleteSelection());
        assertEquals(unselected, paintedCells(field, bounds));
        assertEquals(unselectedCursor, field.layout(bounds).cursorPosition(FOCUSED).orElseThrow());
    }

    @Test
    void actualCaretSelectionAndValueChangesResumeCaretFollowing() {
        Rect bounds = new Rect(2, 1, 5, 1);
        TextField moved = dragIntoScrolledWindow(bounds);
        moved.moveHome();
        assertEquals(new Point(2, 1), moved.layout(bounds).cursorPosition(FOCUSED).orElseThrow());
        assertEquals("0", paintedCells(moved, bounds).getFirst().grapheme());

        TextField collapsed = dragIntoScrolledWindow(bounds);
        collapsed.moveRight();
        assertEquals(6, collapsed.cursor(), "collapsing at the active end keeps the caret offset");
        assertFalse(collapsed.hasSelection());
        assertEquals(new Point(4, 1), collapsed.layout(bounds).cursorPosition(FOCUSED).orElseThrow());
        assertEquals("4", paintedCells(collapsed, bounds).getFirst().grapheme());

        TextField edited = dragIntoScrolledWindow(bounds);
        edited.insert("Z");
        assertEquals("0Z6789abcdefghijkl", edited.text());
        assertEquals(new Point(4, 1), edited.layout(bounds).cursorPosition(FOCUSED).orElseThrow());
        assertEquals("0", paintedCells(edited, bounds).getFirst().grapheme());

        TextField reset = dragIntoScrolledWindow(bounds);
        reset.set(reset.text());
        assertEquals(reset.length(), reset.cursor());
        assertEquals(new Point(6, 1), reset.layout(bounds).cursorPosition(FOCUSED).orElseThrow());
        assertEquals("i", paintedCells(reset, bounds).getFirst().grapheme());
    }

    @Test
    void focusAndClipCancellationPreserveTheWindowWhileResizeReflowsIt() {
        Rect bounds = new Rect(2, 1, 5, 1);
        for (FocusContext context : new FocusContext[] {
            new FocusContext(false, false, FOCUSED.space()),
            new FocusContext(false, true, FOCUSED.space()),
            new FocusContext(true, true, FocusSpace.root(new Rect(0, 0, 4, 2)))
        }) {
            TextField field = dragIntoScrolledWindow(bounds);
            List<Cell> before = paintedCells(field, bounds);
            assertFalse(field.layout(bounds).handle(drag(1, 2), context).consumed());
            assertFalse(field.mouseSelecting());
            assertEquals("12345", field.selectedText());
            assertEquals(before, paintedCells(field, bounds));
            assertFalse(field.layout(bounds).handle(drag(1, 2), FOCUSED).consumed());
        }

        TextField resized = dragIntoScrolledWindow(bounds);
        Rect narrower = new Rect(2, 1, 4, 1);
        assertEquals(new Point(2, 1), resized.layout(narrower).cursorPosition(FOCUSED).orElseThrow());
        assertFalse(resized.mouseSelecting());
        assertEquals("6", paintedCells(resized, narrower).getFirst().grapheme());
        assertFalse(resized.layout(narrower).handle(release(3, 29), FOCUSED).consumed());
    }

    @Test
    void retainedWindowAndSubsequentKeyboardMovementKeepWholeGraphemes() {
        Rect bounds = new Rect(2, 1, 5, 1);
        TextField field = new TextField();
        field.set("01👩‍💻e\u0301中789abcdefgh");
        field.moveHome();
        field.layout(bounds).handle(press(1, 3), FOCUSED);
        field.layout(bounds).handle(drag(1, 9), FOCUSED);
        field.layout(bounds).handle(release(1, 9), FOCUSED);
        TerminalText.Metrics metrics = TerminalText.metrics(field.text(), 4);
        assertTrue(metrics.isBoundary(field.cursor()));
        assertEquals("1👩‍💻e\u0301中", field.selectedText());
        List<Cell> before = paintedCells(field, bounds);
        Point cursorBefore = field.layout(bounds).cursorPosition(FOCUSED).orElseThrow();
        field.cancelMouseSelection();
        field.layout(bounds).handle(KeyStroke.of(KeyStroke.Key.C, KeyStroke.Modifier.CTRL), FOCUSED);
        assertEquals(before, paintedCells(field, bounds));
        assertEquals(cursorBefore, field.layout(bounds).cursorPosition(FOCUSED).orElseThrow());

        field.layout(bounds).handle(
            KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT), FOCUSED);
        assertTrue(metrics.isBoundary(field.cursor()));
        assertEquals("1👩‍💻e\u0301", field.selectedText());
        assertTrue(field.layout(bounds).cursorPosition(FOCUSED).isPresent());
        assertFalse(paintedCells(field, bounds).getFirst().continuation());
    }

    @Test
    void draggingPastHorizontalEdgesScrollsToBothEndsWithoutSplittingGraphemes() {
        TextField field = new TextField();
        field.set("012👩‍💻e\u0301中6789abcdef");
        field.moveHome();
        Rect bounds = new Rect(2, 1, 5, 1);
        field.layout(bounds).handle(press(1, 2), FOCUSED);
        for (int i = 0; i < 20; i++) {
            field.layout(bounds).handle(drag(0, 8), FOCUSED);
            assertTrue(TerminalText.metrics(field.text(), 4).isBoundary(field.cursor()));
        }
        assertEquals(field.text(), field.selectedText());
        field.layout(bounds).handle(release(3, 29), FOCUSED);

        field.layout(bounds).handle(press(1, 6), FOCUSED);
        for (int i = 0; i < 20; i++) {
            field.layout(bounds).handle(drag(0, 0), FOCUSED);
        }
        assertEquals(0, field.cursor());
        assertEquals(field.text(), field.selectedText());
    }

    @Test
    void disabledClippedAndUnfocusedFieldsCancelCaptureWithoutMovingTheSelection() {
        for (FocusContext context : new FocusContext[] {
            new FocusContext(false, false, FOCUSED.space()),
            new FocusContext(false, true, FOCUSED.space()),
            new FocusContext(true, true, FocusSpace.root(new Rect(0, 0, 10, 2)))
        }) {
            TextField field = new TextField();
            field.set("abcdef");
            handle(field, press(1, 4));
            assertFalse(field.layout(BOUNDS).handle(drag(1, 8), context).consumed());
            assertEquals(2, field.cursor());
            assertFalse(field.mouseSelecting());
        }
    }

    @Test
    void geometryChangesAndKeyboardInputEndCapture() {
        TextField field = new TextField();
        field.set("abcdef");
        handle(field, press(1, 3));
        field.layout(new Rect(2, 1, 10, 1));
        assertFalse(field.mouseSelecting());

        handle(field, press(1, 3));
        handle(field, drag(1, 5));
        assertEquals("bc", field.selectedText());
        assertTrue(field.layout(BOUNDS).handle(new TextInput("X"), FOCUSED).consumed());
        assertEquals("aXdef", field.text());
        assertFalse(field.mouseSelecting());
        assertFalse(handle(field, drag(1, 7)).consumed());

        handle(field, press(1, 3));
        field.moveEnd();
        assertFalse(field.mouseSelecting());
        handle(field, press(1, 3));
        field.cancelMouseSelection();
        assertFalse(field.captures(release(3, 29)));
    }

    private static InteractionResult<TextField.Action> handle(TextField field, PointerEvent pointer) {
        return field.layout(BOUNDS).handle(pointer, FOCUSED);
    }

    private static TextField dragIntoScrolledWindow(Rect bounds) {
        TextField field = new TextField();
        field.set("0123456789abcdefghijkl");
        field.moveHome();
        field.layout(bounds).handle(press(1, 3), FOCUSED);
        field.layout(bounds).handle(drag(1, 8), FOCUSED);
        return field;
    }

    private static List<Cell> paintedCells(TextField field, Rect bounds) {
        CellBuffer cells = new CellBuffer(4, 30);
        field.layout(bounds).paint(new Canvas(cells), Theme.fixed(CellStyle.DEFAULT), WidgetState.FOCUSED);
        List<Cell> result = new ArrayList<>();
        for (int column = bounds.x(); column < bounds.x() + bounds.width(); column++) {
            result.add(cells.get(bounds.y(), column));
        }
        return result;
    }

    private static PointerEvent press(int row, int column) {
        return pointer(row, column, PointerEvent.Kind.PRESS);
    }

    private static PointerEvent drag(int row, int column) {
        return pointer(row, column, PointerEvent.Kind.DRAG);
    }

    private static PointerEvent release(int row, int column) {
        return pointer(row, column, PointerEvent.Kind.RELEASE);
    }

    private static PointerEvent pointer(int row, int column, PointerEvent.Kind kind,
        KeyStroke.Modifier... modifiers) {
        return new PointerEvent(row, column, kind, PointerEvent.Button.LEFT, Set.of(modifiers));
    }
}
