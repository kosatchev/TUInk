package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.TextField;
import dev.tuink.core.EditorClipboard;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.ui.SaveAsBarView;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SaveAsInputRouterTest {
    @Test
    void tabCyclesThroughFieldSaveAndCancelInBothDirectionsWhileNarrowRowsSkipButtons() {
        Fixture fixture = fixture(48, new Object());
        fixture.field.set("draft-😀.txt");
        fixture.field.moveLeft();
        int caret = fixture.field.cursor();
        for (SaveAsBarView.Target expected : List.of(SaveAsBarView.Target.SAVE, SaveAsBarView.Target.CANCEL,
            SaveAsBarView.Target.FIELD)) {
            assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.TAB)));
            assertEquals(expected, fixture.focus.focused().orElseThrow());
        }
        for (SaveAsBarView.Target expected : List.of(SaveAsBarView.Target.CANCEL, SaveAsBarView.Target.SAVE,
            SaveAsBarView.Target.FIELD)) {
            assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT)));
            assertEquals(expected, fixture.focus.focused().orElseThrow());
        }
        assertEquals("draft-😀.txt", fixture.field.text());
        assertEquals(caret, fixture.field.cursor());

        Fixture narrow = fixture(24, new Object());
        for (KeyStroke stroke : List.of(KeyStroke.of(KeyStroke.Key.TAB),
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT))) {
            assertCaptured(narrow.route(stroke));
            assertEquals(SaveAsBarView.Target.FIELD, narrow.focus.focused().orElseThrow());
        }
    }

    @Test
    void visibleButtonsEmitSubmitOrCancelForEnterSpaceAndClickWithoutEditingTheFilename() {
        for (SaveAsBarView.Target target : List.of(SaveAsBarView.Target.SAVE, SaveAsBarView.Target.CANCEL)) {
            SaveAsBarView.Action expected = target == SaveAsBarView.Target.SAVE
                ? SaveAsBarView.Action.SUBMITTED : SaveAsBarView.Action.CANCELLED;
            for (TuiInputEvent event : List.of(KeyStroke.of(KeyStroke.Key.ENTER),
                KeyStroke.of(KeyStroke.Key.SPACE), new TextInput(" "))) {
                Fixture fixture = fixture(48, new Object());
                fixture.field.set("draft-中.txt");
                fixture.focus.focus(fixture.placement.focusScope(), target);
                assertAction(expected, fixture.route(event));
                assertEquals("draft-中.txt", fixture.field.text());
            }
            Fixture fixture = fixture(48, new Object());
            fixture.field.set("clicked.txt");
            Rect button = fixture.placement.targetBounds(target);
            assertAction(expected, fixture.route(pointer(button.y(), button.x(),
                PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT)));
            assertEquals(target, fixture.focus.focused().orElseThrow());
            assertEquals("clicked.txt", fixture.field.text());
        }
    }

    @Test
    void buttonFocusCapturesTextPasteAndCaretKeysWithoutChangingTheField() {
        for (SaveAsBarView.Target target : List.of(SaveAsBarView.Target.SAVE, SaveAsBarView.Target.CANCEL)) {
            Fixture fixture = fixture(48, new Object());
            fixture.field.set("draft-😀.txt");
            fixture.field.moveLeft();
            int caret = fixture.field.cursor();
            fixture.focus.focus(fixture.placement.focusScope(), target);
            for (TuiInputEvent event : List.of(new TextInput("accidental typing"), new PasteEvent("paste\nvalue"),
                new PasteEvent("partial", true), KeyStroke.of(KeyStroke.Key.BACKSPACE),
                KeyStroke.of(KeyStroke.Key.DELETE), KeyStroke.of(KeyStroke.Key.HOME), KeyStroke.of(KeyStroke.Key.END))) {
                assertCaptured(fixture.route(event));
                assertEquals(target, fixture.focus.focused().orElseThrow());
                assertEquals("draft-😀.txt", fixture.field.text());
                assertEquals(caret, fixture.field.cursor());
            }
        }
        Fixture fixture = fixture(48, new Object());
        assertAction(SaveAsBarView.Action.VALUE_CHANGED, fixture.route(KeyStroke.of(KeyStroke.Key.SPACE)));
        assertEquals(" ", fixture.field.text(), "Space in the filename remains text rather than activating Save");
    }

    @Test
    void partiallyClippedButtonsCannotBeActivatedOrFocusedThroughTheirVisibleFragment() {
        for (SaveAsBarView.Target target : List.of(SaveAsBarView.Target.SAVE, SaveAsBarView.Target.CANCEL)) {
            TextField field = new TextField();
            field.set("draft.txt");
            SaveAsBarView view = SaveAsBarView.build(field, 48);
            Rect bounds = view.place(new Object(), 2, viewport(4, 48)).targetBounds(target);
            var placement = view.place(new Object(), 2, viewport(4, bounds.rightExclusive() - 1));
            var focus = new FocusManager<SaveAsBarView.Target>();
            Fixture fixture = new Fixture(field, focus, placement);
            placement.activate(focus);

            assertCaptured(fixture.route(pointer(bounds.y(), bounds.x(), PointerEvent.Kind.PRESS,
                PointerEvent.Button.LEFT)));
            assertEquals(SaveAsBarView.Target.FIELD, focus.focused().orElseThrow());
            assertEquals("draft.txt", field.text());
            assertFalse(placement.focusScope().target(target).orElseThrow().interactive());
        }
    }

    @Test
    void routesUnicodeTextCaretEditingAndSubmitThroughTheField() {
        Fixture fixture = fixture(32, new Object());

        assertAction(SaveAsBarView.Action.VALUE_CHANGED, fixture.route(new TextInput("A😀")));
        assertEquals("A😀", fixture.field.text());
        assertEquals(3, fixture.field.cursor());

        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_LEFT)));
        assertEquals(1, fixture.field.cursor());
        assertAction(
            SaveAsBarView.Action.VALUE_CHANGED,
            fixture.route(KeyStroke.of(KeyStroke.Key.BACKSPACE))
        );
        assertEquals("😀", fixture.field.text());
        assertEquals(0, fixture.field.cursor());

        assertAction(SaveAsBarView.Action.SUBMITTED, fixture.route(KeyStroke.of(KeyStroke.Key.ENTER)));
        assertEquals("😀", fixture.field.text(), "submit must not mutate the field value");
    }

    @Test
    void completePasteUsesSingleLinePolicyAndTruncatedPasteNeverPartiallyMutates() {
        Fixture fixture = fixture(40, new Object());

        assertAction(
            SaveAsBarView.Action.VALUE_CHANGED,
            fixture.route(new PasteEvent("first\r\n\tsecond\u0000 😀\n", false))
        );
        assertEquals("first second 😀", fixture.field.text());

        String before = fixture.field.text();
        int cursorBefore = fixture.field.cursor();
        assertAction(
            SaveAsBarView.Action.INPUT_REJECTED,
            fixture.route(new PasteEvent("partial", true))
        );
        assertEquals(before, fixture.field.text());
        assertEquals(cursorBefore, fixture.field.cursor());

        assertCaptured(fixture.route(new PasteEvent("\r\n\t", false)));
        assertEquals(before, fixture.field.text());
    }

    @Test
    void applicationCancelAndUnownedCommandsBubbleToTheCommandRouter() {
        Fixture fixture = fixture(32, new Object());
        fixture.field.set("draft");

        assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.ESCAPE)));
        assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)));
        assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.ESCAPE, KeyStroke.Modifier.SHIFT)));
        assertIgnored(fixture.route(
            KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT)
        ));
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT)
        ));
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT)
        ));
        assertIgnored(fixture.route(
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.CTRL)
        ));
        assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.F1)));

        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.TAB)));
        assertEquals(SaveAsBarView.Target.FIELD, fixture.focus.focused().orElseThrow());
        assertEquals("draft", fixture.field.text());
        assertEquals("draf".length(), fixture.field.cursor());
    }

    @Test
    void caretBoundaryNoOpsAreCapturedWithoutFallingThroughForSecondHandling() {
        Fixture fixture = fixture(24, new Object());

        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_LEFT)));
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.BACKSPACE)));
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.DELETE)));
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.HOME)));
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.END)));
        assertEquals("", fixture.field.text());
        assertEquals(0, fixture.field.cursor());
    }

    @Test
    void filenameFieldUsesSharedWordNavigationSelectionAndReplacement() {
        Fixture fixture = fixture(48, new Object());
        fixture.field.set("old name.txt");
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.CTRL)));
        assertEquals("old name.".length(), fixture.field.cursor());
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.HOME, KeyStroke.Modifier.SHIFT)));
        assertEquals("old name.", fixture.field.selectedText());
        assertAction(SaveAsBarView.Action.VALUE_CHANGED, fixture.route(new TextInput("new.")));
        assertEquals("new.txt", fixture.field.text());
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL)));
        assertEquals("new.txt", fixture.field.selectedText());
        assertAction(SaveAsBarView.Action.VALUE_CHANGED, fixture.route(new TextInput("renamed.md")));
        assertEquals("renamed.md", fixture.field.text());
        assertEquals(SaveAsBarView.Target.FIELD, fixture.focus.focused().orElseThrow());
    }

    @Test
    void draggingFilenameOverButtonsAndOutsideThePromptSelectsWithoutSubmitting() {
        TextField field = new TextField();
        field.set("draft-👩‍💻.txt");
        Fixture fixture = fixture(field, 48, new Object());
        Rect bounds = fixture.placement.fieldBounds();
        assertCaptured(fixture.route(pointer(bounds.y(), bounds.x() + 6,
            PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT)));
        Rect save = fixture.placement.targetBounds(SaveAsBarView.Target.SAVE);
        assertCaptured(fixture.route(pointer(save.y(), save.x(),
            PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT)));
        assertEquals("👩‍💻.txt", field.selectedText());
        assertEquals(SaveAsBarView.Target.FIELD, fixture.focus.focused().orElseThrow());
        assertTrue(fixture.capture.active());
        assertCaptured(fixture.route(pointer(23, 79, PointerEvent.Kind.RELEASE, PointerEvent.Button.NONE)));
        assertFalse(field.mouseSelecting());
        assertEquals("👩‍💻.txt", field.selectedText());
    }

    @Test
    void shiftClickFilenameUsesSharedSelectionAndTabCancelsItsCapture() {
        TextField field = new TextField();
        field.set("draft.txt");
        Fixture fixture = fixture(field, 48, new Object());
        Rect bounds = fixture.placement.fieldBounds();
        fixture.route(pointer(bounds.y(), bounds.x() + 2, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT));
        fixture.route(pointer(bounds.y(), bounds.x() + 2, PointerEvent.Kind.RELEASE, PointerEvent.Button.LEFT));
        assertCaptured(fixture.route(new PointerEvent(bounds.y(), bounds.x() + 5,
            PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of(KeyStroke.Modifier.SHIFT))));
        assertEquals("aft", field.selectedText());
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.TAB)));
        assertFalse(field.mouseSelecting());
        assertEquals(SaveAsBarView.Target.SAVE, fixture.focus.focused().orElseThrow());
        assertCaptured(fixture.route(pointer(0, 0, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT)));
        assertEquals("aft", field.selectedText());
    }

    @Test
    void pointerHitAppliesSelfFocusAndEveryPointerMissRemainsModal() {
        TextField field = new TextField();
        field.set("abcd");
        Fixture fixture = fixture(field, 48, new Object());
        fixture.focus.clear(fixture.placement.focusScope());
        int row = fixture.placement.fieldBounds().y();
        int start = fixture.placement.fieldBounds().x();

        assertCaptured(fixture.route(pointer(
            row,
            start + 1,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT
        )));
        assertEquals(SaveAsBarView.Target.FIELD, fixture.focus.focused().orElseThrow());
        assertEquals(1, fixture.field.cursor());

        assertCaptured(fixture.route(pointer(
            0,
            0,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT
        )));
        assertCaptured(fixture.route(pointer(
            row,
            start,
            PointerEvent.Kind.RELEASE,
            PointerEvent.Button.LEFT
        )));
        assertCaptured(fixture.route(pointer(
            0,
            0,
            PointerEvent.Kind.SCROLL_DOWN,
            PointerEvent.Button.NONE
        )));
    }

    @Test
    void hiddenFieldCapturesRawInputWithoutMutationOrInvisibleSubmit() {
        int labelWidth = CellText.width(SaveAsBarView.DEFAULT_LABEL);
        TextField field = new TextField();
        field.set("draft");
        Fixture fixture = fixture(field, labelWidth, new Object());

        assertTrue(fixture.focus.focused().isEmpty());
        assertCaptured(fixture.route(new TextInput("hidden")));
        assertCaptured(fixture.route(new PasteEvent("hidden", false)));
        assertAction(SaveAsBarView.Action.SUBMIT_REJECTED, fixture.route(KeyStroke.of(KeyStroke.Key.ENTER)));
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.BACKSPACE, KeyStroke.Modifier.SHIFT)
        ));
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.DELETE, KeyStroke.Modifier.CTRL)
        ));
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT)
        ));
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.F1)));
        assertAction(
            SaveAsBarView.Action.INPUT_REJECTED,
            fixture.route(new PasteEvent("partial", true))
        );
        assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.ESCAPE)));
        assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)));
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ESCAPE, KeyStroke.Modifier.SHIFT)));
        assertCaptured(fixture.route(
            KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT)
        ));
        assertEquals("draft", fixture.field.text());
        assertEquals("draft".length(), fixture.field.cursor());
        assertTrue(fixture.focus.focused().isEmpty());
    }

    @Test
    void relayoutWithSameScopeRestoresFocusAndEditingWhenFieldBecomesVisible() {
        int labelWidth = CellText.width(SaveAsBarView.DEFAULT_LABEL);
        Object scopeId = new Object();
        TextField field = new TextField();
        Fixture hidden = fixture(field, labelWidth, scopeId);
        assertCaptured(hidden.route(new TextInput("ignored")));
        assertEquals("", field.text());

        SaveAsBarView.Placement visiblePlacement = SaveAsBarView.build(field, 30)
            .place(scopeId, 2, viewport(4, 30));
        Fixture visible = new Fixture(field, hidden.focus, visiblePlacement);

        assertAction(SaveAsBarView.Action.VALUE_CHANGED, visible.route(new TextInput("visible")));
        assertEquals("visible", field.text());
        assertEquals(SaveAsBarView.Target.FIELD, visible.focus.focused().orElseThrow());
    }

    @Test
    void aCaretOnlyOrTooNarrowFilenameCannotSubmitThroughPhysicalOrSemanticEnter() {
        for (int columns : List.of(10, 11)) {
            TextField field = new TextField();
            field.set("draft-中😀.txt");
            Fixture fixture = fixture(field, columns, new Object());
            int caret = field.cursor();

            assertAction(SaveAsBarView.Action.SUBMIT_REJECTED, fixture.route(KeyStroke.of(KeyStroke.Key.ENTER)));
            assertAction(SaveAsBarView.Action.SUBMIT_REJECTED, SaveAsInputRouter.routeCommand(fixture.focus,
                fixture.placement, TuinkCommand.EDIT_INSERT_NEWLINE, new EditorClipboard()));
            assertEquals("draft-中😀.txt", field.text());
            assertEquals(caret, field.cursor());
            assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.ESCAPE)));
            assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)));
        }
    }

    @Test
    void saveCannotBypassAClippedFilenameWhileCancelRemainsAvailable() {
        TextField field = new TextField();
        field.set("draft.txt");
        SaveAsBarView view = SaveAsBarView.build(field, 48);
        var full = view.place(new Object(), 2, viewport(4, 48));
        int left = full.fieldBounds().rightExclusive() - 1;
        var clipped = view.place(new Object(), 2, new Rect(left, 0, 48 - left, 4));
        var focus = new FocusManager<SaveAsBarView.Target>();
        Fixture fixture = new Fixture(field, focus, clipped);
        clipped.activate(focus);
        Rect save = clipped.targetBounds(SaveAsBarView.Target.SAVE);

        assertFalse(clipped.focusScope().target(SaveAsBarView.Target.SAVE).orElseThrow().enabled());
        assertCaptured(fixture.route(pointer(save.y(), save.x(), PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT)));
        assertEquals("draft.txt", field.text());
        assertEquals(SaveAsBarView.Target.CANCEL, focus.focused().orElseThrow());
        assertAction(SaveAsBarView.Action.CANCELLED, fixture.route(KeyStroke.of(KeyStroke.Key.ENTER)));
    }

    private static Fixture fixture(int columns, Object scopeId) {
        return fixture(new TextField(), columns, scopeId);
    }

    private static Fixture fixture(TextField field, int columns, Object scopeId) {
        SaveAsBarView.Placement placement = SaveAsBarView.build(field, columns)
            .place(scopeId, 2, viewport(4, columns));
        FocusManager<SaveAsBarView.Target> focus = new FocusManager<>();
        placement.activate(focus);
        return new Fixture(field, focus, placement);
    }

    private static Rect viewport(int rows, int columns) {
        return new Rect(0, 0, columns, rows);
    }

    private static PointerEvent pointer(
        int row,
        int column,
        PointerEvent.Kind kind,
        PointerEvent.Button button
    ) {
        return new PointerEvent(row, column, kind, button, Set.of());
    }

    private static void assertAction(SaveAsBarView.Action expected, SaveAsInputRouter.Result result) {
        assertTrue(result.consumed());
        assertEquals(expected, result.action());
    }

    private static void assertCaptured(SaveAsInputRouter.Result result) {
        assertTrue(result.consumed());
        assertNull(result.action());
    }

    private static void assertIgnored(SaveAsInputRouter.Result result) {
        assertFalse(result.consumed());
        assertNull(result.action());
    }

    private record Fixture(
        TextField field,
        FocusManager<SaveAsBarView.Target> focus,
        SaveAsBarView.Placement placement,
        WindowPointerCapture capture
    ) {
        private Fixture(TextField field, FocusManager<SaveAsBarView.Target> focus,
            SaveAsBarView.Placement placement) {
            this(field, focus, placement, new WindowPointerCapture());
        }

        private SaveAsInputRouter.Result route(TuiInputEvent event) {
            WindowPointerCapture.Frame frame = WindowPointerCapture.frame(this)
                .addScope(placement.focusScope(), focus, action -> { });
            capture.beforeInput(frame, event);
            if (event instanceof PointerEvent pointer && capture.dispatch(frame, pointer)) {
                return new SaveAsInputRouter.Result(true, null);
            }
            return SaveAsInputRouter.route(focus, placement, event, capture, this);
        }
    }
}
