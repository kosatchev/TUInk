package dev.tuink.tui;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.markdown.MarkdownBlock;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.MarkdownInline;
import dev.tuink.markdown.MarkdownParser;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownPreviewServiceTest {
    private static DocumentSnapshot snapshot(long revision) {
        return new DocumentSnapshot(new DocumentId("preview"), revision, List.of("visible text"));
    }
    private static MarkdownDocument model(DocumentSnapshot snapshot) {
        var range = new TextRange(new TextPosition(0, 0), new TextPosition(0, snapshot.line(0).length()));
        return new MarkdownDocument(snapshot.documentId(), snapshot.revision(),
            List.of(new MarkdownBlock.Paragraph(List.of(new MarkdownInline.Text(snapshot.line(0), range)), range)), List.of());
    }
    @Test void resizingReusesParsedModelWithoutReadingLiveDocumentState() throws Exception {
        var calls = new AtomicInteger();
        MarkdownParser parser = (snapshot, limits, cancellation) -> { calls.incrementAndGet(); return model(snapshot); };
        try (var service = new MarkdownPreviewService(parser, () -> {})) {
            assertEquals(30, service.submit(snapshot(1), 30).get(3, TimeUnit.SECONDS).columns());
            assertEquals(10, service.submit(snapshot(1), 10).get(3, TimeUnit.SECONDS).columns());
            assertEquals(1, calls.get());
            service.submit(snapshot(2), 10).get(3, TimeUnit.SECONDS);
            assertEquals(2, calls.get());
        }
    }
    @Test void overloadKeepsOneWorkerAndOnlyLatestQueuedRequest() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        MarkdownParser parser = (snapshot, limits, cancellation) -> {
            if (calls.incrementAndGet() == 1) {
                started.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException(); }
            }
            return model(snapshot);
        };
        try (var service = new MarkdownPreviewService(parser, () -> {})) {
            var running = service.submit(snapshot(1), 30);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            var displaced = service.submit(snapshot(2), 30);
            var latest = service.submit(snapshot(3), 30);
            assertTrue(displaced.isCancelled());
            release.countDown();
            running.get(3, TimeUnit.SECONDS);
            assertEquals(3, latest.get(3, TimeUnit.SECONDS).document().revision());
            assertEquals(2, calls.get());
        } finally { release.countDown(); }
    }

    @Test void cancellingOneConsumerDoesNotCancelSharedParseOrAnotherConsumer() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var quiesced = new CountDownLatch(1); var reference = new AtomicReference<MarkdownPreviewService>();
        try (var service = new MarkdownPreviewService((snapshot, limits, control) -> {
            calls.incrementAndGet(); started.countDown();
            try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException cancelled) { throw new AssertionError("Shared work was interrupted", cancelled); }
            return model(snapshot);
        }, () -> {
            var current = reference.get();
            if (current != null && current.retainedSourceBytes() == 0) quiesced.countDown();
        })) {
            reference.set(service);
            var source = snapshot(1);
            var first = service.submit(source, 30);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            long rawBytes = service.retainedSourceBytes();
            var second = service.submit(source, 30);
            assertEquals(rawBytes, service.retainedSourceBytes(), "Shared consumers own one Work input lease");
            first.cancel(true); assertFalse(second.isCancelled());
            assertEquals(rawBytes, service.retainedSourceBytes());
            release.countDown();
            assertEquals(1, second.get(3, TimeUnit.SECONDS).document().revision());
            assertEquals(1, calls.get());
            assertTrue(service.cachedModelCount() > 0);
            assertTrue(quiesced.await(3, TimeUnit.SECONDS));
            service.close();
            assertEquals(0, service.cachedModelCount()); assertEquals(0, service.retainedBytes());
        } finally { release.countDown(); }
    }
    @Test void anotherWidthKeepsAUsefulSharedParseAliveAfterTheFirstConsumerRetires() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        try (var service = new MarkdownPreviewService((snapshot, limits, control) -> {
            calls.incrementAndGet(); started.countDown();
            try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException cancelled) { throw new AssertionError("Another width still needs this parse", cancelled); }
            return model(snapshot);
        }, () -> {})) {
            var first = service.submit(snapshot(1), 30);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            var second = service.submit(snapshot(1), 10);
            first.cancel(true); release.countDown();
            assertEquals(10, second.get(3, TimeUnit.SECONDS).columns());
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }
}
