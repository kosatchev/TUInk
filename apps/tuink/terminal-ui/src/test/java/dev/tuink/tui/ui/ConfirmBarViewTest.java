package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.CellText;
import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.style.CellColor;
import dev.tuink.terminal.style.CellStyle;
import dev.tuink.terminal.widget.Button;
import dev.tuink.terminal.widget.FocusManager;
import dev.tuink.terminal.widget.RoutedTarget;
import dev.tuink.terminal.widget.Theme;
import dev.tuink.terminal.widget.WidgetThemeKeys;
import dev.tuink.tui.flow.ConfirmChoice;
import org.junit.jupiter.api.Test;

class ConfirmBarViewTest {
    private static final CellStyle NORMAL = new CellStyle(
        CellColor.BRIGHT_WHITE,
        CellColor.RED,
        false,
        false
    );
    private static final CellStyle FOCUSED = new CellStyle(
        CellColor.BRIGHT_WHITE,
        CellColor.RED,
        true,
        false
    );
    private static final Theme THEME = role -> WidgetThemeKeys.BUTTON_FOCUSED.equals(role)
        ? FOCUSED
        : NORMAL;

    @Test
    void paintAndRoutingUseOneFocusSnapshotForAllButtons() {
        ConfirmBarView.Placement placement = ConfirmBarView.build("Quit?", 80)
            .place(new Object(), 4, viewport(6, 80));
        FocusManager<ConfirmChoice> focus = new FocusManager<>();
        placement.activate(focus);

        CellBuffer initial = new CellBuffer(6, 80);
        Canvas initialCanvas = new Canvas(initial);
        placement.paint(initialCanvas, THEME, focus);

        assertButtonStyle(initial, placement, ConfirmChoice.SAVE, NORMAL);
        assertButtonStyle(initial, placement, ConfirmChoice.DISCARD, NORMAL);
        assertButtonStyle(initial, placement, ConfirmChoice.CANCEL, FOCUSED);
        assertTrue(initialCanvas.put(0, 0, "Z", NORMAL), "button paint must restore the parent clip");

        focus.focus(placement.focusScope(), ConfirmChoice.SAVE);
        CellBuffer moved = new CellBuffer(6, 80);
        placement.paint(new Canvas(moved), THEME, focus);

        assertButtonStyle(moved, placement, ConfirmChoice.SAVE, FOCUSED);
        assertButtonStyle(moved, placement, ConfirmChoice.DISCARD, NORMAL);
        assertButtonStyle(moved, placement, ConfirmChoice.CANCEL, NORMAL);
    }

    @Test
    void unicodeLabelsKeepCellGeometryAndSemanticIds() {
        int cols = 42;
        ConfirmBarView view = ConfirmBarView.build(
            "Несохранённые данные",
            cols,
            "保存",
            "放棄",
            true,
            "取消"
        );
        ConfirmBarView.Placement placement = view.place(new Object(), 2, viewport(4, cols));

        assertEquals(cols, CellText.width(view.row()));
        assertEquals(
            CellText.width("[保存]"),
            placement.focusScope().target(ConfirmChoice.SAVE).orElseThrow().placement().bounds().width()
        );
        assertEquals(
            CellText.width("[放棄]"),
            placement.focusScope().target(ConfirmChoice.DISCARD).orElseThrow().placement().bounds().width()
        );
        assertEquals(3, placement.focusScope().tabOrder().size());
    }

    @Test
    void tinyViewportDoesNotActivatePartiallyVisibleDangerousTargets() {
        ConfirmBarView.Placement placement = ConfirmBarView.build("Confirm?", 1)
            .place(new Object(), 1, viewport(3, 1));
        FocusManager<ConfirmChoice> focus = new FocusManager<>();
        placement.activate(focus);

        assertTrue(focus.focused().isEmpty());
        assertFalse(target(placement, ConfirmChoice.SAVE).focusable());
        assertFalse(target(placement, ConfirmChoice.DISCARD).focusable());
        assertFalse(target(placement, ConfirmChoice.CANCEL).focusable());

        CellBuffer buffer = new CellBuffer(3, 1);
        placement.paint(new Canvas(buffer), THEME, focus);
        assertEquals("[", buffer.get(1, 0).grapheme());
    }

    @Test
    void emptyViewportHasNoFocusedTargetAndPaintIsSafe() {
        ConfirmBarView.Placement placement = ConfirmBarView.build("Confirm?", 0)
            .place(new Object(), 0, viewport(0, 0));
        FocusManager<ConfirmChoice> focus = new FocusManager<>();
        placement.activate(focus);

        assertTrue(focus.focused().isEmpty());
        placement.paint(new Canvas(new CellBuffer(0, 0)), THEME, focus);
    }

    @Test
    void paintDoesNotEnterOrMutateFocusScope() {
        ConfirmBarView.Placement placement = ConfirmBarView.build("Quit?", 80)
            .place(new Object(), 4, viewport(6, 80));
        FocusManager<ConfirmChoice> focus = new FocusManager<>();

        placement.paint(new Canvas(new CellBuffer(6, 80)), THEME, focus);

        assertTrue(focus.activeScopeId().isEmpty());
        assertTrue(focus.focused().isEmpty());
    }

    @Test
    void actionClusterUsesTheRightmostMatchingText() {
        String cluster = "[Save] [Discard] [Cancel]";
        ConfirmBarView view = ConfirmBarView.build(cluster + " still pending", 80);
        ConfirmBarView.Placement placement = view.place(new Object(), 4, viewport(6, 80));

        int saveColumn = target(placement, ConfirmChoice.SAVE).placement().bounds().x();
        assertTrue(saveColumn > CellText.width(cluster));
    }

    private static void assertButtonStyle(
        CellBuffer buffer,
        ConfirmBarView.Placement placement,
        ConfirmChoice choice,
        CellStyle expected
    ) {
        RoutedTarget<ConfirmChoice, Button.Action> target = target(placement, choice);
        int row = target.placement().bounds().y();
        int col = target.placement().bounds().x();
        assertEquals("[", buffer.get(row, col).grapheme());
        assertEquals(expected, buffer.get(row, col).style());
    }

    private static RoutedTarget<ConfirmChoice, Button.Action> target(
        ConfirmBarView.Placement placement,
        ConfirmChoice choice
    ) {
        return placement.focusScope().target(choice).orElseThrow();
    }

    private static Rect viewport(int rows, int cols) {
        return new Rect(0, 0, cols, rows);
    }
}
