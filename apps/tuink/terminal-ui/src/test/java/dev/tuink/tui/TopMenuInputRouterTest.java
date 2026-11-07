package dev.tuink.tui;

import static dev.tuink.tui.command.TuinkCommand.EDIT_SELECT_ALL;
import static dev.tuink.tui.command.TuinkCommand.TAB_NEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.command.CommandInvocation;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.widget.MenuBar;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.PointerCaptureRequest;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.widget.Theme;
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
    void explorerIsTheNextVisualMenuAfterFileWhileF2StillOpensEdit() {
        Fixture fixture = fixture(MenuBar.State.open(TopMenuController.FILE_INDEX), new Rect(0, 0, 80, 20));

        assertTrue(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT)).consumed());
        assertEquals(TopMenuController.EXPLORER_INDEX, fixture.controller.state().openMenuIndex());

        fixture = fixture(fixture.controller.state(), new Rect(0, 0, 80, 20));
        assertTrue(fixture.route(KeyStroke.of(KeyStroke.Key.F2)).consumed());
        assertEquals(TopMenuController.EDIT_INDEX, fixture.controller.state().openMenuIndex());
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
        PointerEvent selectAll = fixture.itemPointer("Select All");

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
    void shortcutRevealsAnOffscreenMenuOnTheNextNarrowFrame() {
        int readableWidth = dev.tuiframe.cell.CellText.width("[ ] Confirm on close") + 5;
        Rect bounds = new Rect(0, 0, readableWidth - 1, 10);
        Fixture fixture = fixture(MenuBar.State.closed(), bounds);
        TopMenuInputRouter.Result shortcut = fixture.route(KeyStroke.of(KeyStroke.Key.S, KeyStroke.Modifier.ALT));
        assertTrue(shortcut.consumed());
        assertNull(shortcut.command());
        Fixture rebuilt = fixture(fixture.controller.state(), bounds);
        assertEquals(TopMenuController.SETTINGS_INDEX, rebuilt.placement.state().openMenuIndex());
        assertTrue(rebuilt.placement.popupBounds().isPresent());
        TopMenuInputRouter.Result activated = rebuilt.route(KeyStroke.of(KeyStroke.Key.ENTER));
        assertTrue(activated.consumed());
        assertNull(activated.command(), "The scrollbar leaves one character of the item label hidden");
        assertTrue(rebuilt.controller.isOpen());

        Fixture expanded = fixture(rebuilt.controller.state(), new Rect(0, 0, readableWidth, 10));
        TopMenuInputRouter.Result readable = expanded.route(KeyStroke.of(KeyStroke.Key.ENTER));
        assertEquals("tuink.settings.toggle-confirm-on-close", readable.command().id().value());
    }

    @Test
    void longMenuWheelRebuildAndScrollbarReleaseKeepTheEditorCaptured() {
        Rect bounds = new Rect(0, 0, 30, 7);
        Fixture fixture = fixture(MenuBar.State.open(TopMenuController.VIEW_INDEX), bounds);
        Rect popup = fixture.placement.popupBounds().orElseThrow();
        TopMenuInputRouter.Result wheel = fixture.route(pointer(
            popup.y() + 1, popup.x() + 1, PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE
        ));
        assertTrue(wheel.consumed());
        assertNull(wheel.command());
        assertEquals(3, fixture.controller.state().firstVisibleEntryIndex());
        Fixture rebuilt = fixture(fixture.controller.state(), bounds);
        assertEquals(3, rebuilt.placement.state().firstVisibleEntryIndex());
        var cells = new CellBuffer(bounds.height(), bounds.width());
        rebuilt.placement.paint(new Canvas(cells), Theme.fixed(CellStyle.DEFAULT));
        var thumb = java.util.stream.IntStream.range(0, bounds.height() * bounds.width())
            .mapToObj(index -> new Point(index % bounds.width(), index / bounds.width()))
            .filter(point -> "█".equals(cells.get(point.y(), point.x()).grapheme()))
            .findFirst().orElseThrow();
        TopMenuInputRouter.Result grab = rebuilt.route(pointer(
            thumb.y(), thumb.x(), PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT
        ));
        assertTrue(grab.consumed());
        assertNull(grab.command());
        assertTrue(rebuilt.controller.state().drag() != null);
        Fixture dragging = fixture(rebuilt.controller.state(), bounds);
        assertTrue(dragging.route(new ResizeEvent(10, 40)).consumed());
        assertTrue(dragging.controller.state().drag() == null);
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
        TopMenuView view = TopMenuView.build(
            ui,
            tabs,
            0,
            "Cyan",
            router,
            state,
            TestSurfaces.source(ui)
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
        List<TopMenuView.TabItem> tabs,
        WindowPointerCapture capture
    ) {
        private Fixture(TopMenuController controller, TopMenuView.Placement placement, CommandRouter router,
            List<TopMenuView.TabItem> tabs) {
            this(controller, placement, router, tabs, new WindowPointerCapture());
        }

        PointerEvent itemPointer(String label) {
            Rect popup = placement.popupBounds().orElseThrow();
            var cells = new CellBuffer(popup.y() + popup.height(), popup.x() + popup.width());
            placement.paint(new Canvas(cells), Theme.fixed(CellStyle.DEFAULT));
            for (int row = popup.y(); row < popup.y() + popup.height(); row++) {
                var text = new StringBuilder();
                for (int column = popup.x(); column < popup.x() + popup.width(); column++)
                    text.append(cells.get(row, column).grapheme());
                int column = text.indexOf(label);
                if (column >= 0) return pointer(row, popup.x() + column + 1, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT);
            }
            throw new AssertionError("Missing painted menu item: " + label);
        }

        TopMenuInputRouter.Result route(dev.tuiframe.input.TuiInputEvent event) {
            if (placement.pointerCaptureActive() && !capture.active()) {
                capture.observe(this, placement.captureScope(), "menu",
                    InteractionResult.<MenuBar.Action<dev.tuink.editor.command.CommandInvocation>>handled()
                        .withPointerCapture(PointerCaptureRequest.ACQUIRE));
            }
            WindowPointerCapture.Frame frame = WindowPointerCapture.frame(this)
                .addScope(placement.captureScope(), action -> {
                    if (action instanceof MenuBar.StateChanged<dev.tuink.editor.command.CommandInvocation> changed) {
                        controller.adopt(changed.state());
                    } else {
                        throw new AssertionError("captured scrollbar invoked a command");
                    }
                });
            boolean cancelled = capture.beforeInput(frame, event);
            if (event instanceof PointerEvent pointer && capture.dispatch(frame, pointer)) {
                return new TopMenuInputRouter.Result(null, true, null);
            }
            TopMenuView.Placement current = cancelled
                ? fixture(controller.state(), placement.bounds(), tabs).placement : placement;
            return TopMenuInputRouter.route(controller, current, router, event, capture, this);
        }
    }
}
