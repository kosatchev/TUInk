package dev.tuink.tui;

import static dev.tuink.tui.command.TuinkCommand.EDIT_SELECT_ALL;
import static dev.tuink.tui.command.TuinkCommand.TAB_NEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.command.CommandInvocation;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.widget.MenuBar;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TopMenuInputRouterTest {
    @Test
    void physicalMenuShortcutOpensBeforeRoutingTheClosedFramePlacement() {
        Fixture fixture = fixture(MenuBar.State.closed(), new Rect(0, 0, 80, 20));

        TopMenuInputRouter.Result result = fixture.route(KeyStroke.of(KeyStroke.Key.F2));

        assertTrue(result.consumed());
        assertNull(result.command());
        assertEquals(MenuBar.State.open(TopMenuController.EDIT_INDEX), fixture.controller.state());
    }

    @Test
    void shortcutSwitchesAnOpenMenuWithoutTogglingItClosed() {
        Fixture fixture = fixture(MenuBar.State.open(TopMenuController.FILE_INDEX), new Rect(0, 0, 80, 20));

        TopMenuInputRouter.Result result = fixture.route(
            KeyStroke.of(KeyStroke.Key.H, KeyStroke.Modifier.ALT)
        );

        assertTrue(result.consumed());
        assertNull(result.command());
        assertEquals(MenuBar.State.open(TopMenuController.HELP_INDEX), fixture.controller.state());
    }

    @Test
    void editSelectAllReturnsItsExactSemanticCommandEvenWithSearchBehindMenu() {
        Fixture fixture = fixture(MenuBar.State.open(TopMenuController.EDIT_INDEX), new Rect(0, 0, 80, 20));
        PointerEvent selectAll = pointer(8, 9, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT);

        TopMenuInputRouter.Result result = fixture.route(selectAll);

        assertTrue(result.consumed());
        assertEquals(EDIT_SELECT_ALL.id(), result.command().id());
        assertEquals(MenuBar.State.closed(), fixture.controller.state());
    }

    @Test
    void keyboardSelectionInvokesDynamicTabWithItsExactIndex() {
        Fixture fixture = fixture(
            MenuBar.State.open(TopMenuController.TABS_INDEX),
            new Rect(0, 0, 80, 20),
            List.of(
                new TopMenuView.TabItem(Path.of("first.txt"), false, false),
                new TopMenuView.TabItem(Path.of("second.txt"), false, false)
            )
        );
        for (int i = 0; i < 5; i++) {
            TopMenuInputRouter.Result moved = fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            assertTrue(moved.consumed());
            assertNull(moved.command());
            fixture = fixture(fixture.controller.state(), new Rect(0, 0, 80, 20), fixture.tabs);
        }

        TopMenuInputRouter.Result selected = fixture.route(KeyStroke.of(KeyStroke.Key.ENTER));

        assertTrue(selected.consumed());
        assertEquals(1, fixture.router.invokeTabActivation(selected.command()).orElseThrow());
        assertEquals(MenuBar.State.closed(), fixture.controller.state());
    }

    @Test
    void escapeDismissesWithoutACommandAndEnterInvokesOneStaticCommand() {
        Fixture dismissed = fixture(MenuBar.State.open(TopMenuController.FILE_INDEX), new Rect(0, 0, 80, 20));
        TopMenuInputRouter.Result escape = dismissed.route(KeyStroke.of(KeyStroke.Key.ESCAPE));

        assertTrue(escape.consumed());
        assertNull(escape.command());
        assertEquals(MenuBar.State.closed(), dismissed.controller.state());

        Fixture selected = fixture(MenuBar.State.open(TopMenuController.FILE_INDEX), new Rect(0, 0, 80, 20));
        TopMenuInputRouter.Result enter = selected.route(KeyStroke.of(KeyStroke.Key.ENTER));

        assertTrue(enter.consumed());
        assertEquals(TAB_NEW.id(), enter.command().id());
        assertEquals(MenuBar.State.closed(), selected.controller.state());
    }

    @Test
    void outsidePressDismissesAndWheelOrPasteCannotReachTheEditor() {
        Fixture outside = fixture(MenuBar.State.open(TopMenuController.FILE_INDEX), new Rect(0, 0, 80, 20));
        TopMenuInputRouter.Result dismissed = outside.route(
            pointer(18, 70, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT)
        );
        assertTrue(dismissed.consumed());
        assertNull(dismissed.command());
        assertEquals(MenuBar.State.closed(), outside.controller.state());

        Fixture wheel = fixture(MenuBar.State.open(TopMenuController.FILE_INDEX), new Rect(0, 0, 80, 20));
        TopMenuInputRouter.Result wheelResult = wheel.route(
            pointer(3, 4, PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE)
        );
        assertTrue(wheelResult.consumed());
        assertNull(wheelResult.command());
        assertTrue(wheel.controller.isOpen());

        TopMenuInputRouter.Result paste = wheel.route(new PasteEvent("must not leak"));
        assertTrue(paste.consumed());
        assertNull(paste.command());
        assertTrue(wheel.controller.isOpen());
    }

    @Test
    void tinyOpenPlacementCapturesWithoutInvokingHiddenItems() {
        Fixture fixture = fixture(MenuBar.State.open(TopMenuController.VIEW_INDEX), new Rect(0, 0, 5, 1));

        TopMenuInputRouter.Result result = fixture.route(KeyStroke.of(KeyStroke.Key.ENTER));

        assertTrue(result.consumed());
        assertNull(result.command());
    }

    @Test
    void modifiedClosedChromeIsCapturedButPointerMissBubbles() {
        Fixture fixture = fixture(MenuBar.State.closed(), new Rect(0, 0, 80, 20));
        PointerEvent modifiedLabel = new PointerEvent(
            0,
            2,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT,
            Set.of(KeyStroke.Modifier.SHIFT)
        );

        TopMenuInputRouter.Result modified = fixture.route(modifiedLabel);
        TopMenuInputRouter.Result miss = fixture.route(
            pointer(15, 50, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT)
        );

        assertTrue(modified.consumed());
        assertFalse(fixture.controller.isOpen());
        assertFalse(miss.consumed());
        assertNull(miss.command());
    }

    private static Fixture fixture(MenuBar.State state, Rect bounds) {
        return fixture(
            state,
            bounds,
            List.of(new TopMenuView.TabItem(Path.of("demo.txt"), false, false))
        );
    }

    private static Fixture fixture(MenuBar.State state, Rect bounds, List<TopMenuView.TabItem> tabs) {
        CommandRouter router = new CommandRouter();
        TopMenuController controller = new TopMenuController();
        controller.adopt(state);
        UiSessionState ui = new UiSessionState();
        ui.wrapLinesSetting = true;
        ui.mouseModeAvailable = true;
        TopMenuView view = TopMenuView.build(
            ui,
            tabs,
            0,
            "Cyan",
            router,
            state
        );
        return new Fixture(controller, view.place(bounds), router, tabs);
    }

    private static PointerEvent pointer(
        int row,
        int column,
        PointerEvent.Kind kind,
        PointerEvent.Button button
    ) {
        return new PointerEvent(row, column, kind, button, Set.of());
    }

    private record Fixture(
        TopMenuController controller,
        TopMenuView.Placement placement,
        CommandRouter router,
        List<TopMenuView.TabItem> tabs
    ) {
        TopMenuInputRouter.Result route(dev.tuiframe.input.TuiInputEvent event) {
            return TopMenuInputRouter.route(controller, placement, router, event);
        }
    }
}
