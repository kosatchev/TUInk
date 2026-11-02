package dev.tuiframe.memory;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RenderMemoryBudgetTest {
    @Test void refusedAdmissionDoesNotChargeAndArithmeticCannotOverflow() {
        var budget = RenderMemoryBudget.bounded(Long.MAX_VALUE);
        var almostAll = budget.tryReserve(Long.MAX_VALUE - 4);
        assertNotNull(almostAll);
        assertNull(budget.tryReserve(5));
        assertEquals(Long.MAX_VALUE - 4, budget.usedBytes());
        try (var remainder = budget.tryReserve(4)) {
            assertNotNull(remainder);
            assertEquals(Long.MAX_VALUE, budget.usedBytes());
            assertNull(budget.tryReserve(1));
        }
        almostAll.close();
        assertEquals(0, budget.usedBytes());
    }

    @Test void chargeBelongsToTheLastRealOwnerAndCloseIsIdempotent() {
        var budget = RenderMemoryBudget.bounded(100);
        var first = budget.tryReserve(60);
        var second = first.retain();
        assertEquals(60, first.bytes());
        assertEquals(60, budget.usedBytes());
        first.close();
        first.close();
        assertEquals(60, budget.usedBytes());
        assertThrows(IllegalStateException.class, first::retain);
        second.close();
        assertEquals(0, budget.usedBytes());
    }

    @Test void closingAdmissionDoesNotPretendOutstandingOwnersReleasedMemory() {
        var budget = RenderMemoryBudget.bounded(100);
        var first = budget.tryReserve(60);
        budget.close();
        assertNull(budget.tryReserve(1));
        assertEquals(60, budget.usedBytes());
        var borrowed = first.retain();
        first.close();
        assertEquals(60, budget.usedBytes());
        borrowed.close();
        assertEquals(0, budget.usedBytes());
    }

    @Test void invalidLimitsAndNegativeAllocationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RenderMemoryBudget.bounded(0));
        var budget = RenderMemoryBudget.bounded(1);
        assertThrows(IllegalArgumentException.class, () -> budget.tryReserve(-1));
        try (var zero = budget.tryReserve(0)) {
            assertNotNull(zero);
            assertEquals(0, budget.usedBytes());
        }
    }
}
