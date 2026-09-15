package dev.tuiframe.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeometryTest {
    @Test
    void pointTranslationUsesZeroBasedCartesianCoordinates() {
        Point point = new Point(4, 7);

        assertEquals(new Point(1, 12), point.translate(-3, 5));
        assertSame(point, point.translate(0, 0));
    }

    @Test
    void sizeRejectsNegativeDimensionsAndReportsArea() {
        assertEquals(24L, new Size(6, 4).area());
        assertFalse(new Size(6, 4).isEmpty());
        assertTrue(new Size(0, 4).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Size(-1, 4));
        assertThrows(IllegalArgumentException.class, () -> new Size(4, -1));
    }

    @Test
    void rectangleUsesExclusiveRightAndBottomEdges() {
        Rect rect = new Rect(2, 3, 4, 5);

        assertEquals(6, rect.rightExclusive());
        assertEquals(8, rect.bottomExclusive());
        assertEquals(new Size(4, 5), rect.size());
        assertTrue(rect.contains(new Point(2, 3)));
        assertTrue(rect.contains(new Point(5, 7)));
        assertFalse(rect.contains(new Point(6, 7)));
        assertFalse(rect.contains(new Point(5, 8)));
    }

    @Test
    void rectangleIntersectionClipsOverlapsAndRepresentsDisjointAreasAsEmpty() {
        Rect rect = new Rect(1, 2, 6, 5);

        assertTrue(rect.intersects(new Rect(4, 0, 5, 5)));
        assertEquals(new Rect(4, 2, 3, 3), rect.intersection(new Rect(4, 0, 5, 5)));
        assertFalse(rect.intersects(new Rect(7, 2, 3, 3)));
        assertEquals(new Rect(7, 2, 0, 3), rect.intersection(new Rect(7, 2, 3, 3)));
    }

    @Test
    void rectangleContainmentIncludesNestedAndBoundaryEmptyRectangles() {
        Rect outer = new Rect(-2, -1, 8, 6);

        assertTrue(outer.contains(new Rect(0, 0, 2, 2)));
        assertTrue(outer.contains(new Rect(6, 5, 0, 0)));
        assertFalse(outer.contains(new Rect(-3, 0, 2, 2)));
    }

    @Test
    void rectanglesCanBeBuiltFromEdgesAndTranslated() {
        Rect rect = Rect.fromEdges(2, 3, 7, 11);

        assertEquals(new Rect(2, 3, 5, 8), rect);
        assertEquals(new Rect(0, 7, 5, 8), rect.translate(-2, 4));
        assertSame(rect, rect.translate(0, 0));
    }

    @Test
    void rectangleRejectsNegativeOrUnrepresentableGeometry() {
        assertThrows(IllegalArgumentException.class, () -> new Rect(0, 0, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Rect(0, 0, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new Rect(Integer.MAX_VALUE, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> Rect.fromEdges(2, 0, 1, 1));
        assertThrows(
            IllegalArgumentException.class,
            () -> Rect.fromEdges(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 1)
        );
    }
}
