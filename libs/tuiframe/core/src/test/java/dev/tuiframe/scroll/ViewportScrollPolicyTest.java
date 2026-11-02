package dev.tuiframe.scroll;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.screen.ScrollHint;
import org.junit.jupiter.api.Test;

class ViewportScrollPolicyTest {
    private static final Rect BODY = new Rect(2, 3, 40, 12);

    @Test void compatibleVerticalMovesUseOnlyTheDeclaredBody() {
        var before = viewport("a", 1, BODY, 20, 5, false);
        assertEquals(new ScrollHint.Vertical(BODY), ViewportScrollPolicy.between(before,
            viewport("a", 1, BODY, 23, 5, false)));
        assertEquals(new ScrollHint.Vertical(BODY), ViewportScrollPolicy.between(before,
            viewport("a", 1, BODY, 17, 5, false)));
    }

    @Test void horizontalMovesCarryTheExactSignedCellDelta() {
        var before = viewport("a", 1, BODY, 20, 5, true);
        assertEquals(new ScrollHint.Horizontal(BODY, 3), ViewportScrollPolicy.between(before,
            viewport("a", 1, BODY, 20, 8, true)));
        assertEquals(new ScrollHint.Horizontal(BODY, -3), ViewportScrollPolicy.between(before,
            viewport("a", 1, BODY, 20, 2, true)));
        assertInstanceOf(ScrollHint.None.class, ViewportScrollPolicy.between(before,
            viewport("a", 1, BODY, 20, 8, false)));
    }

    @Test void surfaceGenerationGeometryAndOcclusionInvalidateReuse() {
        var before = viewport("a", 1, BODY, 20, 5, true);
        for (var after : new ViewportScrollPolicy.Viewport[] {
            null, viewport("b", 1, BODY, 23, 5, true), viewport("a", 2, BODY, 23, 5, true),
            viewport("a", 1, new Rect(2, 3, 39, 12), 23, 5, true),
            viewport("a", 1, new Rect(2, 4, 40, 12), 23, 5, true),
            viewport("a", 1, new Rect(2, 3, 0, 12), 23, 5, true)
        }) assertInstanceOf(ScrollHint.None.class, ViewportScrollPolicy.between(before, after));
        assertInstanceOf(ScrollHint.None.class, ViewportScrollPolicy.between(null, before));
    }

    @Test void stationaryDiagonalAndNonoverlappingMovesUseOrdinaryDiff() {
        var before = viewport("a", 1, BODY, 20, 5, true);
        for (var after : new ViewportScrollPolicy.Viewport[] {
            before, viewport("a", 1, BODY, 21, 6, true), viewport("a", 1, BODY, 32, 5, true),
            viewport("a", 1, BODY, 20, 45, true), viewport("a", 1, BODY, Integer.MAX_VALUE, 5, true)
        }) assertInstanceOf(ScrollHint.None.class, ViewportScrollPolicy.between(before, after));
    }

    @Test void trackerDropsOccludedAndInvalidatedHistoryWithoutRetainingAProducer() {
        var tracker = new ViewportScrollPolicy.Tracker();
        var first = viewport("a", 1, BODY, 2, 0, true);
        var next = viewport("a", 1, BODY, 3, 0, true);
        assertEquals(ScrollHint.none(), tracker.next(first));
        assertEquals(new ScrollHint.Vertical(BODY), tracker.next(next));
        assertEquals(ScrollHint.none(), tracker.next(null));
        assertEquals(ScrollHint.none(), tracker.next(first));
        tracker.invalidate();
        assertEquals(ScrollHint.none(), tracker.next(next));
        for (var field : ViewportScrollPolicy.Tracker.class.getDeclaredFields())
            assertEquals(ViewportScrollPolicy.Viewport.class, field.getType());
    }

    private static ViewportScrollPolicy.Viewport viewport(String id, long generation, Rect bounds,
        int top, int left, boolean horizontal) {
        return new ViewportScrollPolicy.Viewport(id, generation, bounds, top, left, horizontal);
    }
}
