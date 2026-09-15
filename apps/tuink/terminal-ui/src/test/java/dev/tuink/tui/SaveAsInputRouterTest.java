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
import dev.tuink.tui.ui.SaveAsBarView;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SaveAsInputRouterTest {
    @Test
    void routesUnicodeTextCaretEditingAndSubmitBeforeLegacyInput() {
        Fixture fixture = fixture(32, new Object());

        assertAction(TextField.Action.VALUE_CHANGED, fixture.route(new TextInput("A😀")));
        assertEquals("A😀", fixture.field.text());
        assertEquals(3, fixture.field.cursor());

        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_LEFT)));
        assertEquals(1, fixture.field.cursor());
        assertAction(
            TextField.Action.VALUE_CHANGED,
            fixture.route(KeyStroke.of(KeyStroke.Key.BACKSPACE))
        );
        assertEquals("😀", fixture.field.text());
        assertEquals(0, fixture.field.cursor());

        assertAction(TextField.Action.SUBMITTED, fixture.route(KeyStroke.of(KeyStroke.Key.ENTER)));
        assertEquals("😀", fixture.field.text(), "submit must not mutate the field value");
    }

    @Test
    void completePasteUsesSingleLinePolicyAndTruncatedPasteNeverPartiallyMutates() {
        Fixture fixture = fixture(40, new Object());

        assertAction(
            TextField.Action.VALUE_CHANGED,
            fixture.route(new PasteEvent("first\r\n\tsecond\u0000 😀\n", false))
        );
        assertEquals("first second 😀", fixture.field.text());

        String before = fixture.field.text();
        int cursorBefore = fixture.field.cursor();
        assertAction(
            TextField.Action.PASTE_REJECTED,
            fixture.route(new PasteEvent("partial", true))
        );
        assertEquals(before, fixture.field.text());
        assertEquals(cursorBefore, fixture.field.cursor());

        assertCaptured(fixture.route(new PasteEvent("\r\n\t", false)));
        assertEquals(before, fixture.field.text());
    }

    @Test
    void applicationOwnedEscapeCtrlQAndModifiedKeysStillBubbleToLegacyPolicy() {
        Fixture fixture = fixture(32, new Object());

        assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.ESCAPE)));
        assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)));
        assertIgnored(fixture.route(
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
        assertEquals("", fixture.field.text());
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
    void pointerHitAppliesSelfFocusAndEveryPointerMissRemainsModal() {
        TextField field = new TextField();
        field.set("abcd");
        Fixture fixture = fixture(field, 30, new Object());
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
        assertCaptured(fixture.route(KeyStroke.of(KeyStroke.Key.ENTER)));
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
            TextField.Action.PASTE_REJECTED,
            fixture.route(new PasteEvent("partial", true))
        );
        assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.ESCAPE)));
        assertIgnored(fixture.route(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)));
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

        assertAction(TextField.Action.VALUE_CHANGED, visible.route(new TextInput("visible")));
        assertEquals("visible", field.text());
        assertEquals(SaveAsBarView.Target.FIELD, visible.focus.focused().orElseThrow());
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

    private static void assertAction(TextField.Action expected, SaveAsInputRouter.Result result) {
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
        SaveAsBarView.Placement placement
    ) {
        private SaveAsInputRouter.Result route(TuiInputEvent event) {
            return SaveAsInputRouter.route(focus, placement, event);
        }
    }
}
