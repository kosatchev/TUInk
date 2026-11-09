package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DropDownTest {
    private static final Rect VIEW = new Rect(0, 0, 50, 15);
    private static final FocusContext FOCUS = new FocusContext(true, true, FocusSpace.root(VIEW));
    private DropDown<Integer> selector() {
        return new DropDown<>(List.of(new DropDown.Option<>(1, "Strict", "literal text"),
            new DropDown.Option<>(2, "Wildcard", "* and ?"), new DropDown.Option<>(3, "Regex", "expressions")), 1);
    }
    private KeyStroke key(KeyStroke.Key key) { return KeyStroke.of(key); }
    @Test void navigationCommitsOnlyOnActivationAndEscapeAndTabCancel() {
        var widget = selector();
        var placed = widget.layout(new Rect(3, 12, widget.width(), 1), VIEW);
        for (var cancel : List.of(KeyStroke.Key.ESCAPE, KeyStroke.Key.TAB)) {
            assertEquals(DropDown.Action.OPENED, placed.handle(key(KeyStroke.Key.ENTER), FOCUS).output().orElseThrow());
            placed.handle(key(KeyStroke.Key.END), FOCUS);
            assertEquals(1, widget.value());
            assertEquals(DropDown.Action.CLOSED, placed.handle(key(cancel), FOCUS).output().orElseThrow());
            assertFalse(widget.popupOpen()); assertEquals(1, widget.value());
        }
        placed.handle(key(KeyStroke.Key.ARROW_DOWN), FOCUS);
        placed.handle(key(KeyStroke.Key.END), FOCUS);
        assertEquals(DropDown.Action.VALUE_CHANGED, placed.handle(key(KeyStroke.Key.SPACE), FOCUS).output().orElseThrow());
        assertEquals(3, widget.value()); assertFalse(widget.popupOpen());
    }
    @Test void popupFitsAboveFooterAndPointerCoordinatesRespectParentOrigin() {
        var widget = selector();
        var placed = widget.layout(new Rect(4, 10, widget.width(), 1), new Rect(2, 2, 25, 9));
        assertTrue(placed.popupBounds().bottomExclusive() <= placed.bounds().y());
        assertTrue(new Rect(2, 2, 25, 9).contains(placed.popupBounds()));
        var origin = new Point(7, 4);
        var context = new FocusContext(true, true, new FocusSpace(origin, placed.bounds()));
        placed.handle(key(KeyStroke.Key.ENTER), context);
        Rect popup = placed.popupBounds();
        var click = new PointerEvent(popup.y() + 2 + origin.y(), popup.x() + 1 + origin.x(),
            PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of());
        assertEquals(DropDown.Action.VALUE_CHANGED, placed.handle(click, context).output().orElseThrow());
        assertEquals(3, widget.value());
    }
    @Test void outsidePressClosesAndConsumesWithoutChoosingOrLeakingIntoDocument() {
        var widget = selector(); var placed = widget.layout(new Rect(3, 2, widget.width(), 1), VIEW);
        placed.handle(key(KeyStroke.Key.ENTER), FOCUS);
        placed.handle(key(KeyStroke.Key.END), FOCUS);
        var result = placed.handle(new PointerEvent(14, 49, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of()), FOCUS);
        assertTrue(result.consumed()); assertFalse(widget.popupOpen()); assertEquals(1, widget.value());
    }
    @Test void emptyClippedAndDisabledControlsCannotOpenAndDisabledPopupDismisses() {
        var widget = selector();
        for (Rect bounds : List.of(new Rect(3, 2, 0, 1), new Rect(3, 2, 3, 1), new Rect(48, 2, widget.width(), 1))) {
            assertFalse(widget.layout(bounds, VIEW).handle(key(KeyStroke.Key.ARROW_DOWN), FOCUS).consumed());
            assertFalse(widget.popupOpen());
        }
        var placed = widget.layout(new Rect(3, 2, widget.width(), 1), VIEW);
        placed.handle(key(KeyStroke.Key.ENTER), FOCUS);
        placed.handle(key(KeyStroke.Key.ENTER), new FocusContext(false, false, FocusSpace.root(VIEW)));
        assertFalse(widget.popupOpen()); assertEquals(1, widget.value());
    }
    @Test void popupContrastsWithOrdinaryButtonAnchorAndSelectionSurvivesFocusLoss() {
        var widget = selector(); var placed = widget.layout(new Rect(3, 2, widget.width(), 1), VIEW);
        assertInstanceOf(PlacedPopup.class, placed.mapOutput(action -> action.name()));
        var cells = new CellBuffer(15, 50); Theme theme = role -> null;
        placed.paint(new Canvas(cells), theme, WidgetState.NORMAL);
        assertFalse(cells.get(2, 3).style().inverse());
        placed.handle(key(KeyStroke.Key.ENTER), FOCUS);
        placed.paintPopup(new Canvas(cells), theme, WidgetState.FOCUSED);
        Rect popup = placed.popupBounds();
        assertNotEquals(cells.get(popup.y(), popup.x()).style(), cells.get(popup.y() + 1, popup.x()).style());
        assertEquals("✓", cells.get(popup.y(), popup.x() + 1).grapheme());
        assertTrue(cells.get(popup.y() + 1, popup.x()).style().inverse());
    }
    @Test void popupInvertsItsOwnSurfaceEvenWhenTheParentSuppliesAnotherMenuPalette() {
        var owner = new CellStyle(CellColor.WHITE, CellColor.BLUE, false, false);
        var inheritedMenu = new CellStyle(CellColor.BLACK, CellColor.GRAY_188, false, false);
        Theme parent = role -> role.equals(WidgetThemeKeys.POPUP_BODY) || role.equals(WidgetThemeKeys.POPUP_SELECTED) ? inheritedMenu : null;
        var theme = parent.withSurface(owner, owner);
        var widget = selector(); var placed = widget.layout(new Rect(3, 2, widget.width(), 1), VIEW);
        var cells = new CellBuffer(15, 50);
        placed.paint(new Canvas(cells), theme, WidgetState.NORMAL);
        assertEquals(owner, cells.get(2, 3).style());
        placed.handle(key(KeyStroke.Key.ENTER), FOCUS);
        placed.paintPopup(new Canvas(cells), theme, WidgetState.FOCUSED);
        var menu = placed.popupBounds();
        assertEquals(owner.inverted(), cells.get(menu.y() + 1, menu.x()).style());
        assertEquals(owner.emphasis(true, true), cells.get(menu.y(), menu.x()).style());
    }
    @Test void optionValuesAreValidatedAndRetainedAcrossLayouts() {
        assertThrows(IllegalArgumentException.class, () -> new DropDown<Integer>(List.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> new DropDown<>(List.of(new DropDown.Option<>(1, "A"), new DropDown.Option<>(1, "B")), 1));
        var widget = selector(); assertThrows(IllegalArgumentException.class, () -> widget.setValue(9));
        widget.setValue(2); widget.layout(new Rect(0, 0, widget.width(), 1), VIEW);
        assertEquals(2, widget.value());
    }
}
