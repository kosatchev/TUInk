package dev.tuink.render.surface;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.scroll.ScrollLayout;
import dev.tuiframe.scroll.Scrollbar;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TextSurfacePointerTest {
    private static final class Geometry implements TextSurfaceGeometry<TextSurfaceInteraction.Visual> {
        public int totalRows() { return 30; }
        public int totalColumns() { return 80; }
        public int rowWidth(int row) { return 80; }
        public TextSurfaceInteraction.Visual visualPosition(TextSurfaceInteraction.Visual position) { return position; }
        public TextSurfaceInteraction.Visual positionAt(TextSurfaceInteraction.Visual visual) { return visual; }
        public TextSurfaceInteraction.Visual normalize(TextSurfaceInteraction.Visual visual) {
            return new TextSurfaceInteraction.Visual(Math.clamp(visual.row(), 0, 29), Math.clamp(visual.column(), 0, 80));
        }
        public int previousColumn(int row, int column) { return column - 1; }
        public int nextColumn(int row, int column) { return column + 1; }
    }
    private static TextSurfacePointer.Placement<TextSurfaceInteraction.Visual> placement(TextSurfacePointer.Policy policy) {
        var bounds = new Rect(4, 2, 20, 5);
        var layout = ScrollLayout.calculate(bounds, 30, 80, 0);
        var content = layout.contentBounds();
        return new TextSurfacePointer.Placement<>(bounds, layout,
            Scrollbar.vertical(layout.verticalScrollbarBounds(), 30, 0),
            Scrollbar.horizontal(layout.horizontalScrollbarBounds(), 80, 0), new Geometry(),
            new TextSurfaceInteraction.Viewport(0, 0, content.height(), content.width()), 30, 80, policy);
    }
    private static PointerEvent event(int row, int column, PointerEvent.Kind kind) {
        return new PointerEvent(row, column, kind, PointerEvent.Button.LEFT, Set.of());
    }

    @Test void shiftBreaksTheDoubleClickSequenceAndReleaseFinishesExactlyOneGesture() {
        var pointer = new TextSurfacePointer<TextSurfaceInteraction.Visual>((a, b) -> a.row() == b.row() && Math.abs(a.column() - b.column()) <= 1);
        var placement = placement(TextSurfacePointer.Policy.source(false));
        var press = event(3, 6, PointerEvent.Kind.PRESS);
        assertEquals(TextSurfacePointer.Action.BEGIN, pointer.handle(placement, press, false, 1_000).action());
        assertEquals(TextSurfacePointer.Action.FINISH, pointer.handle(placement, event(3, 6, PointerEvent.Kind.RELEASE), false, 2_000).action());
        assertEquals(TextSurfacePointer.Action.WORD, pointer.handle(placement, press, false, 3_000).action());
        pointer.finishSelection();
        assertEquals(TextSurfacePointer.Action.EXTEND, pointer.handle(placement, press, true, 4_000).action());
        pointer.finishSelection();
        assertEquals(TextSurfacePointer.Action.BEGIN, pointer.handle(placement, press, false, 5_000).action());
        assertTrue(pointer.capturing());
        assertTrue(pointer.finishSelection()); assertFalse(pointer.finishSelection());
    }

    @Test void capturedDragUsesBoundedEdgeStepsAndTimedContinuationWithoutRetainingPlacements() {
        var pointer = new TextSurfacePointer<TextSurfaceInteraction.Visual>((a, b) -> a.equals(b));
        var placement = placement(TextSurfacePointer.Policy.source(false));
        pointer.handle(placement, event(3, 6, PointerEvent.Kind.PRESS), false, 0);
        var drag = event(1_000, 6, PointerEvent.Kind.DRAG);
        var intent = pointer.handle(placement, drag, false, 10);
        assertEquals(TextSurfacePointer.Action.EXTEND, intent.action());
        assertEquals(6, intent.viewport().top());
        assertEquals(9, intent.target().row());
        pointer.afterDrag(placement, drag, true, 10);
        assertEquals(50_000_000L, pointer.nanosUntilRepeat(10));
        assertNull(pointer.repeatIfDue(49_999_999L));
        assertSame(drag, pointer.repeatIfDue(50_000_010L));
        pointer.cancel(); assertEquals(-1, pointer.nanosUntilRepeat(Long.MAX_VALUE));
        assertFalse(pointer.capturing());
    }

    @Test void scrollbarCapturePreservesGrabOffsetAndNeverStartsContentSelection() {
        var pointer = new TextSurfacePointer<TextSurfaceInteraction.Visual>((a, b) -> a.equals(b));
        var placement = placement(TextSurfacePointer.Policy.preview());
        var track = placement.layout().verticalScrollbarBounds();
        var press = pointer.handle(placement, event(track.y(), track.x(), PointerEvent.Kind.PRESS), false, 0);
        assertEquals(TextSurfacePointer.Action.SCROLL, press.action());
        assertNotNull(press.drag()); assertFalse(pointer.selecting()); assertTrue(pointer.capturing());
        var drag = pointer.handle(placement, event(Integer.MAX_VALUE, 0, PointerEvent.Kind.DRAG), false, 1);
        assertEquals(30 - placement.viewport().rows(), drag.viewport().top());
        assertEquals(press.drag().grabOffset(), drag.drag().grabOffset());
        var release = pointer.handle(placement, event(0, 0, PointerEvent.Kind.RELEASE), false, 2);
        assertTrue(release.consumed()); assertFalse(pointer.capturing());
        assertEquals(TextSurfacePointer.Action.NONE, release.action());
    }

    @Test void capturedSelectionSaturatesExtremeCoordinatesAndEmptyContentCannotStartAGesture() {
        var pointer = new TextSurfacePointer<TextSurfaceInteraction.Visual>((a, b) -> a.equals(b));
        var placement = placement(TextSurfacePointer.Policy.source(false));
        pointer.handle(placement, event(3, 6, PointerEvent.Kind.PRESS), false, 0);
        var drag = pointer.handle(placement, event(0, 0, PointerEvent.Kind.DRAG), false, 1);
        assertEquals(new TextSurfaceInteraction.Visual(0, 0), drag.target());
        pointer.cancel();
        var bounds = new Rect(4, 2, 0, 0);
        var empty = new TextSurfacePointer.Placement<TextSurfaceInteraction.Visual>(bounds, ScrollLayout.vertical(bounds, 30),
            null, null, new Geometry(), new TextSurfaceInteraction.Viewport(0, 0, 0, 0), 30, 80, TextSurfacePointer.Policy.source(false));
        assertFalse(pointer.handle(empty, event(2, 4, PointerEvent.Kind.PRESS), false, 2).consumed());
        assertFalse(pointer.capturing());
    }

    @Test void previewDragPreservesItsVisualAffinityPolicyWithoutASecondStateMachine() {
        var pointer = new TextSurfacePointer<TextSurfaceInteraction.Visual>((a, b) -> a.equals(b));
        var placement = placement(TextSurfacePointer.Policy.preview());
        pointer.handle(placement, event(3, 6, PointerEvent.Kind.PRESS), false, 0);
        var drag = pointer.handle(placement, event(10, 6, PointerEvent.Kind.DRAG), false, 1);
        assertEquals(0, drag.viewport().top(), "Preview reveal/pan is applied by its projection adapter");
        assertEquals(new TextSurfaceInteraction.Visual(8, 2), drag.target());
        pointer.afterDrag(placement, event(10, 6, PointerEvent.Kind.DRAG), true, 1);
        assertEquals(-1, pointer.nanosUntilRepeat(1));
    }
}
