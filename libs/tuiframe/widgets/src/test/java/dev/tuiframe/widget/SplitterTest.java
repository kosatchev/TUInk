package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SplitterTest {
    private static final Rect BOUNDS = new Rect(2, 1, 30, 8);
    private static final FocusSpace SPACE = new FocusSpace(new Point(10, 5), new Rect(0, 0, 40, 12));
    private static final FocusContext UNFOCUSED = new FocusContext(false, true, SPACE);

    @Test
    void capturedDragUsesCurrentTranslatedPlacementOutsideItsClipWithoutStealingFocus() {
        SplitPane split = SplitPane.sideBySide(BOUNDS, 10, 4, 6);
        var initial = new Splitter(split, Splitter.State.initial()).layout();
        var target = target(initial);
        PointerCaptureManager<String> manager = new PointerCaptureManager<>();
        var press = initial.handle(pointer(7, 22, PointerEvent.Kind.PRESS), UNFOCUSED);
        assertEquals(FocusRequest.NONE, press.focusRequest());
        assertEquals(PointerCaptureRequest.ACQUIRE, press.pointerCaptureRequest());
        Splitter.StateChanged pressed = press.output().orElseThrow();
        manager.apply("window", "panes", target, press);

        var current = new Splitter(split, pressed.state()).layout();
        var currentTarget = target(current);
        var scope = FocusScope.single("panes", FocusResetPolicy.FIRST_FOCUSABLE, currentTarget);
        PointerEvent drag = pointer(30, 60, PointerEvent.Kind.DRAG);
        assertTrue(SPACE.localPoint(drag).isEmpty());
        var routed = manager.route("window", List.of(scope), drag).orElseThrow();
        assertSame(current, routed.target().placement());
        var moved = current.handle(drag, UNFOCUSED);
        assertEquals(23, moved.output().orElseThrow().firstExtent());
        assertEquals(FocusRequest.NONE, moved.focusRequest());
        assertTrue(moved.output().orElseThrow().state().dragging());

        Splitter.StateChanged change = moved.output().orElseThrow();
        split = SplitPane.sideBySide(BOUNDS, change.firstExtent(), 4, 6);
        current = new Splitter(split, change.state()).layout();
        assertEquals(4, current.handle(pointer(0, 0, PointerEvent.Kind.DRAG), UNFOCUSED)
            .output().orElseThrow().firstExtent());
        var released = current.handle(pointer(30, 60, PointerEvent.Kind.RELEASE), UNFOCUSED);
        assertFalse(released.output().orElseThrow().state().dragging());
        assertEquals(23, released.output().orElseThrow().firstExtent());
        manager.apply("window", "panes", target(current), released);
        assertTrue(manager.captured().isEmpty());
        assertFalse(initial.pointerCaptureActive(), "previous immutable placements retain their own state");
    }

    @Test
    void keyboardResizeRespectsAxisFocusAndModifiersWhileOtherInputIsIgnored() {
        SplitPane split = SplitPane.resolve(BOUNDS, SplitPane.Axis.VERTICAL, 3, 2, 2);
        var placement = new Splitter(split, Splitter.State.initial()).layout();
        FocusContext focused = new FocusContext(true, true, SPACE);
        assertEquals(4, placement.handle(KeyStroke.of(KeyStroke.Key.ARROW_DOWN), focused)
            .output().orElseThrow().firstExtent());
        assertEquals(2, placement.handle(KeyStroke.of(KeyStroke.Key.HOME), focused)
            .output().orElseThrow().firstExtent());
        assertEquals(5, placement.handle(KeyStroke.of(KeyStroke.Key.END), focused)
            .output().orElseThrow().firstExtent());
        assertFalse(placement.handle(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT), focused).consumed());
        assertFalse(placement.handle(KeyStroke.of(KeyStroke.Key.ARROW_DOWN), UNFOCUSED).consumed());
        assertFalse(placement.handle(KeyStroke.of(KeyStroke.Key.ARROW_DOWN, KeyStroke.Modifier.CTRL), focused).consumed());
        assertFalse(placement.handle(pointer(6, 12, PointerEvent.Kind.PRESS), focused).consumed());

        var capture = new Splitter(split, new Splitter.State(true)).layout();
        assertEquals(5, capture.handle(pointer(40, 1, PointerEvent.Kind.DRAG), UNFOCUSED)
            .output().orElseThrow().firstExtent());
        assertEquals(2, capture.handle(pointer(0, 1, PointerEvent.Kind.DRAG), UNFOCUSED)
            .output().orElseThrow().firstExtent());
    }

    @Test
    void resizeAndExplicitLifecycleCancellationReleaseGestureWithoutChangingExtent() {
        SplitPane split = SplitPane.sideBySide(BOUNDS, 10, 4, 6);
        var placement = new Splitter(split, new Splitter.State(true)).layout();
        var resized = placement.handle(new ResizeEvent(20, 80), UNFOCUSED);
        assertEquals(PointerCaptureRequest.RELEASE, resized.pointerCaptureRequest());
        assertEquals(new Splitter.StateChanged(10, Splitter.State.initial()), resized.output().orElseThrow());
        var cancelled = placement.cancelPointerCapture(PointerCaptureCancellation.HIDDEN);
        assertEquals(resized, cancelled);

        SplitPane collapsed = SplitPane.sideBySide(new Rect(0, 0, 3, 8), 10, 4, 6);
        var unavailable = new Splitter(collapsed, Splitter.State.initial()).layout();
        assertFalse(unavailable.interactionReady());
        assertFalse(unavailable.handle(pointer(0, 0, PointerEvent.Kind.PRESS), UNFOCUSED).consumed());
        var disabled = new FocusContext(false, false, SPACE);
        assertFalse(new Splitter(split, Splitter.State.initial()).layout()
            .handle(pointer(7, 22, PointerEvent.Kind.PRESS), disabled).consumed());
    }

    @Test
    void paintingAndHitTestingShareTheDividerAllocationAndRespectCanvasClip() {
        SplitPane split = SplitPane.sideBySide(BOUNDS, 10, 4, 6);
        Splitter splitter = new Splitter(split, new Splitter.State(true));
        var placement = splitter.layout(split.dividerBounds());
        assertThrows(IllegalArgumentException.class, () -> splitter.layout(BOUNDS));
        CellStyle normal = CellStyle.fg(CellColor.WHITE);
        CellStyle active = CellStyle.fg(CellColor.CYAN);
        Theme theme = role -> WidgetThemeKeys.SPLITTER_FOCUSED.equals(role) ? active : normal;
        CellBuffer buffer = new CellBuffer(12, 40);
        Canvas canvas = new Canvas(buffer);
        canvas.fill(new Rect(0, 0, 40, 12), normal);
        canvas.clip(new Rect(0, 2, 40, 3));
        placement.paint(canvas, theme, WidgetState.NORMAL);
        assertEquals("│", buffer.get(2, 12).grapheme());
        assertEquals(active, buffer.get(2, 12).style());
        assertEquals(" ", buffer.get(1, 12).grapheme());
        assertEquals(" ", buffer.get(2, 11).grapheme());
        assertEquals(" ", buffer.get(2, 13).grapheme());
        assertTrue(placement.hit(2, 12));
        assertFalse(placement.hit(2, 11));
    }

    private static RoutedTarget<String, Splitter.StateChanged> target(Splitter.Placement placement) {
        return new RoutedTarget<>("divider", placement, SPACE, false, true);
    }

    private static PointerEvent pointer(int row, int column, PointerEvent.Kind kind) {
        return new PointerEvent(row, column, kind,
            kind == PointerEvent.Kind.RELEASE ? PointerEvent.Button.NONE : PointerEvent.Button.LEFT, Set.of());
    }
}
