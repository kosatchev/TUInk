package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MenuBarUnavailableTest {
    private static final Rect BOUNDS = new Rect(0, 0, 40, 8);
    private static final FocusContext FOCUS = new FocusContext(true, true, FocusSpace.root(BOUNDS));

    @Test void readableDisabledMouseAndMnemonicReportTheExactValueWithoutClosingOrSelectingIt() {
        var placement = menu("Disabled").layout(BOUNDS);
        var original = placement.state();
        for (TuiInputEvent event : List.of(press(3, 2), new TextInput("d"), new TextInput("D"))) {
            var result = placement.handle(event, FOCUS);
            assertTrue(result.consumed());
            assertEquals(FocusRequest.NONE, result.focusRequest());
            assertEquals(PointerCaptureRequest.NONE, result.pointerCaptureRequest());
            var unavailable = assertInstanceOf(MenuBar.Unavailable.class, result.output().orElseThrow());
            assertEquals("disabled", unavailable.value());
            assertSame(original, unavailable.state());
            assertTrue(unavailable.state().isOpen());
            assertEquals(0, unavailable.state().selectedEntryIndex());
        }
        var normal = assertInstanceOf(MenuBar.Invoked.class,
            placement.handle(KeyStroke.of(KeyStroke.Key.ENTER), FOCUS).output().orElseThrow());
        assertEquals("enabled", normal.value(), "Enter retains the enabled selection");
        assertFalse(normal.state().isOpen());
    }

    @Test void disabledRowsStayDimAndDoNotBecomeTheKeyboardSelection() {
        var placement = menu("Disabled").layout(BOUNDS);
        CellStyle dim = CellStyle.fg(CellColor.BRIGHT_BLACK);
        var cells = new CellBuffer(8, 40);
        placement.paint(new Canvas(cells), role -> WidgetThemeKeys.MENU_ITEM_DISABLED.equals(role) ? dim : CellStyle.DEFAULT,
            WidgetState.FOCUSED);
        assertEquals(dim.fg(), cells.get(3, 2).style().fg());
        var requestedDisabled = new MenuBar<>(descriptors("Disabled"), new MenuBar.State(0, 1, 0)).layout(BOUNDS);
        assertEquals(0, requestedDisabled.state().selectedEntryIndex());
        assertTrue(requestedDisabled.handle(KeyStroke.of(KeyStroke.Key.ARROW_DOWN), FOCUS).output().isEmpty());
    }

    @Test void clippedDisabledLabelsCannotProduceAnAttemptUntilTheyAreFullyReadable() {
        var widget = new MenuBar<String>(List.of(new MenuBar.Menu<>("Menu", 0,
            List.of(new MenuBar.Item<>("disabled", "Disabled Ctrl+D", 0, false)))), MenuBar.State.open(0));
        var clipped = widget.layout(new Rect(0, 0, 8, 6));
        for (TuiInputEvent event : List.of(press(2, 2), new TextInput("d"), KeyStroke.of(KeyStroke.Key.ENTER))) {
            var result = clipped.handle(event, FOCUS);
            assertTrue(result.consumed()); assertTrue(result.output().isEmpty());
        }
        var visible = widget.layout(BOUNDS);
        assertInstanceOf(MenuBar.Unavailable.class, visible.handle(new TextInput("d"), FOCUS).output().orElseThrow());
    }

    @Test void separatorsRightClicksModifiersHiddenRowsAndDisabledWidgetsNeverProduceAttempts() {
        var placement = menu("Disabled").layout(BOUNDS);
        for (var event : List.of(
            press(4, 2),
            new PointerEvent(3, 2, PointerEvent.Kind.PRESS, PointerEvent.Button.RIGHT, Set.of()),
            new PointerEvent(3, 2, PointerEvent.Kind.RELEASE, PointerEvent.Button.LEFT, Set.of()),
            new PointerEvent(3, 2, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of(KeyStroke.Modifier.SHIFT)))) {
            assertTrue(placement.handle(event, FOCUS).output().isEmpty());
        }
        var disabledContext = new FocusContext(false, false, FocusSpace.root(BOUNDS));
        assertTrue(placement.handle(press(3, 2), disabledContext).output().isEmpty());
        var hidden = menu("Disabled").layout(new Rect(0, 0, 40, 4));
        assertTrue(hidden.handle(new TextInput("d"), FOCUS).output().isEmpty());
    }

    @Test void unavailableActionValidatesItsValueAndOpenState() {
        assertThrows(NullPointerException.class, () -> new MenuBar.Unavailable<String>(null, MenuBar.State.open(0)));
        assertThrows(NullPointerException.class, () -> new MenuBar.Unavailable<>("value", null));
        assertThrows(IllegalArgumentException.class, () -> new MenuBar.Unavailable<>("value", MenuBar.State.closed()));
    }

    private static MenuBar<String> menu(String disabledLabel) {
        return new MenuBar<>(descriptors(disabledLabel), MenuBar.State.open(0));
    }
    private static List<MenuBar.Menu<String>> descriptors(String disabledLabel) {
        return List.of(new MenuBar.Menu<>("Menu", 0, List.of(
            new MenuBar.Item<>("enabled", "Enabled", 0, true),
            new MenuBar.Item<>("disabled", disabledLabel, 0, false),
            new MenuBar.Separator<>())));

    }
    private static PointerEvent press(int row, int column) {
        return new PointerEvent(row, column, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of());
    }
}
