package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Rect;
import org.junit.jupiter.api.Test;

class SplitPaneTest {
    @Test
    void partitionsTranslatedBoundsWithoutOverlapAndClampsBothMinimums() {
        Rect bounds = new Rect(7, 3, 80, 20);
        SplitPane split = SplitPane.sideBySide(bounds, 30, 16, 24);
        assertEquals(new Rect(7, 3, 30, 20), split.firstBounds());
        assertEquals(new Rect(37, 3, 1, 20), split.dividerBounds());
        assertEquals(new Rect(38, 3, 49, 20), split.secondBounds());
        assertFalse(split.firstBounds().intersects(split.dividerBounds()));
        assertFalse(split.secondBounds().intersects(split.dividerBounds()));
        assertEquals(16, SplitPane.sideBySide(bounds, 0, 16, 24).firstExtent());
        assertEquals(55, SplitPane.sideBySide(bounds, Integer.MAX_VALUE, 16, 24).firstExtent());
        assertEquals(16, split.clampFirstExtent(Long.MIN_VALUE));
        assertEquals(55, split.clampFirstExtent(Long.MAX_VALUE));
    }

    @Test
    void insufficientSpacePreservesTheSecondRegionAndRestoringSpaceRestoresPreferredExtent() {
        Rect narrow = new Rect(7, 3, 40, 20);
        SplitPane split = SplitPane.sideBySide(narrow, 30, 16, 24);
        assertFalse(split.divided());
        assertTrue(split.firstBounds().isEmpty());
        assertTrue(split.dividerBounds().isEmpty());
        assertEquals(narrow, split.secondBounds());
        assertEquals(0, split.firstExtent());
        assertEquals(0, split.clampFirstExtent(30));

        SplitPane exact = SplitPane.sideBySide(new Rect(7, 3, 41, 20), 30, 16, 24);
        assertTrue(exact.divided());
        assertEquals(16, exact.firstExtent());
        assertEquals(24, exact.secondBounds().width());
        assertEquals(30, SplitPane.sideBySide(new Rect(7, 3, 80, 20), 30, 16, 24).firstExtent());
        assertFalse(SplitPane.sideBySide(new Rect(0, 0, 80, 0), 30, 16, 24).divided());
    }

    @Test
    void verticalSplitAndExtremeCoordinatesUseRepresentableGeometry() {
        SplitPane vertical = SplitPane.resolve(new Rect(4, 9, 12, 15), SplitPane.Axis.VERTICAL, 6, 3, 4);
        assertEquals(new Rect(4, 9, 12, 6), vertical.firstBounds());
        assertEquals(new Rect(4, 15, 12, 1), vertical.dividerBounds());
        assertEquals(new Rect(4, 16, 12, 8), vertical.secondBounds());

        Rect extreme = new Rect(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 1);
        SplitPane split = SplitPane.sideBySide(extreme, Integer.MAX_VALUE, 1, 1);
        assertEquals(extreme.rightExclusive(), split.secondBounds().rightExclusive());
        assertEquals(1, split.secondBounds().width());
        assertFalse(SplitPane.sideBySide(extreme, 1, Integer.MAX_VALUE, Integer.MAX_VALUE).divided());
        assertThrows(IllegalArgumentException.class, () -> SplitPane.sideBySide(extreme, -1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> SplitPane.sideBySide(extreme, 1, 0, 1));
    }
}
