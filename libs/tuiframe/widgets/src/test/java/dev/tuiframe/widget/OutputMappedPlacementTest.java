package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.style.CellStyle;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutputMappedPlacementTest {
    private static final FocusSpace SPACE = FocusSpace.root(new Rect(0, 0, 80, 24));
    private static final FocusContext FOCUSED = new FocusContext(true, true, SPACE);

    @Test
    void plainMappingPreservesPaintHitGeometryContextAndIndependentFocusEffects() {
        ShapePlacement source = new ShapePlacement();
        var mapped = source.mapOutput(value -> "value=" + value);
        assertFalse(mapped instanceof PlacedPointerCapture<?>);
        assertFalse(mapped instanceof PlacedCursorSource);
        assertSame(source.bounds(), mapped.bounds());
        assertTrue(mapped.hit(1, 2));
        assertFalse(mapped.hit(1, 3));
        assertTrue(mapped.hit(new Point(4, 1)));
        assertFalse(mapped.hit(new Point(3, 1)));
        assertEquals(source.interactionReady(), mapped.interactionReady());
        Canvas canvas = new Canvas(new CellBuffer(3, 10));
        Theme theme = Theme.fixed(CellStyle.DEFAULT);
        mapped.paint(canvas, theme, WidgetState.FOCUSED);
        assertSame(canvas, source.canvas);
        assertSame(theme, source.theme);
        assertSame(WidgetState.FOCUSED, source.state);
        TextInput event = new TextInput("x");

        var result = mapped.handle(event, FOCUSED);

        assertSame(event, source.event);
        assertSame(FOCUSED, source.context);
        assertEquals(Optional.of("value=7"), result.output());
        assertTrue(result.consumed());
        assertEquals(FocusRequest.NEXT, result.focusRequest());
        assertEquals(PointerCaptureRequest.NONE, result.pointerCaptureRequest());
        assertThrows(NullPointerException.class, () -> source.mapOutput(null));
    }

    @Test
    void cursorOnlyMappingDoesNotAdvertiseCaptureAndForwardsCursorContext() {
        CursorPlacement source = new CursorPlacement();
        var mapped = source.mapOutput(Object::toString);
        assertFalse(mapped instanceof PlacedPointerCapture<?>);
        var cursor = assertInstanceOf(PlacedCursorSource.class, mapped);

        assertEquals(Optional.of(new Point(5, 1)), cursor.cursorPosition(FOCUSED));
        assertSame(FOCUSED, source.cursorContext);
        assertTrue(cursor.cursorPosition(new FocusContext(false, true, SPACE)).isEmpty());
    }

    @Test
    void captureOnlyMappingMapsCancellationOutputAndKeepsCaptureEffects() {
        Rect bounds = new Rect(2, 2, 10, 4);
        var initial = new ListView(50, index -> "row" + index, ListView.State.initial()).layout(bounds);
        var mapped = initial.mapOutput(action -> ((ListView.StateChanged) action).state());
        assertFalse(mapped instanceof PlacedCursorSource);
        assertInstanceOf(PlacedPointerCapture.class, mapped);
        Rect scrollbar = initial.scrollbarBounds().orElseThrow();

        var press = mapped.handle(pointer(scrollbar.y(), scrollbar.x()), new FocusContext(false, true, SPACE));

        assertEquals(PointerCaptureRequest.ACQUIRE, press.pointerCaptureRequest());
        assertEquals(FocusRequest.SELF, press.focusRequest());
        ListView.State state = press.output().orElseThrow();
        assertTrue(state.drag() != null);
        var current = new ListView(50, index -> "row" + index, state).layout(bounds)
            .mapOutput(action -> ((ListView.StateChanged) action).state());
        PlacedPointerCapture<?> capture = assertInstanceOf(PlacedPointerCapture.class, current);
        assertTrue(capture.pointerCaptureActive());

        var cancelled = capture.cancelPointerCapture(PointerCaptureCancellation.HIDDEN);

        assertTrue(cancelled.consumed());
        assertEquals(PointerCaptureRequest.RELEASE, cancelled.pointerCaptureRequest());
        assertEquals(FocusRequest.NONE, cancelled.focusRequest());
        assertEquals(null, assertInstanceOf(ListView.State.class, cancelled.output().orElseThrow()).drag());
    }

    @Test
    void mappedTextFieldKeepsBothCapabilitiesWithoutSynthesizingOutputForPressOrCancellation() {
        TextField field = new TextField();
        field.set("A😀B");
        Rect bounds = new Rect(2, 1, 8, 1);
        AtomicInteger mappings = new AtomicInteger();
        var mapped = field.layout(bounds).mapOutput(action -> {
            mappings.incrementAndGet();
            return action.name();
        });
        PlacedPointerCapture<?> capture = assertInstanceOf(PlacedPointerCapture.class, mapped);
        var cursor = assertInstanceOf(PlacedCursorSource.class, mapped);
        assertEquals(field.layout(bounds).cursorPosition(FOCUSED), cursor.cursorPosition(FOCUSED));

        var press = mapped.handle(pointer(1, 3), FOCUSED);

        assertEquals(FocusRequest.SELF, press.focusRequest());
        assertEquals(PointerCaptureRequest.ACQUIRE, press.pointerCaptureRequest());
        assertTrue(press.output().isEmpty());
        assertEquals(0, mappings.get());
        assertTrue(capture.pointerCaptureActive());
        var cancellation = capture.cancelPointerCapture(PointerCaptureCancellation.KEYBOARD_INPUT);
        assertEquals(PointerCaptureRequest.RELEASE, cancellation.pointerCaptureRequest());
        assertTrue(cancellation.output().isEmpty());
        assertEquals(0, mappings.get());
        assertFalse(capture.pointerCaptureActive());

        var edited = field.layout(bounds).mapOutput(Enum::name).handle(new TextInput("X"), FOCUSED);
        assertEquals(Optional.of("VALUE_CHANGED"), edited.output());
        assertEquals("AX😀B", field.text());
        assertEquals(Optional.of("SUBMITTED"), field.layout(bounds).mapOutput(Enum::name)
            .handle(KeyStroke.of(KeyStroke.Key.ENTER), FOCUSED).output());
    }

    @Test
    void mappedButtonRetainsIntrinsicAndParentClipGates() {
        Button button = new Button("中e\u0301");
        var narrow = button.layout(new Rect(2, 1, 2, 1)).mapOutput(ignored -> "save");
        assertFalse(narrow.interactionReady());
        assertFalse(new RoutedTarget<>("save", narrow, SPACE).focusEligible());
        assertFalse(narrow.handle(KeyStroke.of(KeyStroke.Key.ENTER), FOCUSED).consumed());
        assertFalse(narrow.handle(pointer(1, 2), FOCUSED).consumed());
        var full = button.layout(new Rect(2, 1, button.width(), 1)).mapOutput(ignored -> "save");
        assertFalse(full instanceof PlacedPointerCapture<?>);
        assertFalse(full instanceof PlacedCursorSource);
        assertEquals(Optional.of("save"), full.handle(KeyStroke.of(KeyStroke.Key.ENTER), FOCUSED).output());
        FocusContext clipped = new FocusContext(true, true,
            FocusSpace.root(new Rect(0, 0, 3, 24)));
        assertFalse(full.handle(KeyStroke.of(KeyStroke.Key.ENTER), clipped).consumed());
        assertFalse(full.handle(pointer(1, 2), clipped).consumed());
    }

    private static PointerEvent pointer(int row, int column) {
        return new PointerEvent(row, column, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of());
    }

    private static class ShapePlacement implements PlacedInteractiveWidget<Integer> {
        private final Rect bounds = new Rect(2, 1, 4, 1);
        private Canvas canvas;
        private Theme theme;
        private WidgetState state;
        private TuiInputEvent event;
        private FocusContext context;

        @Override public Rect bounds() { return bounds; }
        @Override public boolean hit(int row, int column) { return row == 1 && column == 2; }
        @Override public boolean hit(Point point) { return point.equals(new Point(4, 1)); }
        @Override public void paint(Canvas canvas, Theme theme, WidgetState state) {
            this.canvas = canvas;
            this.theme = theme;
            this.state = state;
        }
        @Override public InteractionResult<Integer> handle(TuiInputEvent event, FocusContext context) {
            this.event = event;
            this.context = context;
            return InteractionResult.output(7).merge(InteractionResult.focus(FocusRequest.NEXT));
        }
    }

    private static final class CursorPlacement extends ShapePlacement implements PlacedCursorSource {
        private FocusContext cursorContext;

        @Override public Optional<Point> cursorPosition(FocusContext context) {
            cursorContext = context;
            return context.focused() ? Optional.of(new Point(5, 1)) : Optional.empty();
        }
    }
}
