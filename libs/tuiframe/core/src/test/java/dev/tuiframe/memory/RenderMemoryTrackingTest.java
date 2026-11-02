package dev.tuiframe.memory;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RenderMemoryTrackingTest {
    @Test void trackingSharesAdmissionAndCountsEachAllocationUntilItsFinalOwnerCloses() {
        var admission = RenderMemoryBudget.bounded(100);
        var first = RenderMemoryBudget.tracked(admission);
        var second = RenderMemoryBudget.tracked(admission);
        var cache = first.tryReserve(60);
        var frame = cache.retain();
        assertEquals(60, first.usedBytes());
        assertEquals(60, admission.usedBytes());
        assertNull(second.tryReserve(41));
        assertEquals(0, second.usedBytes());
        var other = second.tryReserve(40);
        assertEquals(100, admission.usedBytes());
        admission.close();
        cache.close();
        assertEquals(60, first.usedBytes());
        var completedFrame = frame.retain();
        frame.close();
        assertEquals(60, first.usedBytes());
        assertEquals(40, second.usedBytes());
        completedFrame.close(); other.close();
        assertEquals(0, first.usedBytes());
        assertEquals(0, second.usedBytes());
        assertEquals(0, admission.usedBytes());
        assertNull(first.tryReserve(1));
    }

    @Test void trackingCanNestWithoutCreatingAdditionalAdmissionOrKeepingClosedOwnersAlive() {
        var admission = RenderMemoryBudget.bounded(100);
        var outer = RenderMemoryBudget.tracked(admission);
        var inner = RenderMemoryBudget.tracked(outer);
        var owner = inner.tryReserve(100);
        assertEquals(100, admission.usedBytes());
        assertEquals(100, outer.usedBytes());
        assertEquals(100, inner.usedBytes());
        var retained = owner.retain();
        owner.close(); owner.close();
        assertEquals(100, inner.usedBytes());
        assertThrows(IllegalStateException.class, owner::retain);
        retained.close();
        assertEquals(0, inner.usedBytes());
        assertEquals(0, outer.usedBytes());
        assertEquals(0, admission.usedBytes());
        for (var field : retained.getClass().getDeclaredFields()) assertNotEquals(RenderMemoryBudget.Tracked.class, field.getType());
    }

    @Test void failedDelegatedRetainLeavesTheOriginalChargeAndOwnershipIntact() {
        var failure = new OutOfMemoryError("synthetic lease failure");
        int[] closes = {0};
        var tracked = RenderMemoryBudget.tracked(bytes -> new RenderMemoryBudget.Reservation() {
            @Override public long bytes() { return bytes; }
            @Override public RenderMemoryBudget.Reservation retain() { throw failure; }
            @Override public void close() { closes[0]++; }
        });
        var owner = tracked.tryReserve(100);
        assertSame(failure, assertThrows(OutOfMemoryError.class, owner::retain));
        assertEquals(100, tracked.usedBytes());
        assertEquals(0, closes[0]);
        owner.close(); owner.close();
        assertEquals(0, tracked.usedBytes());
        assertEquals(1, closes[0]);
    }
}
