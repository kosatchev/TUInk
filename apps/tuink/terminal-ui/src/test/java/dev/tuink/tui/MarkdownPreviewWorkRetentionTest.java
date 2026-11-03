package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.markdown.commonmark.CommonmarkMarkdownParser;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MarkdownPreviewWorkRetentionTest {
    @Test void cancelledConsumerCannotUnchargeInputStillReadByAnInterruptIgnoringParser() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var ignoredInterrupt = new CountDownLatch(1); var quiesced = new CountDownLatch(1);
        var reference = new AtomicReference<MarkdownPreviewService>();
        var parser = new CommonmarkMarkdownParser();
        var limits = new MarkdownPreviewBudget.Limits(100_000, 2_000, 50_000, 50_000);
        try (var service = new MarkdownPreviewService((snapshot, admission, control) -> {
            if (snapshot.revision() == 1) {
                started.countDown();
                boolean released = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!released && System.nanoTime() < deadline) {
                    try { released = release.await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                    catch (InterruptedException ignored) { ignoredInterrupt.countDown(); }
                }
                assertTrue(released, "Test must release its intentionally uncooperative parser");
            }
            return parser.parse(snapshot, admission, control);
        }, () -> {
            var current = reference.get();
            if (current != null && current.retainedSourceBytes() == 0) quiesced.countDown();
        }, limits)) {
            reference.set(service);
            var running = service.submit(input(1), 30);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            long retained = service.retainedSourceBytes(); assertTrue(retained > 0);
            running.cancel(true);
            assertTrue(ignoredInterrupt.await(3, TimeUnit.SECONDS));
            assertEquals(retained, service.retainedSourceBytes(), "Consumer cancellation is not provider quiescence");
            assertThrows(MarkdownPreviewBudget.Refused.class, () -> service.submit(input(2), 30));
            release.countDown();
            assertTrue(quiesced.await(3, TimeUnit.SECONDS));
            assertEquals(0, service.retainedSourceBytes());
            assertNotNull(service.submit(input(2), 30).get(3, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    @Test void cancellingAQueuedJobImmediatelyReleasesOnlyItsNeverStartedInput() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var parser = new CommonmarkMarkdownParser();
        var limits = new MarkdownPreviewBudget.Limits(100_000, 2_500, 50_000, 50_000);
        try (var service = new MarkdownPreviewService((snapshot, admission, control) -> {
            calls.incrementAndGet();
            if (snapshot.revision() == 1) {
                started.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException cancelled) { throw new java.util.concurrent.CancellationException(); }
            }
            return parser.parse(snapshot, admission, control);
        }, () -> {}, limits)) {
            var running = service.submit(input(1), 30);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            long runningBytes = service.retainedSourceBytes();
            var queued = service.submit(input(2), 30);
            assertTrue(service.retainedSourceBytes() > runningBytes);
            assertThrows(MarkdownPreviewBudget.Refused.class, () -> service.submit(input(3), 30));
            queued.cancel(true);
            assertEquals(runningBytes, service.retainedSourceBytes());
            var replacement = service.submit(input(3), 30);
            release.countDown();
            running.get(3, TimeUnit.SECONDS); replacement.get(3, TimeUnit.SECONDS);
            assertEquals(2, calls.get(), "The cancelled queued input must never reach the parser");
        } finally { release.countDown(); }
    }

    private static DocumentSnapshot input(long revision) {
        return new DocumentSnapshot(new DocumentId("retained-" + revision), revision,
            List.of(String.valueOf((char) ('a' + revision)).repeat(200)));
    }
}
