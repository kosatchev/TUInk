package dev.tuink.terminal.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.geometry.Point;
import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.input.KeyStroke;
import dev.tuink.terminal.input.PointerEvent;
import dev.tuink.terminal.input.TextInput;
import dev.tuink.terminal.input.TuiInputEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FocusManagerTest {
    private static final FocusSpace ROOT = FocusSpace.root(new Rect(0, 0, 80, 24));

    @Test
    void stableIdsSurviveRelayoutAndMissingOrDisabledFocusFallsBack() {
        FocusManager<String> manager = new FocusManager<>();
        FocusScope<String, String> initial = scope(
            "editor",
            target("first", 0),
            target("second", 4)
        );

        assertTrue(manager.route(initial, new TextInput("x")).focusChanged());
        assertEquals("first", manager.focused().orElseThrow());
        assertTrue(manager.focus(initial, "second"));

        FocusScope<String, String> relayout = scope(
            "editor",
            target("first", 10),
            target("second", 20)
        );
        assertFalse(manager.route(relayout, new TextInput("x")).focusChanged());
        assertEquals("second", manager.focused().orElseThrow());

        FocusScope<String, String> missing = scope("editor", target("first", 10));
        assertTrue(manager.route(missing, new TextInput("x")).focusChanged());
        assertEquals("first", manager.focused().orElseThrow());

        FocusScope<String, String> disabled = scope("editor", disabledTarget("first", 10));
        assertTrue(manager.route(disabled, new TextInput("x")).focusChanged());
        assertTrue(manager.focused().isEmpty());
        assertThrows(
            IllegalArgumentException.class,
            () -> new FocusScope<>(
                "duplicate",
                FocusResetPolicy.FIRST_FOCUSABLE,
                List.of(target("same", 0), target("same", 2)),
                List.of("same"),
                List.of("same")
            )
        );
    }

    @Test
    void exactTabAndShiftTabWrapWhileCommandModifiedTabBubbles() {
        FocusManager<String> manager = new FocusManager<>();
        FocusScope<String, String> scope = scope(
            "dialog",
            target("first", 0),
            disabledTarget("disabled", 2),
            target("last", 4)
        );
        manager.context(scope, "first");

        FocusRoute<String, String> next = manager.route(scope, KeyStroke.of(KeyStroke.Key.TAB));
        assertTrue(next.navigationConsumed());
        assertTrue(next.focusChanged());
        assertEquals("last", next.target().orElseThrow().id());

        FocusRoute<String, String> wrapped = manager.route(scope, KeyStroke.of(KeyStroke.Key.TAB));
        assertEquals("first", wrapped.target().orElseThrow().id());

        FocusRoute<String, String> previous = manager.route(
            scope,
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT)
        );
        assertTrue(previous.navigationConsumed());
        assertEquals("last", previous.target().orElseThrow().id());

        FocusRoute<String, String> commandTab = manager.route(
            scope,
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.CTRL)
        );
        assertFalse(commandTab.navigationConsumed());
        assertFalse(commandTab.focusChanged());
        assertEquals("last", commandTab.target().orElseThrow().id());
        assertFalse(
            manager.route(
                scope,
                KeyStroke.of(
                    KeyStroke.Key.TAB,
                    KeyStroke.Modifier.CTRL,
                    KeyStroke.Modifier.SHIFT
                )
            ).navigationConsumed()
        );
    }

    @Test
    void tabOrderAndPointerZOrderAreIndependent() {
        FocusManager<String> manager = new FocusManager<>();
        FocusSpace translated = new FocusSpace(new Point(10, 5), new Rect(0, 0, 8, 3));
        RoutedTarget<String, String> bottom = target(
            "bottom",
            placement(new Rect(1, 1, 4, 1)),
            translated
        );
        RoutedTarget<String, String> top = target(
            "top",
            placement(new Rect(2, 1, 3, 1)),
            translated
        );
        FocusScope<String, String> scope = new FocusScope<>(
            "stack",
            FocusResetPolicy.FIRST_FOCUSABLE,
            List.of(bottom, top),
            List.of("bottom", "top"),
            List.of("bottom", "top")
        );

        manager.context(scope, "bottom");
        assertEquals(
            "top",
            manager.route(scope, KeyStroke.of(KeyStroke.Key.TAB)).target().orElseThrow().id()
        );
        FocusRoute<String, String> overlap = manager.route(
            scope,
            pointer(6, 12, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT)
        );
        assertEquals("bottom", overlap.target().orElseThrow().id());
        assertEquals("bottom", manager.focused().orElseThrow());

        FocusRoute<String, String> clipped = manager.route(
            scope,
            pointer(6, 18, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT)
        );
        assertTrue(clipped.target().isEmpty());
        assertEquals("bottom", manager.focused().orElseThrow());
    }

    @Test
    void scrollRoutesUnderPointerWithoutMovingFocusAndKeyboardRoutesToFocus() {
        FocusManager<String> manager = new FocusManager<>();
        FocusScope<String, String> scope = scope(
            "editor",
            target("first", 0),
            target("second", 4)
        );
        manager.context(scope, "first");

        FocusRoute<String, String> scroll = manager.route(
            scope,
            pointer(0, 5, PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE)
        );
        assertEquals("second", scroll.target().orElseThrow().id());
        assertFalse(scroll.focusChanged());
        assertEquals("first", manager.focused().orElseThrow());

        FocusRoute<String, String> keyboard = manager.route(scope, new TextInput("x"));
        assertEquals("first", keyboard.target().orElseThrow().id());
        assertTrue(keyboard.context().orElseThrow().focused());
    }

    @Test
    void resetPolicyAndScopeLifecycleAreExplicit() {
        FocusManager<String> manager = new FocusManager<>();
        FocusScope<String, String> first = scope(
            "first-scope",
            target("first", 0),
            target("second", 4)
        );
        FocusScope<String, String> cleared = new FocusScope<>(
            "cleared-scope",
            FocusResetPolicy.CLEARED,
            List.of(target("first", 0), target("second", 4)),
            List.of("first", "second"),
            List.of("second", "first")
        );

        manager.context(first, "first");
        manager.focus(first, "second");
        assertTrue(manager.route(cleared, new TextInput("x")).target().isEmpty());
        assertTrue(manager.focused().isEmpty());
        assertEquals(
            "first",
            manager.route(cleared, KeyStroke.of(KeyStroke.Key.TAB)).target().orElseThrow().id()
        );

        assertTrue(manager.deactivate(cleared.id()));
        assertTrue(manager.activeScopeId().isEmpty());
        assertTrue(manager.route(cleared, new TextInput("x")).target().isEmpty());
        assertTrue(manager.route(first, new TextInput("x")).focusChanged());
        assertEquals("first", manager.focused().orElseThrow());
    }

    @Test
    void scopeTransitionsAreReportedEvenWhenFocusedIdsAreEqualOrAbsent() {
        FocusManager<String> manager = new FocusManager<>();
        FocusScope<String, String> first = scope("first-scope", target("same", 0));
        FocusScope<String, String> second = scope("second-scope", target("same", 4));
        FocusScope<String, String> cleared = new FocusScope<>(
            "cleared",
            FocusResetPolicy.CLEARED,
            List.of(target("same", 0)),
            List.of("same"),
            List.of("same")
        );

        assertTrue(manager.route(first, new TextInput("x")).focusChanged());
        assertTrue(manager.route(second, new TextInput("x")).focusChanged());
        assertEquals("same", manager.focused().orElseThrow());

        assertTrue(manager.route(cleared, new TextInput("x")).focusChanged());
        assertTrue(manager.focused().isEmpty());
        assertTrue(manager.deactivate(cleared.id()));
        assertTrue(manager.activeScopeId().isEmpty());
    }

    @Test
    void focusRequestsAreAppliedCentrallyAgainstTheCurrentSnapshot() {
        FocusManager<String> manager = new FocusManager<>();
        FocusScope<String, String> scope = scope(
            "dialog",
            target("first", 0),
            target("second", 4)
        );

        manager.context(scope, "first");
        assertTrue(manager.apply(scope, "first", FocusRequest.NEXT));
        assertEquals("second", manager.focused().orElseThrow());
        assertTrue(manager.apply(scope, "second", FocusRequest.PREVIOUS));
        assertEquals("first", manager.focused().orElseThrow());
        assertTrue(manager.apply(scope, "second", FocusRequest.SELF));
        assertEquals("second", manager.focused().orElseThrow());
        assertTrue(manager.apply(scope, "second", FocusRequest.CLEAR));
        assertTrue(manager.focused().isEmpty());
        assertFalse(manager.apply(scope, "second", FocusRequest.NONE));
    }

    @Test
    void disabledTopmostTargetBlocksPointerWithoutTakingFocus() {
        FocusManager<String> manager = new FocusManager<>();
        RoutedTarget<String, String> bottom = target("bottom", 0);
        RoutedTarget<String, String> disabledTop = new RoutedTarget<>(
            "disabled",
            placement(new Rect(0, 0, 4, 1)),
            ROOT,
            true,
            false
        );
        FocusScope<String, String> scope = new FocusScope<>(
            "dialog",
            FocusResetPolicy.FIRST_FOCUSABLE,
            List.of(bottom, disabledTop),
            List.of("bottom", "disabled"),
            List.of("disabled", "bottom")
        );

        FocusRoute<String, String> route = manager.route(
            scope,
            pointer(0, 1, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT)
        );
        assertEquals("disabled", route.target().orElseThrow().id());
        assertFalse(route.context().orElseThrow().enabled());
        assertEquals("bottom", manager.focused().orElseThrow());
    }

    @Test
    void routeReturnsTheTypedPlacementFromTheSameSnapshot() {
        FocusManager<String> manager = new FocusManager<>();
        PlacedInteractiveWidget<String> placement = placement(new Rect(0, 0, 4, 1));
        RoutedTarget<String, String> target = target("only", placement, ROOT);
        FocusScope<String, String> scope = FocusScope.single(
            "single",
            FocusResetPolicy.FIRST_FOCUSABLE,
            target
        );

        FocusRoute<String, String> route = manager.route(scope, new TextInput("x"));
        assertSame(placement, route.target().orElseThrow().placement());
        assertTrue(route.context().orElseThrow().focused());
        assertThrows(
            IllegalArgumentException.class,
            () -> new FocusScope<>(
                "invalid",
                FocusResetPolicy.FIRST_FOCUSABLE,
                List.of(target),
                List.of("missing"),
                List.of("only")
            )
        );
    }

    @SafeVarargs
    private static FocusScope<String, String> scope(
        Object id,
        RoutedTarget<String, String>... targets
    ) {
        List<RoutedTarget<String, String>> copied = List.of(targets);
        List<String> order = copied.stream().map(RoutedTarget::id).toList();
        return new FocusScope<>(
            id,
            FocusResetPolicy.FIRST_FOCUSABLE,
            copied,
            order,
            order
        );
    }

    private static RoutedTarget<String, String> target(String id, int x) {
        return target(id, placement(new Rect(x, 0, 2, 1)), ROOT);
    }

    private static RoutedTarget<String, String> target(
        String id,
        PlacedInteractiveWidget<String> placement,
        FocusSpace space
    ) {
        return new RoutedTarget<>(id, placement, space);
    }

    private static RoutedTarget<String, String> disabledTarget(String id, int x) {
        return new RoutedTarget<>(id, placement(new Rect(x, 0, 2, 1)), ROOT, true, false);
    }

    private static PlacedInteractiveWidget<String> placement(Rect bounds) {
        return new PlacedInteractiveWidget<>() {
            @Override
            public Rect bounds() {
                return bounds;
            }

            @Override
            public void paint(Canvas canvas, Theme theme, WidgetState state) {
            }

            @Override
            public InteractionResult<String> handle(TuiInputEvent event, FocusContext context) {
                return InteractionResult.ignored();
            }
        };
    }

    private static PointerEvent pointer(
        int row,
        int column,
        PointerEvent.Kind kind,
        PointerEvent.Button button
    ) {
        return new PointerEvent(row, column, kind, button, Set.of());
    }
}
