package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import org.junit.jupiter.api.Test;

class ControlAppearanceTest {
    private static final Rect BOUNDS = new Rect(2, 1, 12, 1);
    @Test void focusedInputsAndListsUseTheAccentWhileInactiveSelectionInvertsItsOwnSurface() {
        var body = new CellStyle(CellColor.WHITE, CellColor.BLUE, false, false);
        var accent = new CellStyle(CellColor.BLACK, CellColor.BRIGHT_GREEN, true, false);
        Theme theme = role -> role.equals(WidgetThemeKeys.MODAL_BODY) ? body
            : role.equals(WidgetThemeKeys.ACCENT) ? accent : null;
        var field = new TextField(); field.set("x");
        var list = new ListView(2, index -> index == 0 ? "first" : "second", new ListView.State(0, 0));
        for (var state : new WidgetState[] {WidgetState.NORMAL, WidgetState.FOCUSED}) {
            var cells = new CellBuffer(4, 20);
            field.layout(BOUNDS).paint(new Canvas(cells), theme, state);
            list.layout(new Rect(2, 2, 12, 2)).paint(new Canvas(cells), theme, state);
            var expected = state.focused() ? accent : body.inverted();
            for (int column = 2; column < 14; column++) {
                assertEquals(expected, cells.get(1, column).style());
                assertEquals(expected, cells.get(2, column).style());
                assertEquals(body, cells.get(3, column).style());
            }
        }
    }
    @Test void emptyAndFilledInputsInvertTheAllocationUntilFocusedOrDisabled() {
        for (String value : new String[] {"", "text"}) for (var state : new WidgetState[] {WidgetState.NORMAL, WidgetState.DISABLED}) {
            var field = new TextField(); field.set(value);
            var cells = new CellBuffer(3, 20); field.layout(BOUNDS).paint(new Canvas(cells), role -> null, state);
            for (int col = BOUNDS.x(); col < BOUNDS.rightExclusive(); col++) assertTrue(cells.get(1, col).style().inverse());
            assertFalse(cells.get(1, 1).style().inverse());
        }
    }
    @Test void inputSelectionContrastsWithInvertedTextAndExplicitThemeOverridesAreHonored() {
        var field = new TextField(); field.set("text"); field.selectAll();
        var cells = new CellBuffer(3, 20); field.layout(BOUNDS).paint(new Canvas(cells), role -> null, WidgetState.FOCUSED);
        assertEquals(ControlStyles.accent(role -> null).inverted().emphasis(true, true), cells.get(1, 2).style());
        assertEquals(ControlStyles.accent(role -> null), cells.get(1, 6).style());
        var custom = new CellStyle(CellColor.BLACK, CellColor.CYAN, false, false);
        field.set("text"); field.layout(BOUNDS).paint(new Canvas(cells), Theme.fixed(custom), WidgetState.NORMAL);
        assertEquals(custom, cells.get(1, 2).style());
    }
    @Test void placeholderIsPresentationOnlyAndBelongsToThePlacementSnapshot() {
        var field = new TextField().placeholder("Search…");
        var placed = field.layout(BOUNDS); field.placeholder("Changed");
        var cells = new CellBuffer(3, 20); placed.paint(new Canvas(cells), role -> null, WidgetState.NORMAL);
        assertEquals("S", cells.get(1, 2).grapheme()); assertEquals("", field.text()); assertEquals(0, field.cursor());
        field.set("X"); field.layout(BOUNDS).paint(new Canvas(cells), role -> null, WidgetState.NORMAL);
        assertEquals("X", cells.get(1, 2).grapheme()); assertEquals(" ", cells.get(1, 3).grapheme());
    }
    @Test void checkButtonSeparatesBooleanValueFromFocusAndReturnsTheNextValue() {
        var checked = new CheckButton("Word", true); var unchecked = new CheckButton("Word", false);
        assertEquals(checked.width(), unchecked.width());
        var cells = new CellBuffer(3, 20); var placed = checked.layout(BOUNDS);
        placed.paint(new Canvas(cells), role -> null, WidgetState.NORMAL);
        assertEquals("✓", cells.get(1, 3).grapheme()); assertFalse(cells.get(1, 2).style().inverse());
        assertFalse(placed.handle(KeyStroke.of(KeyStroke.Key.ENTER), new FocusContext(true, true, FocusSpace.root(BOUNDS))).output().orElseThrow());
        assertTrue(checked.checked());
        assertFalse(placed.handle(KeyStroke.of(KeyStroke.Key.ENTER), new FocusContext(false, false, FocusSpace.root(BOUNDS))).consumed());
    }
    @Test void checkMarksDoNotChangeColorsAndAllButtonControlsShareFocusStyling() {
        var body = new CellStyle(CellColor.WHITE, CellColor.BLACK, false, false);
        var focus = new CellStyle(CellColor.BLACK, CellColor.CYAN, true, false);
        Theme theme = role -> role.equals(WidgetThemeKeys.BUTTON_FOCUSED) ? focus : body;
        var dropdown = new DropDown<>(java.util.List.of(new DropDown.Option<>(1, "Strict")), 1);
        for (var state : new WidgetState[] {WidgetState.NORMAL, WidgetState.FOCUSED, WidgetState.DISABLED}) {
            for (var control : new InteractiveWidget<?>[] {new Button("Word"), new CheckButton("Word", false), new CheckButton("Word", true), dropdown}) {
                var cells = new CellBuffer(3, 20); control.layout(BOUNDS).paint(new Canvas(cells), theme, state);
                assertEquals(state.focused() ? focus : body, cells.get(1, 2).style());
            }
        }
    }

}
