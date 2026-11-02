package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.storage.*;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FrozenDocumentSessionTest {
    @Test
    void ordinarySavePreservesRawMarkdownMixedEolEncodingBomAndTheFinalSeparator() throws Exception {
        var storage = new MemoryDocumentStorage();
        byte[] bytes = ("\uFEFF$\\int_0^1 x^2\\,dx$  \r\n```mermaid\r\tA-->B\n```\r\n").getBytes(StandardCharsets.UTF_16LE);
        var source = loaded(storage, "source.md", bytes);
        var subject = FrozenDocumentSession.create(source);
        int calls = storage.calls();
        var write = subject.io().beginSave();
        assertEquals(calls, storage.calls(), "owner capture never calls storage");
        assertSame(source.text().immutableLines(), subject.source().text().immutableLines());
        var completion = worker(write::perform);
        assertSame(source, subject.source(), "worker publication does not publish owner file state");
        assertArrayEquals(bytes, storage.bytes("source.md"));
        calls = storage.calls();
        assertInstanceOf(DocumentIoCoordinator.Saved.class, subject.io().accept(write, completion));
        assertEquals(calls, storage.calls(), "owner receipt publication performs no storage operation");
        assertSame(source.text(), subject.source().text());
        assertEquals(source.metadata(), subject.metadata());
        assertEquals("", subject.source().text().line(subject.source().text().lineCount() - 1));
        assertEquals(1, subject.generation());
        assertInstanceOf(DocumentIoCoordinator.Ignored.class, subject.io().accept(write, completion));
    }

    @Test
    void explicitUtf8SaveAsUpdatesOnlyAfterItsReceiptAndNeedsNoEditingSession() throws Exception {
        var storage = new MemoryDocumentStorage();
        var source = loaded(storage, "source.md", "\uFEFFформула $x^{y^z}$  \r\n".getBytes(StandardCharsets.UTF_16BE));
        var subject = FrozenDocumentSession.create(source);
        int calls = storage.calls();
        var inspect = subject.io().beginSaveAs(storage.resource("copy.md"), SaveDisposition.CREATE_NEW, true);
        assertEquals(calls, storage.calls());
        var confirmation = assertInstanceOf(DocumentIoCoordinator.Approval.class,
            subject.io().accept(inspect, worker(inspect::perform))).confirmation();
        assertFalse(confirmation.requiresOverwriteConfirmation());
        var write = assertInstanceOf(DocumentIoCoordinator.Ready.class, subject.io().approve(confirmation)).work();
        assertSame(source, subject.source());
        assertNull(storage.bytes("copy.md"));
        var completion = worker(write::perform);
        assertSame(source, subject.source());
        assertInstanceOf(DocumentIoCoordinator.Saved.class, subject.io().accept(write, completion));
        assertSame(source.text(), subject.source().text());
        assertEquals(storage.resource("copy.md"), subject.binding().resource());
        assertEquals(StandardCharsets.UTF_8, subject.metadata().charset());
        assertEquals(BomKind.NONE, subject.metadata().bomKind());
        assertArrayEquals("формула $x^{y^z}$  \r\n".getBytes(StandardCharsets.UTF_8), storage.bytes("copy.md"));
        assertArrayEquals("\uFEFFформула $x^{y^z}$  \r\n".getBytes(StandardCharsets.UTF_16BE), storage.bytes("source.md"));
        var next = subject.io().beginSave();
        assertInstanceOf(DocumentIoCoordinator.Saved.class, subject.io().accept(next, worker(next::perform)));
        assertEquals(2, subject.generation());
    }

    @Test
    void anUnrepresentableFrozenCharacterRetainsItsFormatUntilExplicitUtf8SaveAsSucceeds() throws Exception {
        var storage = new MemoryDocumentStorage();
        var observed = loaded(storage, "legacy.txt", "legacy".getBytes(StandardCharsets.UTF_8));
        var metadata = DocumentFileMetadata.canonical(java.nio.charset.Charset.forName("windows-1251"), BomKind.NONE, "\n", 1);
        var source = new FrozenDocumentSource(new DocumentSnapshot(observed.text().documentId(), 5, List.of("😀Привет")),
            metadata, observed.binding());
        var subject = FrozenDocumentSession.create(source);
        var failedWrite = subject.io().beginSave();
        var failed = assertInstanceOf(DocumentIoCoordinator.Failed.class,
            subject.io().accept(failedWrite, worker(failedWrite::perform)));
        assertInstanceOf(DocumentEncodingException.class, failed.failure());
        assertSame(source, subject.source());
        assertArrayEquals("legacy".getBytes(StandardCharsets.UTF_8), storage.bytes("legacy.txt"));
        var inspect = subject.io().beginSaveAs(storage.resource("utf8.txt"), SaveDisposition.CREATE_NEW, true);
        var approval = assertInstanceOf(DocumentIoCoordinator.Approval.class,
            subject.io().accept(inspect, worker(inspect::perform))).confirmation();
        var write = assertInstanceOf(DocumentIoCoordinator.Ready.class, subject.io().approve(approval)).work();
        assertInstanceOf(DocumentIoCoordinator.Saved.class, subject.io().accept(write, worker(write::perform)));
        assertSame(source.text(), subject.source().text());
        assertEquals(StandardCharsets.UTF_8, subject.metadata().charset());
        assertArrayEquals("😀Привет".getBytes(StandardCharsets.UTF_8), storage.bytes("utf8.txt"));
    }

    @Test
    void anUnboundSnapshotCanOnlyBeWrittenAfterAnExplicitSaveAsTarget() throws Exception {
        var storage = new MemoryDocumentStorage();
        var text = new DocumentSnapshot(new DocumentId("unnamed"), 4, List.of("```tex", "\\sqrt{x}", "```", ""));
        var source = new FrozenDocumentSource(text, DocumentFileMetadata.newDocument(4), ResourceBinding.unbound());
        var subject = FrozenDocumentSession.create(source);
        assertThrows(java.io.IOException.class, () -> subject.io().beginSave());
        assertSame(source, subject.source());
        var inspect = subject.io().beginSaveAs(storage.resource("named.md"), SaveDisposition.CREATE_NEW, false);
        var approval = assertInstanceOf(DocumentIoCoordinator.Approval.class,
            subject.io().accept(inspect, worker(inspect::perform))).confirmation();
        var write = assertInstanceOf(DocumentIoCoordinator.Ready.class, subject.io().approve(approval)).work();
        assertInstanceOf(DocumentIoCoordinator.Saved.class, subject.io().accept(write, worker(write::perform)));
        assertEquals(storage.resource("named.md"), subject.binding().resource());
        assertArrayEquals("```tex\n\\sqrt{x}\n```\n".getBytes(StandardCharsets.UTF_8), storage.bytes("named.md"));
    }

    @ParameterizedTest
    @EnumSource(value = MemoryDocumentStorage.WriteFailure.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void definiteAndUnknownWriteFailuresRetainTheSnapshotAndItsOriginalBinding(MemoryDocumentStorage.WriteFailure failure)
        throws Exception {
        var storage = new MemoryDocumentStorage();
        var observed = loaded(storage, "source.md", "original".getBytes(StandardCharsets.UTF_8));
        var source = new FrozenDocumentSource(new DocumentSnapshot(observed.text().documentId(), 9, List.of("frozen draft")),
            observed.metadata(), observed.binding());
        var subject = FrozenDocumentSession.create(source);
        storage.failNextWrite(failure);
        var work = subject.io().beginSave();
        var failed = assertInstanceOf(DocumentIoCoordinator.Failed.class, subject.io().accept(work, worker(work::perform)));
        assertEquals(failure == MemoryDocumentStorage.WriteFailure.AFTER_COMMIT ? DocumentIoCoordinator.FailureKind.UNKNOWN
            : DocumentIoCoordinator.FailureKind.FAILED_BEFORE_COMMIT, failed.kind());
        assertSame(source, subject.source());
        assertEquals(0, subject.generation());
        assertFalse(subject.io().busy());
        assertArrayEquals((failure == MemoryDocumentStorage.WriteFailure.AFTER_COMMIT ? "frozen draft" : "original")
            .getBytes(StandardCharsets.UTF_8), storage.bytes("source.md"));
    }

    @Test
    void anExternalVersionConflictKeepsTheClosedSourceAndDoesNotOverwriteTheNewFile() throws Exception {
        var storage = new MemoryDocumentStorage();
        var source = loaded(storage, "source.md", "old".getBytes(StandardCharsets.UTF_8));
        var subject = FrozenDocumentSession.create(source);
        storage.put("source.md", "external change".getBytes(StandardCharsets.UTF_8));
        var work = subject.io().beginSave();
        assertInstanceOf(DocumentIoCoordinator.Failed.class, subject.io().accept(work, worker(work::perform)));
        assertSame(source, subject.source());
        assertArrayEquals("external change".getBytes(StandardCharsets.UTF_8), storage.bytes("source.md"));
    }

    @Test
    void inspectedOverwriteUsesTheExactObservationAndDetectsAnInterveningChange() throws Exception {
        var storage = new MemoryDocumentStorage();
        var source = loaded(storage, "source.md", "source".getBytes(StandardCharsets.UTF_8));
        var subject = FrozenDocumentSession.create(source);
        storage.put("target.md", "existing".getBytes(StandardCharsets.UTF_8));
        var inspect = subject.io().beginSaveAs(storage.resource("target.md"), SaveDisposition.REPLACE_EXISTING, false);
        var confirmation = assertInstanceOf(DocumentIoCoordinator.Approval.class,
            subject.io().accept(inspect, worker(inspect::perform))).confirmation();
        assertTrue(confirmation.requiresOverwriteConfirmation());
        var write = assertInstanceOf(DocumentIoCoordinator.Ready.class, subject.io().approve(confirmation)).work();
        storage.put("target.md", "changed after approval".getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(DocumentIoCoordinator.Failed.class, subject.io().accept(write, worker(write::perform)));
        assertSame(source, subject.source());
        assertArrayEquals("changed after approval".getBytes(StandardCharsets.UTF_8), storage.bytes("target.md"));
    }

    @Test
    void cancellationForeignReceiptsAndWrongThreadCallsCannotPublishIntoANewerOperation() throws Exception {
        var storage = new MemoryDocumentStorage();
        var source = loaded(storage, "source.md", "source".getBytes(StandardCharsets.UTF_8));
        var subject = FrozenDocumentSession.create(source);
        var other = FrozenDocumentSession.create(source);
        var old = subject.io().beginSaveAs(storage.resource("old.md"), SaveDisposition.CREATE_NEW, false);
        var completion = worker(old::perform);
        assertEquals(DocumentIoCoordinator.CancelResult.CANCELLED, subject.io().cancel(old));
        var current = subject.io().beginSave();
        assertInstanceOf(DocumentIoCoordinator.Ignored.class, subject.io().accept(old, completion));
        assertThrows(IllegalArgumentException.class, () -> other.io().accept(old, completion));
        assertThrows(IllegalStateException.class, () -> worker(() -> subject.io().cancel(current)));
        assertThrows(IllegalStateException.class, () -> worker(subject::source));
        assertTrue(subject.io().busy());
        assertNull(storage.bytes("old.md"));
        assertInstanceOf(DocumentIoCoordinator.Saved.class, subject.io().accept(current, worker(current::perform)));
    }

    @Test
    void retiringTheFrozenOwnerCancelsPreparationButDrainsAnAlreadyPublishedWrite() throws Exception {
        var storage = new MemoryDocumentStorage();
        var source = loaded(storage, "source.md", "source".getBytes(StandardCharsets.UTF_8));
        var abandoned = FrozenDocumentSession.create(source);
        var notStarted = abandoned.io().beginSave();
        int calls = storage.calls();
        abandoned.close();
        assertTrue(worker(notStarted::perform).cancelled());
        assertEquals(calls, storage.calls());
        assertThrows(IllegalStateException.class, () -> abandoned.io().beginSave());
        var draining = FrozenDocumentSession.create(source);
        var work = draining.io().beginSave();
        var completion = worker(work::perform);
        draining.close();
        assertTrue(draining.io().busy());
        assertInstanceOf(DocumentIoCoordinator.Saved.class, draining.io().accept(work, completion));
        assertEquals(DocumentIoCoordinator.State.STOPPED, draining.io().state());
        assertSame(source.text(), draining.source().text());
    }

    private static FrozenDocumentSource loaded(MemoryDocumentStorage storage, String name, byte[] bytes) throws Exception {
        storage.put(name, bytes);
        var loaded = worker(() -> storage.load(storage.resource(name), DocumentOpenMode.DETECT, false));
        return new FrozenDocumentSource(new DocumentSnapshot(new DocumentId("frozen:" + name), 3, loaded.lines()),
            loaded.metadata(), new ResourceBinding(loaded.resource(), loaded.disposition(), loaded.baseline()));
    }

    private static <T> T worker(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation);
        Thread thread = new Thread(task);
        thread.start();
        thread.join(5000);
        assertFalse(thread.isAlive());
        try { return task.get(); }
        catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw (Error) failure.getCause();
        }
    }
}
