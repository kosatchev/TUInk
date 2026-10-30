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
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.PlacedInteractiveWidget;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetThemeKeys;
import java.util.List;
import java.util.Set;
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
    private static final CellStyle BUTTON = CellStyle.fg(CellColor.GREEN);
    private static final CellStyle BUTTON_FOCUSED = new CellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE, true, true);
    private static final Theme THEME = role -> {
        if (WidgetThemeKeys.TEXT_FIELD_FOCUSED.equals(role)) {
            return FOCUSED;
        }
        if (WidgetThemeKeys.TEXT_FIELD_DISABLED.equals(role)) {
            return DISABLED;
        }
        if (WidgetThemeKeys.BUTTON.equals(role)) {
            return BUTTON;
        }
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(role)) {
            return BUTTON_FOCUSED;
        }
        return NORMAL;
    };

    @Test
    void mappedButtonsCannotFocusOrActivateWhenTheirOwnBoundsClipTheLabel() {
        var button = new Button("Save").layout(new Rect(2, 1, 1, 1));
        List<PlacedInteractiveWidget<?>> mapped = List.of(
            new SaveAsBarView.ButtonPlacement(button, SaveAsBarView.Action.SUBMITTED),
            new SearchBarView.ButtonPlacement(button, SearchBarView.Action.REPLACE));
        for (var placement : mapped) {
            var target = new RoutedTarget<>("action", placement, FocusSpace.root(viewport(24, 80)));
            var scope = FocusScope.single(new Object(), FocusResetPolicy.FIRST_FOCUSABLE, target);
            FocusManager<String> focus = new FocusManager<>();
            focus.focus(scope, "action");

            assertTrue(target.space().parentClip().contains(placement.bounds()));
            assertFalse(placement.interactionReady());
            assertFalse(target.interactive());
            assertFalse(target.focusEligible());
            assertTrue(focus.focused().isEmpty());
            for (TuiInputEvent event : List.of(KeyStroke.of(KeyStroke.Key.ENTER),
                KeyStroke.of(KeyStroke.Key.SPACE), new TextInput(" "),
                new PointerEvent(1, 2, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of()))) {
                var route = focus.route(scope, event);
                if (route.target().isPresent()) {
                    var interaction = route.target().orElseThrow().placement()
                        .handle(event, route.context().orElseThrow());
                    assertTrue(interaction.output().isEmpty());
                }
                assertTrue(focus.focused().isEmpty());
            }
        }
    }

    @Test
    void rasterRowContainsOnlyLabelAndControlsShareOneRowWithoutOverlapping() {
        int columns = 30;
        TextField field = new TextField();
        field.set("secret.txt");
        SaveAsBarView view = SaveAsBarView.build(field, columns);
        SaveAsBarView.Placement placement = view.place(new Object(), 3, viewport(5, columns));
        RoutedTarget<SaveAsBarView.Target, SaveAsBarView.Action> target = target(placement);

        assertEquals(CellText.fit(SaveAsBarView.DEFAULT_LABEL, columns), view.row());
        assertEquals(view.row(), placement.row());
        assertFalse(view.row().contains(field.text()));
        assertEquals(CellText.width(SaveAsBarView.DEFAULT_LABEL), target.placement().bounds().x());
        assertEquals(3, target.placement().bounds().y());
        assertEquals(4, target.placement().bounds().width());
        assertEquals(placement.fieldBounds(), target.placement().bounds());
        assertTrue(target.interactive());
        assertTrue(target.focusEligible());
        assertEquals(new Rect(14, 3, 6, 1), placement.targetBounds(SaveAsBarView.Target.SAVE));
        assertEquals(new Rect(21, 3, 8, 1), placement.targetBounds(SaveAsBarView.Target.CANCEL));
        assertTrue(placement.fieldBounds().rightExclusive() < placement.targetBounds(SaveAsBarView.Target.SAVE).x());
        assertEquals(List.of(SaveAsBarView.Target.FIELD, SaveAsBarView.Target.SAVE, SaveAsBarView.Target.CANCEL),
            placement.focusScope().tabOrder());
    }

    @Test
    void buttonsUseTheSuppliedThemeAndShowNoTextCursorWhenFocused() {
        int columns = 48;
        TextField field = new TextField();
        field.set("draft.txt");
        SaveAsBarView.Placement placement = SaveAsBarView.build(field, columns)
            .place(new Object(), 2, viewport(4, columns));
        FocusManager<SaveAsBarView.Target> focus = new FocusManager<>();
        placement.activate(focus);
        focus.focus(placement.focusScope(), SaveAsBarView.Target.SAVE);
        CellBuffer buffer = new CellBuffer(4, columns);
        Canvas canvas = new Canvas(buffer);
        CellText.draw(canvas, 0, 2, placement.row(), PROMPT);
        placement.paint(canvas, THEME, focus);

        assertTrue(visibleCellRow(buffer, 2).endsWith("[Save] [Cancel] "));
        assertEquals(BUTTON_FOCUSED, buffer.get(2, placement.targetBounds(SaveAsBarView.Target.SAVE).x()).style());
        assertEquals(BUTTON, buffer.get(2, placement.targetBounds(SaveAsBarView.Target.CANCEL).x()).style());
        assertEquals(NORMAL, buffer.get(2, placement.fieldBounds().x()).style());
        assertTrue(placement.cursorPosition(focus).isEmpty());
        focus.focus(placement.focusScope(), SaveAsBarView.Target.CANCEL);
        assertTrue(placement.cursorPosition(focus).isEmpty());
        focus.focus(placement.focusScope(), SaveAsBarView.Target.FIELD);
        assertTrue(placement.cursorPosition(focus).isPresent());
        assertTrue(canvas.put(0, 0, "Z", NORMAL), "all control painters must restore the parent clip");
    }

    @Test
    void widthThresholdPreservesAUsableFieldBeforeShowingBothCompleteButtons() {
        TextField field = new TextField();
        int minimum = CellText.width(SaveAsBarView.DEFAULT_LABEL) + 3 + 1 + "[Save] [Cancel]".length() + 1;
        var narrow = SaveAsBarView.build(field, minimum - 1).place(new Object(), 2, viewport(4, minimum - 1));
        assertEquals(minimum - 1 - CellText.width(SaveAsBarView.DEFAULT_LABEL), narrow.fieldBounds().width());
        for (SaveAsBarView.Target id : List.of(SaveAsBarView.Target.SAVE, SaveAsBarView.Target.CANCEL)) {
            assertTrue(narrow.targetBounds(id).isEmpty());
            assertTrue(narrow.focusScope().target(id).orElseThrow().enabled());
            assertFalse(narrow.focusScope().target(id).orElseThrow().interactive());
            assertFalse(narrow.focusScope().target(id).orElseThrow().focusEligible());
        }
        var full = SaveAsBarView.build(field, minimum).place(new Object(), 2, viewport(4, minimum));
        assertEquals(3, full.fieldBounds().width());
        assertTrue(full.submissionAvailable());
        assertEquals(minimum - 1, full.targetBounds(SaveAsBarView.Target.CANCEL).rightExclusive());
        assertTrue(full.focusScope().tabOrder().stream()
            .map(id -> full.focusScope().target(id).orElseThrow()).allMatch(RoutedTarget::interactive));
    }

    @Test
    void growingFromTwentySixColumnsKeepsTheFilenameVisibleBeforeOfferingButtons() {
        TextField field = new TextField();
        field.set("document.txt");
        for (int columns : List.of(26, 27, 28, 29)) {
            var placement = SaveAsBarView.build(field, columns).place(new Object(), 2, viewport(4, columns));
            assertEquals(columns < 29 ? columns - 9 : 3, placement.fieldBounds().width());
            assertTrue(placement.submissionAvailable());
            assertEquals(columns >= 29, placement.focusScope().target(SaveAsBarView.Target.SAVE)
                .orElseThrow().interactive());
        }
    }

    @Test
    void clippingTheFilenameDisablesSaveEvenWhenBothButtonsAreCompletelyVisible() {
        TextField field = new TextField();
        field.set("draft.txt");
        SaveAsBarView view = SaveAsBarView.build(field, 48);
        var full = view.place(new Object(), 2, viewport(4, 48));
        int left = full.fieldBounds().rightExclusive() - 1;
        var clipped = view.place(new Object(), 2, new Rect(left, 0, 48 - left, 4));

        assertTrue(target(clipped).enabled());
        assertFalse(target(clipped).interactive());
        assertFalse(clipped.submissionAvailable());
        assertFalse(clipped.focusScope().target(SaveAsBarView.Target.SAVE).orElseThrow().enabled());
        assertTrue(clipped.focusScope().target(SaveAsBarView.Target.CANCEL).orElseThrow().interactive());
    }

    @Test
    void sameScopeKeepsButtonFocusUntilResizeHidesItAndPreservesExplicitContentOwnership() {
        Object scope = new Object();
        TextField field = new TextField();
        field.set("draft-😀.txt");
        field.moveLeft();
        int caret = field.cursor();
        var focus = new FocusManager<SaveAsBarView.Target>();
        var wide = SaveAsBarView.build(field, 48).place(scope, 2, viewport(4, 48));
        wide.activate(focus);
        focus.focus(wide.focusScope(), SaveAsBarView.Target.CANCEL);
        var resized = SaveAsBarView.build(field, 60).place(scope, 2, viewport(4, 60));
        resized.activate(focus);
        assertEquals(SaveAsBarView.Target.CANCEL, focus.focused().orElseThrow());
        assertTrue(resized.cursorPosition(focus).isEmpty());

        var narrow = SaveAsBarView.build(field, 24).place(scope, 2, viewport(4, 24));
        narrow.activate(focus);
        assertEquals(SaveAsBarView.Target.FIELD, focus.focused().orElseThrow());
        assertEquals("draft-😀.txt", field.text());
        assertEquals(caret, field.cursor());
        focus.clear(narrow.focusScope());
        resized.activate(focus);
        assertTrue(focus.focused().isEmpty(), "reconciliation must not steal explicitly cleared document focus");
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
        assertFalse(tiny.submissionAvailable());
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
        assertFalse(target(empty).interactive());
        assertFalse(target(empty).focusEligible());
        assertTrue(emptyFocus.focused().isEmpty());
        assertTrue(empty.cursorPosition(emptyFocus).isEmpty());

        int columns = 30;
        SaveAsBarView full = SaveAsBarView.build(field, columns);
        int fieldEnd = full.place(new Object(), 2, viewport(4, columns)).fieldBounds().rightExclusive();
        SaveAsBarView.Placement clipped = full.place(new Object(), 2, viewport(4, fieldEnd - 1));
        FocusManager<SaveAsBarView.Target> clippedFocus = new FocusManager<>();
        clipped.activate(clippedFocus);

        assertFalse(target(clipped).interactive());
        assertFalse(target(clipped).focusEligible());
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

    private static RoutedTarget<SaveAsBarView.Target, SaveAsBarView.Action> target(
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
