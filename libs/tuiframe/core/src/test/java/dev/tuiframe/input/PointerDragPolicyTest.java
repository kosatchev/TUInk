package dev.tuiframe.input;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PointerDragPolicyTest {
    @Test void objectsArmAtHalfASecondWithNegativeAndWrappingMonotonicTimestamps() {
        for (long pressed : new long[] {0L, -2_000_000_000L, Long.MAX_VALUE - 500_000_000L}) {
            assertFalse(PointerDragPolicy.ready(pressed, pressed + 499_999_999L));
            assertEquals(1L, PointerDragPolicy.nanosUntilReady(pressed, pressed + 499_999_999L));
            assertTrue(PointerDragPolicy.ready(pressed, pressed + 500_000_000L));
            assertEquals(0L, PointerDragPolicy.nanosUntilReady(pressed, pressed + 500_000_000L));
            assertEquals(0L, PointerDragPolicy.nanosUntilReady(pressed, pressed + 600_000_000L));
        }
    }

    @Test void stationaryHoldArmsWithoutStartingADragAndDoesNotRequireATimerEvent() {
        var press = PointerDragPolicy.Gesture.begin(8, 4, 0L);
        assertSame(press, press.move(8, 4, 499_999_999L));
        assertEquals(1L, press.nanosUntilWakeup(499_999_999L));
        var armed = press.tick(500_000_000L);
        assertTrue(armed.armed()); assertFalse(armed.dragging());
        assertEquals(-1L, armed.nanosUntilWakeup(500_000_000L));
        for (var gesture : new PointerDragPolicy.Gesture[] {press, armed}) {
            var moved = gesture.move(8, 5, 500_000_000L);
            assertTrue(moved.dragging());
            assertTrue(moved.move(8, 4, 510_000_000L).dragging(), "Returning to the origin does not turn a drag into a click");
        }
    }

    @Test void oneCellOfEarlyMotionCancelsUntilANewPressEvenAfterReturningAndWaiting() {
        for (var moved : new int[][] {{8, 5}, {9, 4}}) {
            var cancelled = PointerDragPolicy.Gesture.begin(8, 4, 0L).move(moved[0], moved[1], 499_999_999L);
            assertTrue(cancelled.cancelled());
            assertEquals(-1L, cancelled.nanosUntilWakeup(1_000_000_000L));
            assertSame(cancelled, cancelled.move(8, 4, 1_000_000_000L));
            assertSame(cancelled, cancelled.tick(2_000_000_000L));
            assertSame(cancelled, cancelled.move(moved[0], moved[1], 3_000_000_000L));
            assertTrue(PointerDragPolicy.Gesture.begin(8, 4, 3_000_000_000L).move(moved[0], moved[1], 3_500_000_000L).dragging());
        }
    }
}
