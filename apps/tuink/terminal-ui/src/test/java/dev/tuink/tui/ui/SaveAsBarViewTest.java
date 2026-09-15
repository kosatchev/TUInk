package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetThemeKeys;
import org.junit.jupiter.api.Test;

class SaveAsBarViewTest {
    private static final CellStyle NORMAL = CellStyle.fg(CellColor.WHITE);
    private static final CellStyle PROMPT = new CellStyle(
        CellColor.BRIGHT_WHITE,
        CellColor.RED,
        false,
        false
    );
    private static final CellStyle FOCUSED = new CellStyle(
        CellColor.BLACK,
        CellColor.BRIGHT_WHITE,
        true,
        false
    );
    private static final CellStyle DISABLED = CellStyle.fg(CellColor.BRIGHT_BLACK);
    private static final Theme THEME = role -> {
        if (WidgetThemeKeys.TEXT_FIELD_FOCUSED.equals(role)) {
            return FOCUSED;
        }
        if (WidgetThemeKeys.TEXT_FIELD_DISABLED.equals(role)) {
            return DISABLED;
        }
        return NORMAL;
    };

    @Test
    void rasterRowContainsOnlyLabelAndScopeUsesTheExactRemainingPlacement() {
        int columns = 30;
        TextField field = new TextField();
        field.set("secret.txt");
        SaveAsBarView view = SaveAsBarView.build(field, columns);
        SaveAsBarView.Placement placement = view.place(new Object(), 3, viewport(5, columns));
        RoutedTarget<SaveAsBarView.Target, TextField.Action> target = target(placement);

        assertEquals(CellText.fit(SaveAsBarView.DEFAULT_LABEL, columns), view.row());
        assertEquals(view.row(), placement.row());
        assertFalse(view.row().contains(field.text()));
        assertEquals(CellText.width(SaveAsBarView.DEFAULT_LABEL), target.placement().bounds().x());
        assertEquals(3, target.placement().bounds().y());
        assertEquals(columns - CellText.width(SaveAsBarView.DEFAULT_LABEL), target.placement().bounds().width());
        assertEquals(placement.fieldBounds(), target.placement().bounds());
        assertTrue(target.enabled());
        assertTrue(target.focusable());
    }

    @Test
    void paintAndCursorReadOneFocusSnapshotWithoutLeakingCanvasState() {
        int columns = 24;
        TextField field = new TextField();
        field.set("A中");
        SaveAsBarView.Placement placement = SaveAsBarView.build(field, columns)
            .place(new Object(), 3, viewport(5, columns));
        FocusManager<SaveAsBarView.Target> focus = new FocusManager<>();
        placement.activate(focus);

        CellBuffer buffer = new CellBuffer(5, columns);
        Canvas canvas = new Canvas(buffer);
        placement.paint(canvas, THEME, focus);

        int start = placement.fieldBounds().x();
        assertEquals("A", buffer.get(3, start).grapheme());
        assertEquals("中", buffer.get(3, start + 1).grapheme());
        assertTrue(buffer.get(3, start + 2).continuation());
        assertEquals(FOCUSED, buffer.get(3, start).style());
        assertEquals(new Point(start + 3, 3), placement.cursorPosition(focus).orElseThrow());
        assertTrue(canvas.put(0, 0, "Z", NORMAL), "field paint must restore the parent clip");
    }

    @Test
    void finalComposedRowHasOnePainterForLongUnicodeValueAndAVisibleTinyCursor() {
        int columns = 16;
        int row = 2;
        TextField field = new TextField();
        field.set("αβ中😀tail");
        FocusManager<SaveAsBarView.Target> focus = new FocusManager<>();
        SaveAsBarView.Placement placement = SaveAsBarView.build(field, columns)
            .place(new Object(), row, viewport(4, columns));
        placement.activate(focus);

        CellBuffer buffer = new CellBuffer(4, columns);
        Canvas canvas = new Canvas(buffer);
        canvas.fill(0, row, columns, 1, PROMPT);
        CellText.draw(canvas, 0, row, placement.row(), PROMPT);
        placement.paint(canvas, THEME, focus);

        assertEquals("Save As: 😀»tail ", visibleCellRow(buffer, row));
        assertEquals(PROMPT, buffer.get(row, 0).style());
        assertEquals(FOCUSED, buffer.get(row, placement.fieldBounds().x()).style());
        assertEquals(new Point(columns - 1, row), placement.cursorPosition(focus).orElseThrow());

        int tinyColumns = CellText.width(SaveAsBarView.DEFAULT_LABEL) + 1;
        SaveAsBarView.Placement tiny = SaveAsBarView.build(field, tinyColumns)
            .place(new Object(), row, viewport(4, tinyColumns));
        FocusManager<SaveAsBarView.Target> tinyFocus = new FocusManager<>();
        tiny.activate(tinyFocus);
        CellBuffer tinyBuffer = new CellBuffer(4, tinyColumns);
        Canvas tinyCanvas = new Canvas(tinyBuffer);
        tinyCanvas.fill(0, row, tinyColumns, 1, PROMPT);
        CellText.draw(tinyCanvas, 0, row, tiny.row(), PROMPT);
        tiny.paint(tinyCanvas, THEME, tinyFocus);

        assertEquals("Save As:  ", visibleCellRow(tinyBuffer, row));
        assertEquals(new Point(tinyColumns - 1, row), tiny.cursorPosition(tinyFocus).orElseThrow());
    }

