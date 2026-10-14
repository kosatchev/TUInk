package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PointerCaptureManagerTest {
    private static final String SURFACE = "window";
    private static final Rect BOUNDS = new Rect(2, 2, 10, 4);
    private static final FocusSpace ROOT = FocusSpace.root(new Rect(0, 0, 80, 24));

    @Test
    void routesCurrentPlacementOutsideParentClipAndReleasesWithoutMovingKeyboardFocus() {
        TextField field = field();
        FocusSpace translated = new FocusSpace(new Point(20, 5), new Rect(0, 0, 20, 8));
        PointerCaptureManager<String> manager = new PointerCaptureManager<>();
        TextField.Placement initial = field.layout(new Rect(2, 2, 10, 1));
        RoutedTarget<String, TextField.Action> target = target("field", initial, translated, true);
        FocusScope<String, TextField.Action> scope = scope("search", target);
        FocusContext focused = new FocusContext(true, true, translated);
        InteractionResult<TextField.Action> press = initial.handle(pointer(7, 23, PointerEvent.Kind.PRESS), focused);
        manager.apply(SURFACE, scope.id(), target, press);
        assertEquals(PointerCaptureRequest.ACQUIRE, press.pointerCaptureRequest());

        TextField.Placement next = field.layout(initial.bounds());
        RoutedTarget<String, TextField.Action> nextTarget = target("field", next, translated, true);
        FocusScope<String, TextField.Action> nextScope = scope("search", nextTarget);
        PointerEvent drag = pointer(20, 49, PointerEvent.Kind.DRAG);
        assertFalse(translated.localPoint(drag).isPresent());
        PointerCaptureManager.Target<String, TextField.Action> routed = manager
            .route(SURFACE, List.of(nextScope), drag).orElseThrow();
        assertNotSame(initial, routed.target().placement());
        assertSame(next, routed.target().placement());
        InteractionResult<TextField.Action> dragResult = next.handle(drag, focused);
        assertEquals(FocusRequest.NONE, dragResult.focusRequest());
        assertTrue(field.hasSelection());

        PointerEvent release = new PointerEvent(23, 79, PointerEvent.Kind.RELEASE,
            PointerEvent.Button.NONE, Set.of());
        InteractionResult<TextField.Action> released = next.handle(release, focused);
        manager.apply(SURFACE, nextScope.id(), nextTarget, released);
        assertFalse(field.mouseSelecting());
        assertTrue(manager.captured().isEmpty());
        assertTrue(manager.route(SURFACE, List.of(nextScope), drag).isEmpty());
    }

    @Test
    void equalTargetIdsInDifferentScopesDoNotRedirectCapture() {
        TextField first = field();
        TextField second = field();
        TextField.Placement firstPlacement = first.layout(new Rect(0, 0, 10, 1));
        TextField.Placement secondPlacement = second.layout(new Rect(0, 2, 10, 1));
        RoutedTarget<String, TextField.Action> firstTarget = target("field", firstPlacement, ROOT, true);
        RoutedTarget<String, TextField.Action> secondTarget = target("field", secondPlacement, ROOT, true);
        FocusScope<String, TextField.Action> firstScope = scope("find", firstTarget);
        FocusScope<String, TextField.Action> secondScope = scope("save", secondTarget);
        PointerCaptureManager<String> manager = new PointerCaptureManager<>();
        InteractionResult<TextField.Action> press = secondPlacement.handle(
            pointer(2, 2, PointerEvent.Kind.PRESS), new FocusContext(true, true, ROOT));
        manager.apply(SURFACE, secondScope.id(), secondTarget, press);

        assertSame(secondTarget, manager.route(SURFACE, List.of(firstScope, secondScope),
            pointer(0, 4, PointerEvent.Kind.DRAG)).orElseThrow().target());
        assertThrows(IllegalStateException.class, () -> manager.route("other-window",
            List.of(firstScope, secondScope), pointer(0, 4, PointerEvent.Kind.DRAG)));
        manager.cancel(SURFACE, List.of(firstScope, secondScope),
            PointerCaptureCancellation.SURFACE_CHANGED).orElseThrow();
        assertFalse(second.mouseSelecting());
        assertFalse(first.mouseSelecting());
    }

    @Test
    void immutableListStateIsAdoptedBeforeRemovingItsOwner() {
        PointerCaptureManager<String> manager = new PointerCaptureManager<>();
        ListView.Placement initial = list(new ListView.State(0, 0));
        RoutedTarget<String, ListView.Action> target = target("list", initial, ROOT, false);
        FocusScope<String, ListView.Action> scope = scope("help", target);
        InteractionResult<ListView.Action> press = pressScrollbar(initial);
        ListView.State capturedState = assertInstanceOf(ListView.StateChanged.class,
            press.output().orElseThrow()).state();
        manager.apply(SURFACE, scope.id(), target, press);

        ListView.Placement captured = list(capturedState);
        RoutedTarget<String, ListView.Action> current = target("list", captured, ROOT, false);
        FocusScope<String, ListView.Action> currentScope = scope("help", current);
        assertThrows(IllegalStateException.class, () -> manager.route(SURFACE, List.of(),
            pointer(20, 30, PointerEvent.Kind.DRAG)));
        PointerCaptureManager.Cancellation<String, ListView.Action> cancellation = manager.cancel(
            SURFACE, List.of(currentScope), PointerCaptureCancellation.HIDDEN).orElseThrow();
        ListView.State cancelled = assertInstanceOf(ListView.StateChanged.class,
            cancellation.result().output().orElseThrow()).state();
        assertFalse(list(cancelled).pointerCaptureActive());
        assertTrue(captured.pointerCaptureActive());
        assertTrue(manager.captured().isEmpty());
        assertTrue(manager.route(SURFACE, List.of(), pointer(20, 30, PointerEvent.Kind.DRAG)).isEmpty());
    }

    @Test
    void scrollbarCaptureDoesNotRequireKeyboardFocusAndAdoptsDragAndReleaseOutputs() {
        PointerCaptureManager<String> manager = new PointerCaptureManager<>();
        ListView.Placement initial = list(new ListView.State(0, 0));
        RoutedTarget<String, ListView.Action> initialTarget = target("list", initial, ROOT, false);
        InteractionResult<ListView.Action> press = pressScrollbar(initial);
        ListView.State state = assertInstanceOf(ListView.StateChanged.class, press.output().orElseThrow()).state();
        manager.apply(SURFACE, "help", initialTarget, press);
        ListView.Placement current = list(state);
        RoutedTarget<String, ListView.Action> currentTarget = target("list", current, ROOT, false);
        FocusScope<String, ListView.Action> currentScope = scope("help", currentTarget);
        PointerEvent drag = pointer(20, 70, PointerEvent.Kind.DRAG);
        RoutedTarget<String, ListView.Action> routed = manager.route(SURFACE, List.of(currentScope), drag)
            .orElseThrow().target();
        InteractionResult<ListView.Action> dragResult = routed.placement().handle(drag,
            new FocusContext(false, true, ROOT));
        assertEquals(FocusRequest.NONE, dragResult.focusRequest());
        ListView.State moved = assertInstanceOf(ListView.StateChanged.class,
            dragResult.output().orElseThrow()).state();
        assertTrue(moved.firstVisibleIndex() > 0);

        current = list(moved);
        currentTarget = target("list", current, ROOT, false);
        InteractionResult<ListView.Action> release = current.handle(
            pointer(23, 79, PointerEvent.Kind.RELEASE), new FocusContext(false, true, ROOT));
        assertFalse(assertInstanceOf(ListView.StateChanged.class, release.output().orElseThrow())
            .state().drag() != null);
        manager.apply(SURFACE, "help", currentTarget, release);
        assertTrue(manager.captured().isEmpty());
    }

    @Test
    void lifecycleBoundariesCancelMutableOwnersThroughTheCurrentSnapshot() {
        List<TuiInputEvent> boundaries = List.of(new TextInput("x"), KeyStroke.of(KeyStroke.Key.TAB),
            new ResizeEvent(30, 100), pointer(15, 20, PointerEvent.Kind.PRESS));
        for (TuiInputEvent boundary : boundaries) {
            TextField field = field();
            TextField.Placement placement = field.layout(new Rect(0, 0, 10, 1));
            RoutedTarget<String, TextField.Action> target = target("field", placement, ROOT, true);
            FocusScope<String, TextField.Action> scope = scope("search", target);
            PointerCaptureManager<String> manager = new PointerCaptureManager<>();
            manager.apply(SURFACE, scope.id(), target, placement.handle(
                pointer(0, 1, PointerEvent.Kind.PRESS), new FocusContext(true, true, ROOT)));

            PointerCaptureManager.Cancellation<String, TextField.Action> cancelled = manager.beforeInput(
                SURFACE, List.of(scope), boundary).orElseThrow();
            assertEquals(PointerCaptureRequest.RELEASE, cancelled.result().pointerCaptureRequest());
            assertFalse(field.mouseSelecting());
            assertTrue(manager.captured().isEmpty());
        }
    }

    @Test
    void disabledAndHiddenOwnersReturnAdoptableCancellationOutputs() {
        for (boolean disabled : List.of(false, true)) {
            ListView.Placement initial = list(new ListView.State(0, 0));
            RoutedTarget<String, ListView.Action> target = target("list", initial, ROOT, false);
            InteractionResult<ListView.Action> press = pressScrollbar(initial);
            PointerCaptureManager<String> manager = new PointerCaptureManager<>();
            manager.apply(SURFACE, "help", target, press);
            ListView.State captured = assertInstanceOf(ListView.StateChanged.class,
                press.output().orElseThrow()).state();
            FocusSpace space = disabled ? ROOT : FocusSpace.root(new Rect(50, 20, 2, 2));
            RoutedTarget<String, ListView.Action> unavailable = new RoutedTarget<>("list", list(captured),
                space, false, !disabled);
            PointerCaptureManager.Cancellation<String, ListView.Action> cancelled = manager.reconcile(
                SURFACE, List.of(scope("help", unavailable))).orElseThrow();
            assertEquals(null, assertInstanceOf(ListView.StateChanged.class,
                cancelled.result().output().orElseThrow()).state().drag());
            assertTrue(manager.captured().isEmpty());
        }
    }

    @Test
    void treeAndMenuPublishCaptureEffectsAndCancellationUsesTheirNormalStateOutputs() {
        List<TreeView.Item<Integer>> items = new ArrayList<>();
        List<MenuBar.Entry<Integer>> entries = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            items.add(new TreeView.Item<>(index, "row" + index, 0, TreeView.Kind.LEAF));
            entries.add(new MenuBar.Item<>(index, "item" + index, 0, true));
        }
        TreeView<Integer> tree = new TreeView<>(items, new TreeView.State(0, 0, 0));
        TreeView.Placement<Integer> treePlacement = tree.layout(BOUNDS);
        Rect treeBar = treePlacement.verticalScrollbarBounds().orElseThrow();
        InteractionResult<TreeView.Action<Integer>> treePress = treePlacement.handle(
            pointer(treeBar.y(), treeBar.x(), PointerEvent.Kind.PRESS), new FocusContext(false, true, ROOT));
        assertEquals(PointerCaptureRequest.ACQUIRE, treePress.pointerCaptureRequest());
        TreeView.State treeState = assertInstanceOf(TreeView.StateChanged.class,
            treePress.output().orElseThrow()).state();
        InteractionResult<TreeView.Action<Integer>> treeCancel = tree.layout(BOUNDS, treeState)
            .cancelPointerCapture(PointerCaptureCancellation.GEOMETRY_CHANGED);
        assertEquals(null, assertInstanceOf(TreeView.StateChanged.class,
            treeCancel.output().orElseThrow()).state().drag());
        assertEquals(PointerCaptureRequest.RELEASE, treeCancel.pointerCaptureRequest());

        List<MenuBar.Menu<Integer>> menus = List.of(new MenuBar.Menu<>("File", 0, entries));
        Rect menuBounds = new Rect(0, 0, 30, 7);
        MenuBar.Placement<Integer> menu = new MenuBar<>(menus, MenuBar.State.open(0)).layout(menuBounds);
        Rect track = menu.scrollbar().orElseThrow().track();
        InteractionResult<MenuBar.Action<Integer>> menuPress = menu.handle(
            pointer(track.y(), track.x(), PointerEvent.Kind.PRESS), new FocusContext(true, true, ROOT));
        assertEquals(PointerCaptureRequest.ACQUIRE, menuPress.pointerCaptureRequest());
        MenuBar.State menuState = assertInstanceOf(MenuBar.StateChanged.class,
            menuPress.output().orElseThrow()).state();
        InteractionResult<MenuBar.Action<Integer>> menuCancel = new MenuBar<>(menus, menuState).layout(menuBounds)
            .cancelPointerCapture(PointerCaptureCancellation.FOCUS_CHANGED);
        assertEquals(null, assertInstanceOf(MenuBar.StateChanged.class,
            menuCancel.output().orElseThrow()).state().drag());
        assertEquals(PointerCaptureRequest.RELEASE, menuCancel.pointerCaptureRequest());
    }

    @Test
    void independentEffectsSurviveOutputAdaptationAndConflictingCapturesCannotMerge() {
        InteractionResult<String> result = InteractionResult.output("state")
            .withPointerCapture(PointerCaptureRequest.ACQUIRE);
        assertEquals(Optional.of(5), result.mapOutput(String::length).output());
        assertEquals(PointerCaptureRequest.ACQUIRE, result.mapOutput(String::length).pointerCaptureRequest());
        assertThrows(IllegalArgumentException.class, () -> result.merge(InteractionResult.<String>handled()
            .withPointerCapture(PointerCaptureRequest.RELEASE)));
        assertThrows(IllegalArgumentException.class, () -> new InteractionResult<>(false,
            FocusRequest.NONE, Optional.empty(), PointerCaptureRequest.ACQUIRE));
    }

    private static TextField field() {
        TextField field = new TextField();
        field.set("abcdefghijklmnopqrstuvwxyz0123456789");
        field.moveHome();
        return field;
    }

    private static ListView.Placement list(ListView.State state) {
        return new ListView(50, index -> "row" + index, state).layout(BOUNDS);
    }

    private static InteractionResult<ListView.Action> pressScrollbar(ListView.Placement placement) {
        Rect track = placement.scrollbarBounds().orElseThrow();
        return placement.handle(pointer(track.y(), track.x(), PointerEvent.Kind.PRESS),
            new FocusContext(false, true, ROOT));
    }

    private static <A> RoutedTarget<String, A> target(String id, PlacedInteractiveWidget<A> placement,
        FocusSpace space, boolean focusable) {
        return new RoutedTarget<>(id, placement, space, focusable, true);
    }

    private static <A> FocusScope<String, A> scope(String id, RoutedTarget<String, A> target) {
        return FocusScope.single(id, FocusResetPolicy.CLEARED, target);
    }

    private static PointerEvent pointer(int row, int column, PointerEvent.Kind kind) {
        return new PointerEvent(row, column, kind, PointerEvent.Button.LEFT, Set.of());
    }
}
