package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.DocumentSave;
import dev.tuink.editor.storage.DocumentStorage;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.ResourceId;
import dev.tuink.editor.storage.SaveAuthorization;
import dev.tuink.editor.storage.SaveTarget;
import dev.tuink.editor.storage.SavedDocument;
import dev.tuink.tui.flow.RecoveryStore;
import dev.tuink.tui.flow.RecoveryStoreFixtures;
import dev.tuink.verification.MemoryDocumentStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class DocumentIoShutdownRecoveryTest {
    @Test
    @Timeout(10)
    void shutdownTimeoutRetainsRecoveryAndDoesNotAcceptALateExternalSave() throws Exception {
        var memory = new MemoryDocumentStorage();
        memory.put("source", "original".getBytes(StandardCharsets.UTF_8));
        var storage = new BlockingStorage(memory);
        var document = TuiDocumentSession.create(new EditorClipboard()).openPrepared(
            storage.load(storage.resource("source"), DocumentOpenMode.DETECT, false));
        document.execute(new DocumentCommand.Input("draft "));
        var beforeSave = document.document().status();
        var work = document.document().io().beginSave();
        var persistence = new MemoryRecovery();
        var recovery = new TerminalEditorApp.RecoveryAutosaveManager(persistence, Runnable::run);
        var callbacks = new AtomicInteger();
        var outcome = new AtomicReference<DocumentIoCoordinator.Completion>();
        var finished = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "shutdown-recovery-save-test");
            thread.setDaemon(true);
            return thread;
        });
        var io = new DocumentIoController(worker, true);
        try {
            io.submitSave(() -> {
                try {
                    var completion = work.perform();
                    outcome.set(completion);
                    return completion;
                } finally {
                    finished.countDown();
                }
            }, completion -> {
                callbacks.incrementAndGet();
                document.document().io().accept(work, completion.value());
            });
            assertTrue(storage.started.await(5, TimeUnit.SECONDS));
            assertTrue(work.publishing());
            assertEquals(DocumentIoCoordinator.CancelResult.DRAINING_WRITE, document.document().io().stop());
            List<String> warnings = new ArrayList<>();

            var failure = assertThrows(IllegalStateException.class,
                () -> TerminalEditorApp.finishRecoveryAfterIo(() -> io.close(0L), recovery,
                    () -> List.of(document), true, warnings::add));

            assertTrue(failure.getMessage().contains("outcome is unknown"));
            assertEquals(List.of(), warnings);
            assertEquals(beforeSave, document.document().status(), "timeout must not advance the savepoint");
            assertEquals(1, persistence.snapshots.size());
            RecoverySnapshot checkpoint = persistence.snapshots.values().iterator().next();
            assertTrue(checkpoint.dirty());
            assertEquals(List.of("draft original"), checkpoint.lines());
            assertEquals(beforeSave.binding().resource(), checkpoint.currentResource());
            assertEquals(beforeSave.binding().version(), checkpoint.fileBaseline());
            assertEquals(beforeSave.metadata(), checkpoint.fileMetadata());
            assertEquals(0, persistence.deletes, "confirmed quit must not discard an unconfirmed save's draft");
            assertEquals("original", new String(memory.bytes("source"), StandardCharsets.UTF_8));

            storage.release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertTrue(outcome.get().succeeded(), "the opaque provider may finish after shutdown abandoned its reply");
            assertEquals("draft original", new String(memory.bytes("source"), StandardCharsets.UTF_8));
            assertFalse(io.collect());
            assertEquals(0, callbacks.get());
            assertEquals(beforeSave, document.document().status());
            assertEquals(checkpoint, persistence.snapshots.values().iterator().next());

            var restored = TuiDocumentSession.create(new EditorClipboard());
            restored.document().restoreRecovery(restored.viewId(), checkpoint);
            assertEquals(List.of("draft original"), restored.view().model().snapshotLines());
            assertTrue(restored.document().status().dirty());
            assertEquals(beforeSave.binding().version(), restored.document().status().binding().version());
        } finally {
            storage.release.countDown();
            io.close();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void finalDiskCheckpointTemporarilyClearsAndRestoresTheInterruptFlag(@TempDir Path directory) throws Exception {
        var document = TuiDocumentSession.create(new EditorClipboard());
        document.execute(new DocumentCommand.Input("interrupted draft"));
        List<String> warnings = new ArrayList<>();
        try (RecoveryStore store = RecoveryStoreFixtures.open(directory)) {
            var persistence = new TerminalEditorApp.RecoveryPersistence() {
                @Override public void save(String id, RecoverySnapshot snapshot) throws IOException { store.save(id, snapshot); }
                @Override public void delete(String id) throws IOException { store.delete(id); }
                @Override public void release(String id) throws IOException { store.release(id); }
            };
            var recovery = new TerminalEditorApp.RecoveryAutosaveManager(persistence, Runnable::run);
            Thread.currentThread().interrupt();
            try {
                TerminalEditorApp.finishRecoveryAfterIo(() -> {}, recovery,
                    () -> List.of(document), false, warnings::add);
                assertTrue(Thread.currentThread().isInterrupted(), "shutdown must preserve the caller's interrupt");
            } finally {
                Thread.interrupted();
            }
            assertEquals(List.of(), warnings);
        }
        try (RecoveryStore reopened = RecoveryStoreFixtures.open(directory)) {
            var recovered = reopened.loadAll();
            assertEquals(List.of(), recovered.skipped());
            assertEquals(1, recovered.entries().size());
            RecoverySnapshot checkpoint = recovered.entries().getFirst().snapshot();
            assertEquals(List.of("interrupted draft"), checkpoint.lines());
            assertTrue(checkpoint.dirty());
            assertNull(checkpoint.fileBaseline());
        }
    }

    private static final class MemoryRecovery implements TerminalEditorApp.RecoveryPersistence {
        private final Map<String, RecoverySnapshot> snapshots = new LinkedHashMap<>();
        private int deletes;
        @Override public void save(String id, RecoverySnapshot snapshot) { snapshots.put(id, snapshot); }
        @Override public void delete(String id) { deletes++; snapshots.remove(id); }
        @Override public void release(String id) { }
    }

    /** The default operation overload fences this opaque provider before its blocked write. */
    private static final class BlockingStorage implements DocumentStorage {
        private final MemoryDocumentStorage delegate;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private BlockingStorage(MemoryDocumentStorage delegate) { this.delegate = delegate; }
        private DocumentResource resource(String name) { return new Handle(delegate.resource(name), this); }
        @Override public DocumentLoad load(DocumentResource resource, DocumentOpenMode mode, boolean missing) throws IOException {
            var loaded = delegate.load(((Handle) resource).delegate(), mode, missing);
            return new DocumentLoad(new Handle(loaded.resource(), this), loaded.lines(), loaded.metadata(),
                loaded.baseline(), loaded.disposition());
        }
        @Override public SaveTarget inspect(DocumentResource resource, ResourceBinding binding) throws IOException {
            throw new IOException("Current-save test does not inspect another target");
        }
        @Override public boolean isCurrentTarget(DocumentResource resource, ResourceBinding binding) { return false; }
        @Override public SavedDocument write(DocumentSave request, SaveAuthorization authorization) throws IOException {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test write was not released");
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IOException("Publication was interrupted", interruption);
            }
            var unwrapped = new DocumentSave(((Handle) request.resource()).delegate(), request.lines(),
                request.metadata(), request.baseline(), request.disposition());
            var saved = delegate.write(unwrapped, authorization);
            return new SavedDocument(new Handle(saved.resource(), this), saved.metadata(), saved.baseline(), saved.publicationKind());
        }
        private record Handle(DocumentResource delegate, BlockingStorage storage) implements DocumentResource {
            @Override public ResourceId id() { return delegate.id(); }
            @Override public String displayName() { return delegate.displayName(); }
            @Override public String nameHint() { return delegate.nameHint(); }
        }
    }
}
