package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.geometry.Rect;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.markdown.commonmark.CommonmarkMarkdownParser;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownPreviewLifecycleTest {
    @TempDir Path directory;
    private static final Rect BOUNDS = new Rect(0, 1, 40, 10);

    @Test void pendingAndFailedReflowReleaseEverySelectionReferenceToTheRetiredLayout() throws Exception {
        var input = source(0, List.of("# Title", "", "alpha beta gamma"));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var parser = new CommonmarkMarkdownParser();
        var limits = new MarkdownPreviewBudget.Limits(1_000_000, 500_000, 500_000, 20_000);
        try (var service = new MarkdownPreviewService((snapshot, bounds, control) -> {
            if (snapshot.documentId().value().equals("worker-blocker")) {
                started.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException cancelled) { throw new java.util.concurrent.CancellationException(); }
                throw new IllegalStateException("synthetic blocker completion");
            }
            return parser.parse(snapshot, bounds, control);
        }, () -> {}, limits); var state = new MarkdownPreviewState()) {
            accept(state, input, 1, service);
            state.moveSelection(PreviewSelection.Move.DOWN, true);
            var selection = state.selection.snapshot();
            var oldLayout = state.result;
            assertSame(oldLayout, selectionOwner(state.selection, "visualResult"));
            var blocker = service.submit(new DocumentSnapshot(new DocumentId("worker-blocker"), 0, List.of("block")), 39);
            assertTrue(started.await(3, TimeUnit.SECONDS));

            state.refresh(input, 1, new Rect(0, 1, 22, 10), service);
            assertNotNull(state.pending);
            assertFalse(state.pending.isDone(), "The worker is blocked so the old layout is retired while reflow is queued");
            assertNull(state.result);
            assertNull(state.geometry);
            assertNull(selectionOwner(state.selection, "visualResult"));
            assertNull(selectionOwner(state.selection, "geometry"));
            assertEquals(selection, state.selection.snapshot(), "Semantic selection survives retirement through captured anchors");
            assertEquals(0, service.retainedLayoutBytes());

            try (var competing = service.renderMemoryBudget().tryReserve(limits.layoutBytes())) {
                assertNotNull(competing);
                release.countDown();
                assertThrows(java.util.concurrent.ExecutionException.class, () -> blocker.get(3, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.ExecutionException.class, () -> state.pending.get(3, TimeUnit.SECONDS));
                assertTrue(state.collect(input.text().documentId(), input.text().revision()));
                assertTrue(state.error.contains("memory budget"));
                assertNull(selectionOwner(state.selection, "visualResult"));
                assertNull(selectionOwner(state.selection, "geometry"));
                assertEquals(selection, state.selection.snapshot());
                assertSame(input, state.acceptedEnvelope());
            }
            assertEquals(0, service.retainedLayoutBytes());
        } finally { release.countDown(); }
    }

    @Test void hiddenPreviewClosesItsGeometryButAnActiveFrameKeepsItsActualCharge() throws Exception {
        var input = source(0, List.of("# Title", "", "界 text"));
        try (var service = new MarkdownPreviewService(() -> {}); var state = new MarkdownPreviewState()) {
            accept(state, input, 1, service);
            var geometry = state.geometry;
            assertNotNull(geometry);
            assertSame(geometry, state.placement(MarkdownPreviewState.Placement.Focus.CONTENT).geometry());
            long layoutAndGeometry = service.retainedLayoutBytes();
            var borrowed = geometry.acquire(0);
            assertEquals(layoutAndGeometry, service.retainedLayoutBytes(),
                "Selection already warmed this row; a frame shares its admitted index");
            state.suspend();
            assertNull(state.geometry);
            assertNull(state.placement(MarkdownPreviewState.Placement.Focus.CONTENT).geometry());
            assertThrows(IllegalStateException.class, geometry::result);
            assertTrue(service.retainedLayoutBytes() > 0);
            assertEquals("Title", borrowed.metrics().text());
            borrowed.close();
            assertEquals(0, service.retainedLayoutBytes());
        }
    }

    @Test void hiddenPreviewReleasesLayoutButKeepsTheExactAcceptedSourceAndAnchors() throws Exception {
        var input = source(0, List.of("# Title", "", "\\frac{a}{b}  ", "", "last", ""));
        try (var service = new MarkdownPreviewService(() -> {}); var state = new MarkdownPreviewState()) {
            accept(state, input, 1, service);
            state.moveSelection(PreviewSelection.Move.DOWN, false);
            var caret = state.placement(MarkdownPreviewState.Placement.Focus.CONTENT).caret();
            assertSame(input, state.acceptedEnvelope());
            assertTrue(service.retainedLayoutBytes() > 0);
            state.suspend();
            assertFalse(state.active); assertNull(state.result); assertNull(state.pending);
            assertEquals(0, service.retainedLayoutBytes());
            assertSame(input, state.sourceForFreeze());
            state.refresh(source(1, List.of("hidden update")), 1, BOUNDS, service);
            assertNull(state.pending, "A hidden consumer must never enqueue or displace visible work");
            accept(state, input, 1, service);
            assertEquals(caret, state.placement(MarkdownPreviewState.Placement.Focus.CONTENT).caret());
            assertEquals("\\frac{a}{b}  ", state.sourceForFreeze().text().line(2));
            assertEquals("", state.sourceForFreeze().text().line(5));
        }
    }

    @Test void closeBeforeFirstAcceptedResultUsesCreationInputEvenWhenANewerRequestExists() throws Exception {
        var creation = source(0, List.of("creation"));
        var latest = source(1, List.of("later", "different line count", ""));
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var parser = new CommonmarkMarkdownParser();
        try (var service = new MarkdownPreviewService((snapshot, limits, control) -> {
            started.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException cancelled) { throw new java.util.concurrent.CancellationException(); }
            return parser.parse(snapshot, limits, control);
        }, () -> {}); var state = new MarkdownPreviewState()) {
            assertTrue(state.initialize(creation, 1, service));
            assertTrue(state.activate(latest, 1, BOUNDS, service));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            state.suspend();
            assertSame(creation, state.sourceForFreeze());
            assertEquals(1, state.sourceForFreeze().metadata().lineSeparators().size() + 1);
        } finally { release.countDown(); }
    }

    @Test void acceptedTextAndFormatCannotBeReplacedByLateWorkAfterReceiptRebind() throws Exception {
        var initial = source(0, List.of("accepted  ", ""));
        var newer = source(1, List.of("newer", "three", "lines"));
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var parser = new CommonmarkMarkdownParser();
        try (var service = new MarkdownPreviewService((snapshot, limits, control) -> {
            if (snapshot.revision() == 1) {
                started.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException cancelled) { throw new java.util.concurrent.CancellationException(); }
            }
            return parser.parse(snapshot, limits, control);
        }, () -> {}); var state = new MarkdownPreviewState()) {
            accept(state, initial, 1, service);
            state.refresh(newer, 1, BOUNDS, service);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            var target = new ResourceBinding(JvmDocumentStorage.resource(directory.resolve("saved.md")), SaveDisposition.CREATE_NEW, null);
            state.updateBinding(target, DocumentOpenMode.UTF_8, 2);
            release.countDown();
            assertFalse(state.collect(initial.text().documentId(), 1));
            assertSame(initial.text(), state.sourceForFreeze().text());
            assertSame(initial.metadata(), state.sourceForFreeze().metadata());
            assertEquals(target, state.sourceForFreeze().binding());
            assertEquals(DocumentOpenMode.UTF_8, state.sourceForFreeze().reopenMode());
            assertNull(state.pending);
            state.updateBinding(ResourceBinding.unbound(), DocumentOpenMode.CP866, 1);
            assertEquals(target, state.sourceForFreeze().binding(), "A retired receipt generation cannot undo retargeting");
            assertEquals(DocumentOpenMode.UTF_8, state.sourceForFreeze().reopenMode());
            assertFalse(state.activate(newer, 1, BOUNDS, service));
            assertNull(state.pending);
        } finally { release.countDown(); }
    }

    @Test void formatOnlyRefreshReusesLayoutAndPublishesTheMatchingEnvelope() throws Exception {
        var first = source(0, List.of("one", "two", ""));
        var metadata = first.metadata().canonicalized(java.nio.charset.StandardCharsets.UTF_16LE, "\r\n", 3);
        var changed = new FrozenDocumentSource(first.text(), metadata, first.binding());
        try (var service = new MarkdownPreviewService(() -> {}); var state = new MarkdownPreviewState()) {
            accept(state, first, 1, service);
            var layout = state.result;
            state.refresh(changed, 1, BOUNDS, service);
            assertSame(layout, state.result); assertNull(state.pending);
            assertSame(changed, state.acceptedEnvelope());
            assertSame(metadata, state.sourceForFreeze().metadata());
        }
    }

    @Test void frozenSaveUtf8ReceiptUpdatesBomAndKeepsMixedSeparatorsAndRawSnapshot() throws Exception {
        var text = new DocumentSnapshot(new DocumentId("mixed"), 7,
            List.of("# Unicode", "", "raw \\frac{a}{b}  ", ""));
        var originalFormat = new DocumentFileMetadata(java.nio.charset.StandardCharsets.UTF_16LE,
            dev.tuink.editor.storage.BomKind.UTF_16_LE, "\r\n", List.of("\r\n", "\n", "\r"));
        var original = new FrozenDocumentSource(text, originalFormat, ResourceBinding.unbound());
        var committedFormat = new DocumentFileMetadata(java.nio.charset.StandardCharsets.UTF_8,
            dev.tuink.editor.storage.BomKind.NONE, "\r\n", originalFormat.lineSeparators());
        var target = new ResourceBinding(JvmDocumentStorage.resource(directory.resolve("utf8.md")), SaveDisposition.CREATE_NEW, null);
        var saved = new FrozenDocumentSource(text, committedFormat, target);
        try (var service = new MarkdownPreviewService(() -> {}); var state = new MarkdownPreviewState()) {
            accept(state, original, 1, service); state.suspend();
            assertTrue(state.updateSavedSource(saved, 2));
            assertSame(saved, state.sourceForFreeze());
            assertSame(text, state.sourceForFreeze().text());
            assertEquals("raw \\frac{a}{b}  ", state.sourceForFreeze().text().line(2));
            assertEquals(List.of("\r\n", "\n", "\r"), state.sourceForFreeze().metadata().lineSeparators());
            assertEquals(dev.tuink.editor.storage.BomKind.NONE, state.sourceForFreeze().metadata().bomKind());
            assertEquals(dev.tuink.editor.storage.BomKind.UTF_16_LE, original.metadata().bomKind());
            assertNull(state.pending); assertNull(state.result);
        }
    }

    @Test void layoutBudgetFailureKeepsTheCreationSnapshotAndDoesNotResubmitEveryFrame() throws Exception {
        var input = source(0, List.of("still available"));
        var limits = new MarkdownPreviewBudget.Limits(100_000, 50_000, 50_000, 100);
        try (var service = new MarkdownPreviewService(new CommonmarkMarkdownParser(), () -> {}, limits);
             var state = new MarkdownPreviewState()) {
            assertTrue(state.activate(input, 1, BOUNDS, service));
            try { state.pending.get(3, TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException expected) { }
            assertTrue(state.collect(input.text().documentId(), 0));
            assertTrue(state.error.contains("memory budget"));
            assertSame(input, state.sourceForFreeze());
            state.refresh(input, 1, BOUNDS, service);
            assertNull(state.pending);
            assertEquals(0, service.retainedLayoutBytes());
        }
    }

    private static FrozenDocumentSource source(long revision, List<String> lines) {
        return new FrozenDocumentSource(new DocumentSnapshot(new DocumentId("source"), revision, lines),
            DocumentFileMetadata.canonical(java.nio.charset.StandardCharsets.UTF_8, dev.tuink.editor.storage.BomKind.NONE,
                "\r\n", lines.size()), ResourceBinding.unbound());
    }
    private static Object selectionOwner(PreviewSelection selection, String name) throws ReflectiveOperationException {
        var field = PreviewSelection.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(selection);
    }
    private static void accept(MarkdownPreviewState state, FrozenDocumentSource source, long generation,
                               MarkdownPreviewService service) throws Exception {
        assertTrue(state.activate(source, generation, BOUNDS, service));
        assertNotNull(state.pending); state.pending.get(3, TimeUnit.SECONDS);
        assertTrue(state.collect(source.text().documentId(), source.text().revision()));
        assertTrue(state.ready());
    }
}
