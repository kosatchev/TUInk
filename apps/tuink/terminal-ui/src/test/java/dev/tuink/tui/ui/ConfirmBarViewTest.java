package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetThemeKeys;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.ConfirmationAction;
import dev.tuink.tui.flow.ConfirmationShortcuts;
import java.util.List;
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
        ConfirmBarView view = ConfirmBarView.build("Confirm?", 1);
        ConfirmBarView.Placement placement = view
            .place(new Object(), 1, viewport(3, 1));
        FocusManager<ConfirmChoice> focus = new FocusManager<>();
        placement.activate(focus);

        assertTrue(focus.focused().isEmpty());
        assertFalse(target(placement, ConfirmChoice.SAVE).focusEligible());
        assertFalse(target(placement, ConfirmChoice.DISCARD).focusEligible());
        assertFalse(target(placement, ConfirmChoice.CANCEL).focusEligible());

        CellBuffer buffer = new CellBuffer(3, 1);
        CellText.draw(new Canvas(buffer), 0, 1, view.row(), NORMAL);
        placement.paint(new Canvas(buffer), THEME, focus);
        assertEquals("E", buffer.get(1, 0).grapheme(), "hidden buttons must not overwrite the resize hint");
    }

    @Test
    void missingOrClippedQuestionShowsAResizeHintInsteadOfActionButtons() {
        for (String prompt : new String[] {"", " ", "A question too long for the available prompt area"}) {
            ConfirmBarView view = ConfirmBarView.build(prompt, 40);
            ConfirmBarView.Placement placement = view.place(new Object(), 1, viewport(3, 40));

            assertTrue(view.row().contains("Enlarge"));
            assertTrue(view.row().contains("Esc cancels"));
            assertFalse(view.row().contains("[Save]"));
            assertFalse(placement.decisionsAvailable());
            for (ConfirmChoice choice : placement.focusScope().tabOrder()) {
                assertFalse(target(placement, choice).enabled());
            }
        }
    }

    @Test
    void clippedQuestionCannotLeaveVisibleDecisionButtonsActive() {
        ConfirmBarView.Placement placement = ConfirmBarView.build("Confirm?", 80)
            .place(new Object(), 1, new Rect(20, 0, 60, 3));

        assertFalse(placement.decisionsAvailable());
        assertFalse(target(placement, ConfirmChoice.SAVE).enabled());
    }

    @Test
    void compactRecoveryHintAdvertisesSafeEscapeCancellation() {
        ConfirmBarView view = ConfirmBarView.buildRecovery("Recover?", 20);

        assertTrue(view.row().contains("Enlarge"));
        assertTrue(view.row().contains("Esc cancels"));
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

    @Test
    void responsiveHintsUseTheRegistryForEveryConfirmationPolicy() {
        var cases = List.of(
            new HintCase(ConfirmationShortcuts.STANDARD, "Open", "Cancel", false, "Cancel",
                "Y=Open  N/Esc/Ctrl+Q=Cancel"),
            new HintCase(ConfirmationShortcuts.SAVE, "Save", "Discard", true, "Cancel",
                "Y/Ctrl+S=Save  N/Esc/Ctrl+Q=Cancel"),
            new HintCase(ConfirmationShortcuts.SAVE_CHANGES, "Save", "Discard", true, "Cancel",
                "Y/Ctrl+S=Save  N=Discard  Esc/Ctrl+Q=Cancel"),
            new HintCase(ConfirmationShortcuts.QUIT, "Save", "Discard", true, "Cancel",
                "Y/Ctrl+S=Save  N/Ctrl+Q=Discard  Esc=Cancel"),
            new HintCase(ConfirmationShortcuts.RECOVERY, "Restore", "Delete", true, "Later",
                "Y=Restore  D=Delete  N/Esc=Later  Ctrl+Q=Quit"));
        for (var example : cases) {
            var view = ConfirmBarView.responsive("Question?", 120, 2, example.primary(), example.secondary(),
                example.includeCancel(), example.cancel(), example.shortcuts());
            assertEquals(example.hint(), view.row().lines().findFirst().orElseThrow().stripTrailing(),
                example.shortcuts().name());
            assertTrue(view.row().contains(example.shortcuts().keysFor(ConfirmChoice.SAVE) + "=" + example.primary()));
            if (example.includeCancel() && !example.shortcuts().keysFor(ConfirmChoice.DISCARD).isEmpty())
                assertTrue(view.row().contains(example.shortcuts().keysFor(ConfirmChoice.DISCARD) + "=" + example.secondary()));
            assertTrue(view.row().contains(example.shortcuts().keysFor(ConfirmChoice.CANCEL) + "=" + example.cancel()));
            if (example.shortcuts() == ConfirmationShortcuts.RECOVERY)
                assertTrue(view.row().contains(example.shortcuts().keysFor(ConfirmationAction.Command.QUIT)
                    + "=" + ConfirmationAction.Command.QUIT.label()));
            else {
                assertFalse(view.row().contains("D="));
                assertFalse(view.row().contains("Ctrl+Q=Quit"));
            }
            assertFalse(view.row().contains("[Quit]"), "Shortcut commands do not add focusable buttons");
        }
    }

    @Test
    void recoveryAtSeventyColumnsHasTwoCompleteRowsAndAllThreePaintedTargets() {
        var view = ConfirmBarView.responsive("Unsaved drafts: 3.", 70, 2,
            "Restore", "Delete", true, "Later", ConfirmationShortcuts.RECOVERY);
        var rows = view.row().lines().toList();
        assertEquals(2, rows.size());
        assertEquals("Y=Restore  D=Delete  N/Esc=Later  Ctrl+Q=Quit", rows.get(0).stripTrailing());
        assertTrue(rows.get(1).contains("Unsaved drafts: 3."));
        assertTrue(rows.get(1).contains("[Restore] [Delete] [Later]"));
        for (var row : rows) assertTrue(CellText.width(row) <= 70);
        assertFalse(view.row().contains("Enlarge"));
        assertFalse(view.row().contains("[Quit]"));

        var placement = view.place(new Object(), 0, viewport(2, 70));
        assertTrue(placement.decisionsAvailable());
        assertEquals(List.of(ConfirmChoice.SAVE, ConfirmChoice.DISCARD, ConfirmChoice.CANCEL),
            placement.focusScope().tabOrder());
        for (var choice : placement.focusScope().tabOrder()) assertTrue(target(placement, choice).focusEligible());
        var focus = new FocusManager<ConfirmChoice>();
        placement.activate(focus);
        assertEquals(ConfirmChoice.CANCEL, focus.focused().orElseThrow());
        var cells = new CellBuffer(2, 70);
        var canvas = new Canvas(cells);
        for (int row = 0; row < rows.size(); row++) CellText.draw(canvas, 0, row, rows.get(row), NORMAL);
        placement.paint(canvas, THEME, focus);
        assertEquals(rows.get(0).stripTrailing(), rowText(cells, 0).stripTrailing());
        assertButtonStyle(cells, placement, ConfirmChoice.SAVE, NORMAL);
        assertButtonStyle(cells, placement, ConfirmChoice.DISCARD, NORMAL);
        assertButtonStyle(cells, placement, ConfirmChoice.CANCEL, FOCUSED);
    }

    @Test
    void recoveryDoesNotOfferDeleteWhenOnlyOneOfItsRequiredRowsIsAvailable() {
        var view = ConfirmBarView.responsive("Unsaved drafts: 3.", 70, 1,
            "Restore", "Delete", true, "Later", ConfirmationShortcuts.RECOVERY);
        var placement = view.place(new Object(), 0, viewport(1, 70));
        assertTrue(view.row().contains("Enlarge"));
        assertFalse(view.row().contains("D=Delete"));
        assertFalse(placement.decisionsAvailable());
        for (var choice : placement.focusScope().tabOrder()) assertFalse(target(placement, choice).enabled());
    }

    private record HintCase(ConfirmationShortcuts shortcuts, String primary, String secondary,
        boolean includeCancel, String cancel, String hint) {}

    private static String rowText(CellBuffer cells, int row) {
        var text = new StringBuilder();
        for (int column = 0; column < cells.cols(); column++) text.append(cells.get(row, column).grapheme());
        return text.toString();
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
