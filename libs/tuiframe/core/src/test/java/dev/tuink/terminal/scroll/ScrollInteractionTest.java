package dev.tuink.terminal.scroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScrollInteractionTest {
    @Test
    void mapsContentScrollToThumbGeometryAndBack() {
        assertEquals(1, ScrollInteraction.thumbSize(10, 100));
        assertEquals(5, ScrollInteraction.thumbOffset(10, 100, 45));
        assertEquals(50, ScrollInteraction.scrollFromThumbStart(5, 10, 100));
        assertEquals(0, ScrollInteraction.scrollFromPointer(100, 100, 10, 100));
        assertEquals(90, ScrollInteraction.scrollFromPointer(109, 100, 10, 100));
    }

    @Test
    void hitTestingDistinguishesTrackThumbAndOutside() {
        assertEquals(ScrollInteraction.Hit.NONE, ScrollInteraction.hit(99, 100, 10, 100, 45));
        assertEquals(ScrollInteraction.Hit.TRACK, ScrollInteraction.hit(102, 100, 10, 100, 45));
        assertEquals(ScrollInteraction.Hit.THUMB, ScrollInteraction.hit(105, 100, 10, 100, 45));
        assertTrue(ScrollInteraction.hitTrack(109, 100, 10));
        assertFalse(ScrollInteraction.hitTrack(110, 100, 10));
    }

    @Test
    void trackClicksPageWithoutLeavingTheScrollableRange() {
        assertEquals(36, ScrollInteraction.pageScrollFromTrackClick(45, 102, 100, 10, 100));
        assertEquals(54, ScrollInteraction.pageScrollFromTrackClick(45, 108, 100, 10, 100));
        assertEquals(45, ScrollInteraction.pageScrollFromTrackClick(45, 105, 100, 10, 100));
        assertEquals(0, ScrollInteraction.pageScrollFromTrackClick(0, 0, 0, 10, 5));
    }

    @Test
    void nonScrollableContentUsesTheWholeTrack() {
        assertEquals(10, ScrollInteraction.thumbSize(10, 5));
        assertEquals(0, ScrollInteraction.thumbOffset(10, 5, 100));
        assertEquals(0, ScrollInteraction.scrollFromThumbStart(5, 10, 5));
    }
}
