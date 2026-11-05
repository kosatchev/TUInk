package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.widget.MenuBar;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UnavailableMenuInputTest {
    @Test void unavailableMouseIsReportedSeparatelyFromAnInvocationAndKeepsTheMenuOpen() {
        var fixture = fixture();
        int entry = entry(fixture.view, TuinkCommand.VIEW_TOGGLE_WORD_WRAP);
        var popup = fixture.placement.popupBounds().orElseThrow();
        var before = fixture.placement.state();
        var result = TopMenuInputRouter.route(fixture.controller, fixture.placement, fixture.router,
            press(popup.y() + 1 + entry - before.firstVisibleEntryIndex(), popup.x() + 2));
        assertTrue(result.consumed());
        assertNull(result.command(), "An unavailable attempt must never enter ordinary command dispatch");
        assertEquals(TuinkCommand.VIEW_TOGGLE_WORD_WRAP.id(), result.unavailable().id());
        assertEquals(before, fixture.controller.state());
        assertTrue(fixture.controller.isOpen());
    }

    @Test void ordinaryEnterStillInvokesTheSelectedEnabledCommandWithoutAnUnavailableAttempt() {
        var fixture = fixture();
        var selected = fixture.placement.state().selectedEntryIndex();
        var expected = (MenuBar.Item<CommandInvocation>) fixture.view.menus().get(TopMenuController.VIEW_INDEX).entries().get(selected);
        assertTrue(expected.enabled());
        var result = TopMenuInputRouter.route(fixture.controller, fixture.placement, fixture.router,
            KeyStroke.of(KeyStroke.Key.ENTER));
        assertTrue(result.consumed());
        assertEquals(expected.value(), result.command());
        assertNull(result.unavailable());
        assertFalse(fixture.controller.isOpen());
    }

    @Test void clippedUnavailableItemDoesNotProduceEitherKindOfCommand() {
        var fixture = fixture();
        int entry = entry(fixture.view, TuinkCommand.VIEW_TOGGLE_WORD_WRAP);
        var requested = new MenuBar.State(TopMenuController.VIEW_INDEX,
            fixture.placement.state().selectedEntryIndex(), Math.max(0, entry - 1));
        var ui = new UiSessionState();
        var view = view(ui, fixture.router, requested);
        var placement = view.place(new Rect(0, 0, 8, 40));
        fixture.controller.adopt(placement.state());
        var popup = placement.popupBounds().orElseThrow();
        var result = TopMenuInputRouter.route(fixture.controller, placement, fixture.router,
            press(popup.y() + 1 + entry - placement.state().firstVisibleEntryIndex(), popup.x() + 2));
        assertTrue(result.consumed());
        assertNull(result.command()); assertNull(result.unavailable());
        assertTrue(fixture.controller.isOpen());
    }

    @Test void resultRejectsIgnoredOrAmbiguousCommandPublications() {
        var command = CommandInvocation.of(TuinkCommand.VIEW_TOGGLE_WORD_WRAP.id());
        assertThrows(IllegalArgumentException.class, () -> new TopMenuInputRouter.Result(command, false, null));
        assertThrows(IllegalArgumentException.class, () -> new TopMenuInputRouter.Result(null, false, command));
        assertThrows(IllegalArgumentException.class, () -> new TopMenuInputRouter.Result(command, true, command));
    }

    private static Fixture fixture() {
        var ui = new UiSessionState();
        ui.wrapLinesSetting = false;
        var router = new CommandRouter();
        var controller = new TopMenuController();
        var state = MenuBar.State.open(TopMenuController.VIEW_INDEX);
        controller.adopt(state);
        var view = view(ui, router, state);
        return new Fixture(router, controller, view, view.place(new Rect(0, 0, 120, 40)));
    }
    private static TopMenuView view(UiSessionState ui, CommandRouter router, MenuBar.State state) {
        return TopMenuView.build(ui, List.of(new TopMenuView.TabItem(Path.of("document.md"), false, false)),
            0, "Cyan", router, state, TestSurfaces.source(ui));
    }
    private static int entry(TopMenuView view, TuinkCommand command) {
        var entries = view.menus().get(TopMenuController.VIEW_INDEX).entries();
        for (int index = 0; index < entries.size(); index++)
            if (entries.get(index) instanceof MenuBar.Item<CommandInvocation> item && item.value().id().equals(command.id())) {
                assertFalse(item.enabled()); return index;
            }
        throw new AssertionError("Missing disabled item " + command);
    }
    private static PointerEvent press(int row, int column) {
        return new PointerEvent(row, column, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of());
    }
    private record Fixture(CommandRouter router, TopMenuController controller, TopMenuView view,
                           TopMenuView.Placement placement) {}
}
