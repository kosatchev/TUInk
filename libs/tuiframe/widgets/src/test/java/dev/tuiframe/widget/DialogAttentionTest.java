package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DialogAttentionTest {
    @Test
    void noDialogOwnerMeansNoHighlightOrScheduledChange() {
        DialogAttention attention = new DialogAttention();

        assertFalse(attention.synchronize(null, 0));
        assertFalse(attention.request(null, millis(10)));
        assertFalse(attention.highlighted());
        assertEquals(-1L, attention.nanosUntilChange(millis(10)));
    }

    @Test
    void highlightExpiresAtItsDeadlineAndReportsEachVisibleTransitionOnce() {
        DialogAttention attention = new DialogAttention();

        assertFalse(attention.synchronize("save", millis(5)));
        assertTrue(attention.request("save", millis(10)));
        assertTrue(attention.highlighted());
        assertEquals(millis(180), attention.nanosUntilChange(millis(10)));
        assertFalse(attention.synchronize("save", millis(189)));
        assertEquals(millis(1), attention.nanosUntilChange(millis(189)));
        assertEquals(0L, attention.nanosUntilChange(millis(190)));
        assertTrue(attention.synchronize("save", millis(190)));
        assertFalse(attention.highlighted());
        assertEquals(-1L, attention.nanosUntilChange(millis(190)));
        assertFalse(attention.synchronize("save", millis(191)));
    }

    @Test
    void repeatedBlockedInputCannotExtendTheHighlightOrBypassTheCooldown() {
        DialogAttention attention = new DialogAttention();
        assertTrue(attention.request("save", 0));

        for (int time = 10; time < 180; time += 10) {
            assertFalse(attention.request("save", millis(time)));
            assertTrue(attention.highlighted());
            assertEquals(millis(180 - time), attention.nanosUntilChange(millis(time)));
        }
        assertTrue(attention.request("save", millis(180)), "the expired highlight turns off");
        assertFalse(attention.highlighted());
        for (int time = 190; time < 360; time += 10) {
            assertFalse(attention.request("save", millis(time)));
            assertFalse(attention.highlighted());
            assertEquals(-1L, attention.nanosUntilChange(millis(time)));
        }
        assertTrue(attention.request("save", millis(360)));
        assertEquals(millis(180), attention.nanosUntilChange(millis(360)));
    }

    @Test
    void requestingDuringCooldownCanExpireButCannotRestartAnActiveHighlight() {
        DialogAttention attention = new DialogAttention();
        assertTrue(attention.request("save", millis(20)));

        assertTrue(attention.request("save", millis(250)));
        assertFalse(attention.highlighted());
        assertFalse(attention.request("save", millis(379)));
        assertTrue(attention.request("save", millis(380)));
        assertTrue(attention.highlighted());
    }

    @Test
    void equalOwnersRetainTheExistingDeadlineAndCooldown() {
        DialogAttention attention = new DialogAttention();
        Object firstOwner = new String("save");
        Object equalOwner = new String("save");
        assertTrue(attention.request(firstOwner, 0));

        assertFalse(attention.synchronize(equalOwner, millis(100)));
        assertFalse(attention.request(equalOwner, millis(100)));
        assertEquals(millis(80), attention.nanosUntilChange(millis(100)));
        assertTrue(attention.synchronize(equalOwner, millis(180)));
        assertFalse(attention.request(equalOwner, millis(359)));
        assertTrue(attention.request(equalOwner, millis(360)));
    }

    @Test
    void changingOrRemovingTheOwnerClearsBothHighlightAndCooldown() {
        DialogAttention attention = new DialogAttention();
        assertTrue(attention.request("save", 0));

        assertTrue(attention.synchronize("quit", millis(10)));
        assertFalse(attention.highlighted());
        assertEquals(-1L, attention.nanosUntilChange(millis(10)));
        assertTrue(attention.request("quit", millis(10)));
        assertTrue(attention.synchronize(null, millis(20)));
        assertFalse(attention.request(null, millis(20)));
        assertTrue(attention.request("save", millis(30)));
        assertEquals(millis(180), attention.nanosUntilChange(millis(30)));
    }

    @Test
    void aNewOwnerCanFlashImmediatelyAfterTheOldOwnersHighlightExpired() {
        DialogAttention attention = new DialogAttention();
        assertTrue(attention.request("save", 0));
        assertTrue(attention.synchronize("save", millis(180)));

        assertFalse(attention.synchronize("quit", millis(200)));
        assertTrue(attention.request("quit", millis(200)));
        assertEquals(millis(180), attention.nanosUntilChange(millis(200)));
    }

    @Test
    void aRequestForANewOwnerRestartsItsOwnBoundedHighlight() {
        DialogAttention attention = new DialogAttention();
        assertTrue(attention.request("save", 0));

        assertTrue(attention.request("quit", millis(20)));
        assertTrue(attention.highlighted());
        assertEquals(millis(180), attention.nanosUntilChange(millis(20)));
        assertTrue(attention.synchronize("quit", millis(200)));
    }

    @Test
    void negativeNanoTimeValuesDoNotAffectExpiryOrCooldown() {
        DialogAttention attention = new DialogAttention();
        long startedAt = -millis(1_000);
        assertTrue(attention.request("save", startedAt));

        assertEquals(millis(1), attention.nanosUntilChange(startedAt + millis(179)));
        assertTrue(attention.synchronize("save", startedAt + millis(180)));
        assertFalse(attention.request("save", startedAt + millis(359)));
        assertTrue(attention.request("save", startedAt + millis(360)));
    }

    @Test
    void nanoTimeWrapRetainsTheDeadlineAndStartInterval() {
        DialogAttention attention = new DialogAttention();
        long startedAt = Long.MAX_VALUE - millis(100);
        assertTrue(attention.request("save", startedAt));

        assertFalse(attention.request("save", startedAt + millis(179)));
        assertEquals(millis(1), attention.nanosUntilChange(startedAt + millis(179)));
        assertTrue(attention.synchronize("save", startedAt + millis(180)));
        assertFalse(attention.request("save", startedAt + millis(359)));
        assertTrue(attention.request("save", startedAt + millis(360)));
        assertEquals(millis(180), attention.nanosUntilChange(startedAt + millis(360)));
    }

    private static long millis(long value) {
        return TimeUnit.MILLISECONDS.toNanos(value);
    }
}
