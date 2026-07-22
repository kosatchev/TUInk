package dev.tuink.tui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.tui.geometry.Point;
import dev.tuink.tui.geometry.Rect;
import dev.tuink.tui.geometry.Size;
import dev.tuink.tui.render.CellBuffer;
import org.junit.jupiter.api.Test;

class ScreenModelTest {
    @Test
    void frameExposesCellDimensionsAsWidthAndHeight() {
        CellBuffer cells = new CellBuffer(3, 8);
        Frame frame = new Frame(cells, ScreenCursor.visibleAt(new Point(7, 2)), ScrollHint.none());

        assertSame(cells, frame.cells());
        assertEquals(new Size(8, 3), frame.size());
        assertEquals(new Rect(0, 0, 8, 3), frame.bounds());
        assertTrue(frame.cursor().visible());
    }

    @Test
    void frameAllowsHiddenCursorOutsideItsBounds() {
        Frame frame = new Frame(
            new CellBuffer(2, 4),
            new ScreenCursor(new Point(-1, -1), false),
            ScrollHint.none()
        );

        assertFalse(frame.cursor().visible());
    }

    @Test
    void frameRequiresPositiveDimensionsAndVisibleCursorInsideBounds() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new Frame(new CellBuffer(0, 4), ScreenCursor.hidden(), ScrollHint.none())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new Frame(new CellBuffer(2, 0), ScreenCursor.hidden(), ScrollHint.none())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new Frame(
                new CellBuffer(2, 4),
                ScreenCursor.visibleAt(new Point(4, 1)),
                ScrollHint.none()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new Frame(
                new CellBuffer(2, 4),
                ScreenCursor.visibleAt(new Point(3, 2)),
                ScrollHint.none()
            )
        );
    }

    @Test
    void frameAcceptsVerticalAndHorizontalHintsInsideBounds() {
        CellBuffer cells = new CellBuffer(6, 12);
        Rect region = new Rect(2, 1, 8, 4);

        Frame vertical = new Frame(cells, ScreenCursor.hidden(), new ScrollHint.Vertical(region));
        Frame horizontal = new Frame(cells, ScreenCursor.hidden(), new ScrollHint.Horizontal(region, -3));

        assertEquals(region, ((ScrollHint.Vertical) vertical.scrollHint()).region());
        assertEquals(-3, ((ScrollHint.Horizontal) horizontal.scrollHint()).deltaColumns());
    }

    @Test
    void frameRejectsHintRegionOutsideBounds() {
        CellBuffer cells = new CellBuffer(6, 12);

        assertThrows(
            IllegalArgumentException.class,
            () -> new Frame(cells, ScreenCursor.hidden(), new ScrollHint.Vertical(new Rect(-1, 0, 4, 4)))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new Frame(cells, ScreenCursor.hidden(), new ScrollHint.Horizontal(new Rect(10, 0, 3, 2), 1))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new Frame(cells, ScreenCursor.hidden(), new ScrollHint.Vertical(new Rect(0, 5, 2, 2)))
        );
    }

    @Test
    void scrollHintsRejectEmptyRegionsAndZeroHorizontalDelta() {
        Rect empty = new Rect(0, 0, 0, 2);
        Rect region = new Rect(0, 0, 2, 2);

        assertThrows(IllegalArgumentException.class, () -> new ScrollHint.Vertical(empty));
        assertThrows(IllegalArgumentException.class, () -> new ScrollHint.Horizontal(empty, 1));
        assertThrows(IllegalArgumentException.class, () -> new ScrollHint.Horizontal(region, 0));
    }

    @Test
    void modelRequiresAllReferenceComponents() {
        CellBuffer cells = new CellBuffer(2, 2);

        assertThrows(NullPointerException.class, () -> new ScreenCursor(null, true));
        assertThrows(NullPointerException.class, () -> new Frame(null, ScreenCursor.hidden(), ScrollHint.none()));
        assertThrows(NullPointerException.class, () -> new Frame(cells, null, ScrollHint.none()));
        assertThrows(NullPointerException.class, () -> new Frame(cells, ScreenCursor.hidden(), null));
        assertThrows(NullPointerException.class, () -> new ScrollHint.Vertical(null));
        assertSame(ScrollHint.none(), ScrollHint.none());
    }
}