    @Test
    void paintAndCursorLookupDoNotEnterOrMutateFocus() {
        TextField field = new TextField();
        field.set("draft.txt");
        SaveAsBarView.Placement placement = SaveAsBarView.build(field, 30)
            .place(new Object(), 2, viewport(4, 30));
        FocusManager<SaveAsBarView.Target> focus = new FocusManager<>();
        String before = field.text();
        int cursorBefore = field.cursor();

        CellBuffer buffer = new CellBuffer(4, 30);
        placement.paint(new Canvas(buffer), THEME, focus);

        assertTrue(focus.activeScopeId().isEmpty());
        assertTrue(focus.focused().isEmpty());
        assertTrue(placement.cursorPosition(focus).isEmpty());
        assertEquals(before, field.text());
        assertEquals(cursorBefore, field.cursor());
        assertEquals(NORMAL, buffer.get(2, placement.fieldBounds().x()).style());
    }

    @Test
    void emptyOrPartiallyClippedFieldIsDisabledAndHasNoCursor() {
        int labelWidth = CellText.width(SaveAsBarView.DEFAULT_LABEL);
        TextField field = new TextField();
        field.set("draft.txt");
        SaveAsBarView.Placement empty = SaveAsBarView.build(field, labelWidth)
            .place(new Object(), 2, viewport(4, labelWidth));
        FocusManager<SaveAsBarView.Target> emptyFocus = new FocusManager<>();
        empty.activate(emptyFocus);

        assertTrue(empty.fieldBounds().isEmpty());
        assertFalse(target(empty).enabled());
        assertFalse(target(empty).focusable());
        assertTrue(emptyFocus.focused().isEmpty());
        assertTrue(empty.cursorPosition(emptyFocus).isEmpty());

        int columns = 30;
        SaveAsBarView.Placement clipped = SaveAsBarView.build(field, columns)
            .place(new Object(), 2, viewport(4, columns - 1));
        FocusManager<SaveAsBarView.Target> clippedFocus = new FocusManager<>();
        clipped.activate(clippedFocus);

        assertFalse(target(clipped).enabled());
        assertFalse(target(clipped).focusable());
        assertTrue(clippedFocus.focused().isEmpty());
        assertTrue(clipped.cursorPosition(clippedFocus).isEmpty());

        CellBuffer buffer = new CellBuffer(4, columns);
        clipped.paint(new Canvas(buffer), THEME, clippedFocus);
        assertEquals(DISABLED, buffer.get(2, clipped.fieldBounds().x()).style());
    }

    @Test
    void sameScopeBecomesFocusableAfterResizeWithoutDuplicatingFocusState() {
        int labelWidth = CellText.width(SaveAsBarView.DEFAULT_LABEL);
        Object scopeId = new Object();
        TextField field = new TextField();
        FocusManager<SaveAsBarView.Target> focus = new FocusManager<>();
        SaveAsBarView.Placement hidden = SaveAsBarView.build(field, labelWidth)
            .place(scopeId, 2, viewport(4, labelWidth));
        hidden.activate(focus);
        assertTrue(focus.focused().isEmpty());

        SaveAsBarView.Placement visible = SaveAsBarView.build(field, 30)
            .place(scopeId, 2, viewport(4, 30));
        visible.activate(focus);

        assertSame(scopeId, focus.activeScopeId().orElseThrow());
        assertEquals(SaveAsBarView.Target.FIELD, focus.focused().orElseThrow());
        assertTrue(visible.cursorPosition(focus).isPresent());
    }

    private static RoutedTarget<SaveAsBarView.Target, TextField.Action> target(
        SaveAsBarView.Placement placement
    ) {
        return placement.focusScope().target(SaveAsBarView.Target.FIELD).orElseThrow();
    }

    private static Rect viewport(int rows, int columns) {
        return new Rect(0, 0, columns, rows);
    }

    private static String visibleCellRow(CellBuffer buffer, int row) {
        StringBuilder visible = new StringBuilder(buffer.cols());
        for (int column = 0; column < buffer.cols(); column++) {
            Cell cell = buffer.get(row, column);
            visible.append(cell.continuation() ? "»" : cell.grapheme());
        }
        return visible.toString();
    }
}
