package dev.tuink.editor.session.recovery;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.recovery.RecoveryDocument;
import dev.tuink.editor.session.document.EditorDocumentSession;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RecoveryCoordinatorTest {
    private enum Failure { BEFORE_WRITE, AFTER_WRITE, MEMORY_AFTER_WRITE }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void anUncertainFirstPublicationRemainsEligibleForExplicitDiscard(Failure failure) {
        Participant draft = participant("draft", "original");
        var queue = new ArrayDeque<Runnable>();
        var store = new Store();
        store.failure = failure;
        var recovery = coordinator(store, queue::addLast);
        recovery.synchronize(List.of(draft), RecoveryCoordinator.RemovalDisposition.DISCARD);
        assertNull(recovery.tick(0));
        queue.removeFirst().run();
        assertTrue(recovery.tick(1).contains("Recovery autosave failed"));
        assertEquals(failure == Failure.BEFORE_WRITE ? 0 : 1, store.snapshots.size());

        recovery.synchronize(List.of(), RecoveryCoordinator.RemovalDisposition.DISCARD);
        assertNull(recovery.tick(2));
        assertEquals(1, queue.size());
        assertFalse(store.events.contains("delete:entry-1"), "cleanup waits for its I/O operation");
        queue.removeFirst().run();
        assertNull(recovery.tick(3));

        assertTrue(store.snapshots.isEmpty());
        assertEquals(List.of("save:entry-1", "delete:entry-1"), store.events);
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void becomingCleanAlsoCleansUpAnUncertainFirstPublication(Failure failure) {
        Participant draft = participant("draft", "original");
        var store = new Store();
        store.failure = failure;
        var recovery = coordinator(store, Runnable::run);
        recovery.synchronize(List.of(draft), RecoveryCoordinator.RemovalDisposition.DISCARD);
        assertNotNull(recovery.tick(0));

        draft.session.execute(draft.session.defaultViewId(), new DocumentCommand.Edit(DocumentCommand.EditAction.UNDO));
        assertFalse(draft.session.status().dirty());
        assertNull(recovery.tick(1));

        assertTrue(store.snapshots.isEmpty());
        assertEquals(List.of("save:entry-1", "delete:entry-1"), store.events);
    }

    @Test
    void detachPreservesAnUncertainDraftAndReleasesItsClaim() {
        Participant draft = participant("draft", "original");
        var store = new Store();
        store.failure = Failure.AFTER_WRITE;
        var recovery = coordinator(store, Runnable::run);
        recovery.synchronize(List.of(draft), RecoveryCoordinator.RemovalDisposition.PRESERVE);
        assertNotNull(recovery.tick(0));

        recovery.synchronize(List.of(), RecoveryCoordinator.RemovalDisposition.PRESERVE);
        assertNull(recovery.tick(1));
        assertNull(recovery.finish(List.of(), false));

        assertEquals(List.of("draft original"), store.snapshots.get("entry-1").lines());
        assertEquals(List.of("save:entry-1", "release:entry-1"), store.events);
    }

    @Test
    void aPendingWriteCompletesBeforeADiscardCanDeleteItsEntry() {
        Participant draft = participant("draft", "original");
        var queue = new ArrayDeque<Runnable>();
        var store = new Store();
        var recovery = coordinator(store, queue::addLast);
        recovery.synchronize(List.of(draft), RecoveryCoordinator.RemovalDisposition.DISCARD);
        recovery.tick(0);
        recovery.synchronize(List.of(), RecoveryCoordinator.RemovalDisposition.DISCARD);
        recovery.tick(1);
        assertEquals(1, queue.size());
        assertTrue(store.events.isEmpty());

        queue.removeFirst().run();
        recovery.tick(2);
        assertEquals(List.of("save:entry-1"), store.events);
        queue.removeFirst().run();
        recovery.tick(3);

        assertEquals(List.of("save:entry-1", "delete:entry-1"), store.events);
        assertTrue(store.snapshots.isEmpty());
    }

    @Test
    void lifetimeCheckpointCapturesViewChangesWithoutWaitingForShutdown() {
        Participant draft = participant("draft", "original");
        var store = new Store();
        var recovery = coordinator(store, Runnable::run);
        recovery.synchronize(List.of(draft), RecoveryCoordinator.RemovalDisposition.PRESERVE);
        assertNull(recovery.tick(0));
        RecoveryDocument initial = store.snapshots.get("entry-1");
        long revision = draft.session.snapshot().revision();
        draft.session.execute(draft.session.defaultViewId(), new DocumentCommand.Navigate(DocumentCommand.Navigation.LEFT, true));
        assertEquals(revision, draft.session.snapshot().revision());
        assertNull(recovery.tick(3_000_000_000L));
        assertEquals(1, draft.captures);

        recovery.requestCheckpoint();
        assertNull(recovery.tick(3_000_000_001L));

        RecoveryDocument latest = store.snapshots.get("entry-1");
        assertEquals(initial.lines(), latest.lines());
        assertEquals(new TextPosition(0, 5), latest.caret());
        assertEquals(new TextPosition(0, 6), latest.anchor());
        assertEquals(2, draft.captures);
    }

    @Test
    void aFailedViewOnlyCheckpointIsRetriedEvenThoughTheTextRevisionWasAlreadySaved() {
        Participant draft = participant("draft", "original");
        var store = new Store();
        var recovery = coordinator(store, Runnable::run);
        recovery.synchronize(List.of(draft), RecoveryCoordinator.RemovalDisposition.PRESERVE);
        recovery.tick(0);
        draft.session.execute(draft.session.defaultViewId(), new DocumentCommand.Navigate(DocumentCommand.Navigation.LEFT, true));
        recovery.requestCheckpoint();
        store.failure = Failure.BEFORE_WRITE;
        assertNotNull(recovery.tick(3_000_000_000L));
        assertEquals(new TextPosition(0, 6), store.snapshots.get("entry-1").caret());
        store.failure = null;

        assertNull(recovery.tick(5_000_000_000L));

        assertEquals(new TextPosition(0, 5), store.snapshots.get("entry-1").caret());
        assertEquals(3, draft.captures);
    }

    @Test
    void aBlockedWriteRetainsOneImmutableVersionAndDoesNotStarveTheNextDocument() {
        Participant first = participant("first", "one");
        Participant second = participant("second", "two");
        var queue = new ArrayDeque<Runnable>();
        var store = new Store();
        var recovery = coordinator(store, queue::addLast);
        var participants = List.of(first, second);
        recovery.synchronize(participants, RecoveryCoordinator.RemovalDisposition.PRESERVE);
        recovery.tick(0);
        for (int edit = 0; edit < 100; edit++) {
            first.session.execute(first.session.defaultViewId(), new DocumentCommand.Input("x"));
            recovery.requestCheckpoint();
            recovery.tick(3_000_000_000L * (edit + 1));
            assertEquals(1, first.captures);
            assertEquals(0, second.captures);
            assertEquals(1, queue.size());
        }

        queue.removeFirst().run();
        assertEquals(List.of("draft one"), store.snapshots.get("entry-1").lines());
        recovery.tick(400_000_000_000L);
        assertEquals(1, second.captures);
        assertEquals(1, queue.size());
        queue.removeFirst().run();
        assertNull(recovery.finish(participants, false));

        assertEquals(List.of("draft " + "x".repeat(100) + "one"), store.snapshots.get("entry-1").lines());
        assertEquals(List.of("draft two"), store.snapshots.get("entry-2").lines());
    }

    @Test
    void abandonedStaleAndForeignOwnershipPreparationCannotDeleteStartupDrafts() {
        Participant draft = participant("draft", "original");
        var store = new Store();
        RecoveryDocument initial = draft.session.prepareRecovery(draft.session.defaultViewId()).materialize();
        store.snapshots.put("startup", initial);
        var recovery = coordinator(store, Runnable::run);
        var prepared = recovery.prepareRestoration(List.of(new RecoveryCoordinator.RestoredParticipant<>(draft, "startup")), 0);
        var foreign = coordinator(store, Runnable::run);
        assertThrows(IllegalStateException.class, () -> foreign.publishRestoration(prepared));
        recovery.requestCheckpoint();
        assertThrows(IllegalStateException.class, () -> recovery.publishRestoration(prepared));

        assertNull(recovery.finish(List.of(), true));
        assertSame(initial, store.snapshots.get("startup"));
        assertTrue(store.events.isEmpty());
    }

    @Test
    void publishedRestorationKeepsItsStableEntryUntilAnExplicitDiscard() {
        Participant draft = participant("draft", "original");
        var store = new Store();
        RecoveryDocument initial = draft.session.prepareRecovery(draft.session.defaultViewId()).materialize();
        store.snapshots.put("startup", initial);
        var recovery = coordinator(store, Runnable::run);
        var prepared = recovery.prepareRestoration(List.of(new RecoveryCoordinator.RestoredParticipant<>(draft, "startup")), 0);
        recovery.publishRestoration(prepared);
        assertThrows(IllegalStateException.class, () -> recovery.publishRestoration(prepared));
        recovery.synchronize(List.of(draft), RecoveryCoordinator.RemovalDisposition.PRESERVE);
        assertNull(recovery.tick(1));
        assertTrue(store.events.isEmpty());

        assertNull(recovery.finish(List.of(draft), false));
        assertEquals(List.of("save:startup"), store.events);
        assertNull(recovery.finish(List.of(draft), true));
        assertEquals(List.of("save:startup", "delete:startup"), store.events);
        assertTrue(store.snapshots.isEmpty());
    }

    @Test
    void omittedDocumentsArePreservedOnAnUnconfirmedShutdown() {
        Participant draft = participant("draft", "original");
        var store = new Store();
        var recovery = coordinator(store, Runnable::run);
        recovery.synchronize(List.of(draft), RecoveryCoordinator.RemovalDisposition.PRESERVE);
        recovery.tick(0);

        assertNull(recovery.finish(List.of(), false));

        assertEquals(List.of("save:entry-1", "release:entry-1"), store.events);
        assertEquals(1, store.snapshots.size());
    }

    @Test
    @Timeout(5)
    void executorRejectionLeavesNoUnscheduledOperationToBlockFinalOwnerCheckpoint() {
        Participant draft = participant("draft", "original");
        var store = new Store();
        var recovery = coordinator(store, task -> { throw new OutOfMemoryError("worker unavailable"); });
        recovery.synchronize(List.of(draft), RecoveryCoordinator.RemovalDisposition.PRESERVE);
        assertTrue(recovery.tick(0).contains("worker unavailable"));

        assertNull(recovery.finish(List.of(draft), false));

        assertEquals(List.of("draft original"), store.snapshots.get("entry-1").lines());
    }

    @Test
    void failureCooldownDoesNotSuppressTheFinalCheckpointWarning() {
        Participant draft = participant("draft", "original");
        var store = new Store();
        store.failure = Failure.BEFORE_WRITE;
        var recovery = coordinator(store, Runnable::run);
        recovery.synchronize(List.of(draft), RecoveryCoordinator.RemovalDisposition.PRESERVE);
        assertNotNull(recovery.tick(0));
        assertNull(recovery.tick(2_000_000_000L));

        assertTrue(recovery.finish(List.of(draft), false).contains("before write"));
        assertTrue(store.snapshots.isEmpty());
    }

    @Test
    void startupDeleteAndPostponeReturnFailuresForRetryWithoutMaterializingDrafts() {
        var store = new Store();
        store.failCleanup = "second";
        var recovery = coordinator(store, Runnable::run);

        var deletion = recovery.deleteEntries(List.of("first", "second"));
        assertEquals(1, deletion.completedCount());
        assertEquals("second", deletion.failures().get(0).entryId());
        var release = recovery.releaseEntries(List.of("first", "second"));
        assertEquals(1, release.completedCount());
        assertEquals("second", release.failures().get(0).entryId());
        store.failCleanup = null;
        assertTrue(recovery.releaseEntries(List.of("second")).successful());
    }

    @Test
    void duplicateDocumentOrEntryOwnershipIsRejectedBeforePublication() {
        Participant first = participant("first", "one");
        Participant second = participant("second", "two");
        var store = new Store();
        var recovery = coordinator(store, Runnable::run);
        assertThrows(IllegalArgumentException.class, () -> recovery.synchronize(List.of(first, first), RecoveryCoordinator.RemovalDisposition.DISCARD));
        assertThrows(IllegalArgumentException.class, () -> recovery.prepareRestoration(List.of(
            new RecoveryCoordinator.RestoredParticipant<>(first, "shared"),
            new RecoveryCoordinator.RestoredParticipant<>(second, "shared")), 0));
        assertNull(recovery.finish(List.of(), true));
        assertTrue(store.events.isEmpty());
    }

    @Test
    void registryMutationFromAnotherThreadIsRejectedBeforeReadingDocumentState() throws Exception {
        Participant draft = participant("draft", "original");
        var store = new Store();
        var recovery = coordinator(store, Runnable::run);
        recovery.synchronize(List.of(draft), RecoveryCoordinator.RemovalDisposition.PRESERVE);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread foreign = new Thread(() -> {
            try { recovery.tick(0); }
            catch (Throwable error) { failure.set(error); }
        });
        foreign.start();
        foreign.join();

        assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals(0, draft.captures);
        assertTrue(store.events.isEmpty());
    }

    @Test
    @Timeout(10)
    void workerPersistenceCannotReadLiveOwnerStateAndShutdownCapturesTheFinalEdit() throws Exception {
        Participant draft = participant("draft", "original");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workerThread = new AtomicReference<Thread>();
        var writes = new AtomicInteger();
        var store = new Store() {
            @Override public void save(String id, RecoveryDocument checkpoint) throws IOException {
                if (writes.incrementAndGet() == 1) {
                    workerThread.set(Thread.currentThread());
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test release timed out");
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interruption);
                    }
                }
                super.save(id, checkpoint);
            }
        };
        var executor = Executors.newSingleThreadExecutor();
        var recovery = coordinator(store, executor);
        try {
            recovery.synchronize(List.of(draft), RecoveryCoordinator.RemovalDisposition.PRESERVE);
            assertNull(recovery.tick(0));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), workerThread.get());
            draft.session.execute(draft.session.defaultViewId(), new DocumentCommand.Input("latest "));
            draft.session.execute(draft.session.defaultViewId(), new DocumentCommand.Navigate(DocumentCommand.Navigation.LEFT, true));
            recovery.requestCheckpoint();
            assertNull(recovery.tick(1));
            assertEquals(1, draft.captures);
            release.countDown();

            assertNull(recovery.finish(List.of(draft), false));

            assertEquals(List.of("draft latest original"), store.snapshots.get("entry-1").lines());
            assertEquals(draft.session.view(draft.session.defaultViewId()).cursor(), store.snapshots.get("entry-1").caret());
            assertEquals(2, draft.captures);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void aFailedDiscardRemainsQueuedAfterTheDocumentHasGoneAway() {
        Participant draft = participant("draft", "original");
        var store = new Store();
        var recovery = coordinator(store, Runnable::run);
        recovery.synchronize(List.of(draft), RecoveryCoordinator.RemovalDisposition.DISCARD);
        recovery.tick(0);
        store.failCleanup = "entry-1";
        recovery.synchronize(List.of(), RecoveryCoordinator.RemovalDisposition.DISCARD);
        assertTrue(recovery.tick(1).contains("delete failed"));
        assertNull(recovery.tick(2));
        assertEquals(1, store.snapshots.size());
        store.failCleanup = null;

        assertNull(recovery.tick(2_000_000_001L));

        assertTrue(store.snapshots.isEmpty());
        assertEquals(List.of("save:entry-1", "delete:entry-1", "delete:entry-1"), store.events);
    }

    @Test
    void aTaskQueuedBeforeExecutorRejectionCannotLaterResurrectADiscardedDraft() {
        Participant draft = participant("draft", "original");
        var store = new Store();
        var queue = new ArrayDeque<Runnable>();
        var recovery = coordinator(store, task -> {
            queue.addLast(task);
            throw new OutOfMemoryError("queued then rejected");
        });
        recovery.synchronize(List.of(draft), RecoveryCoordinator.RemovalDisposition.DISCARD);
        assertTrue(recovery.tick(0).contains("queued then rejected"));
        assertNull(recovery.finish(List.of(draft), true));
        assertEquals(List.of("delete:entry-1"), store.events);

        queue.removeFirst().run();

        assertTrue(store.snapshots.isEmpty());
        assertEquals(List.of("delete:entry-1"), store.events);
    }

    @Test
    @Timeout(10)
    void executorFailureAfterStartingTheWriterCannotPublishCompletionBeforeTheWriteEnds() throws Exception {
        Participant draft = participant("draft", "original");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        var store = new Store() {
            @Override public void save(String id, RecoveryDocument checkpoint) throws IOException {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test release timed out");
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interruption);
                }
                super.save(id, checkpoint);
            }
        };
        var recovery = coordinator(store, task -> {
            Thread thread = new Thread(task, "recovery-start-then-reject-test");
            worker.set(thread);
            thread.start();
            try {
                if (!entered.await(2, TimeUnit.SECONDS)) throw new AssertionError("worker did not start");
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interruption);
            }
            throw new OutOfMemoryError("started then rejected");
        });
        try {
            recovery.synchronize(List.of(draft), RecoveryCoordinator.RemovalDisposition.DISCARD);
            assertNull(recovery.tick(0), "the actual writer still owns the operation");
            recovery.synchronize(List.of(), RecoveryCoordinator.RemovalDisposition.DISCARD);
            assertNull(recovery.tick(1));
            assertTrue(store.events.isEmpty(), "delete cannot race the still-running save");
            assertEquals(1, draft.captures);
            release.countDown();

            assertNull(recovery.finish(List.of(), true));

            assertEquals(List.of("save:entry-1", "delete:entry-1"), store.events);
            assertTrue(store.snapshots.isEmpty());
        } finally {
            release.countDown();
            Thread thread = worker.get();
            if (thread != null) thread.join();
        }
    }

    @Test
    void entryFactoriesCannotAliasTwoDocumentsOrAnEntryAwaitingCleanup() {
        Participant first = participant("first", "one");
        Participant second = participant("second", "two");
        var store = new Store();
        var recovery = new RecoveryCoordinator<RecoveryDocument>(store, Runnable::run, () -> 1L, () -> "shared");
        assertThrows(IllegalArgumentException.class, () -> recovery.synchronize(List.of(first, second), RecoveryCoordinator.RemovalDisposition.DISCARD));
        assertTrue(store.events.isEmpty());
        recovery.synchronize(List.of(first), RecoveryCoordinator.RemovalDisposition.DISCARD);
        recovery.tick(0);
        recovery.synchronize(List.of(), RecoveryCoordinator.RemovalDisposition.DISCARD);
        assertThrows(IllegalArgumentException.class, () -> recovery.synchronize(List.of(second), RecoveryCoordinator.RemovalDisposition.DISCARD));
        assertNull(recovery.finish(List.of(), true));
        assertTrue(store.snapshots.isEmpty());
    }

    @Test
    void startupBatchValidationCannotDeleteAnActiveDraftOrPartiallyExecuteBadInput() {
        Participant draft = participant("draft", "original");
        var store = new Store();
        var recovery = coordinator(store, Runnable::run);
        recovery.synchronize(List.of(draft), RecoveryCoordinator.RemovalDisposition.DISCARD);
        recovery.tick(0);
        assertThrows(IllegalStateException.class, () -> recovery.deleteEntries(List.of("startup", "entry-1")));
        assertThrows(IllegalStateException.class, () -> recovery.releaseEntries(List.of("startup", "entry-1")));
        assertThrows(IllegalArgumentException.class, () -> recovery.deleteEntries(List.of("startup", "startup")));

        assertEquals(List.of("save:entry-1"), store.events);
        assertEquals(1, store.snapshots.size());
        assertNull(recovery.tick(3_000_000_000L));
        assertEquals(1, draft.captures);
    }

    @Test
    void aFinalPublicationMemoryFailureStillCapturesOtherDocumentsAndPreservesItsOriginalFailure() {
        Participant first = participant("first", "one");
        Participant second = participant("second", "two");
        OutOfMemoryError publicationFailure = new OutOfMemoryError("after final publication");
        var store = new Store() {
            @Override public void save(String id, RecoveryDocument checkpoint) throws IOException {
                super.save(id, checkpoint);
                if (id.equals("entry-1")) throw publicationFailure;
            }
        };
        var recovery = coordinator(store, Runnable::run);

        assertSame(publicationFailure, assertThrows(OutOfMemoryError.class, () -> recovery.finish(List.of(first, second), false)));
        assertEquals(List.of("draft one"), store.snapshots.get("entry-1").lines());
        assertEquals(List.of("draft two"), store.snapshots.get("entry-2").lines());

        assertNull(recovery.finish(List.of(first, second), true));
        assertTrue(store.snapshots.isEmpty());
    }

    private static Participant participant(String id, String text) {
        EditorDocumentSession session = EditorDocumentSession.create(new DocumentId(id), List.of(text));
        session.execute(session.defaultViewId(), new DocumentCommand.Input("draft "));
        return new Participant(session);
    }

    private static RecoveryCoordinator<RecoveryDocument> coordinator(Store store, java.util.concurrent.Executor executor) {
        AtomicInteger ids = new AtomicInteger();
        return new RecoveryCoordinator<>(store, executor, () -> 1L, () -> "entry-" + ids.incrementAndGet());
    }

    private static final class Participant implements RecoveryParticipant<RecoveryDocument> {
        final EditorDocumentSession session;
        final Thread owner = Thread.currentThread();
        int captures;
        Participant(EditorDocumentSession session) { this.session = session; }
        @Override public DocumentId documentId() { return session.documentId(); }
        @Override public Status status() {
            assertSame(owner, Thread.currentThread());
            var status = session.status();
            return new Status(status.dirty(), status.recoveryRevision());
        }
        @Override public PreparedCheckpoint<RecoveryDocument> prepareCheckpoint() {
            assertSame(owner, Thread.currentThread());
            captures++;
            var captured = session.prepareRecovery(session.defaultViewId());
            return captured::materialize;
        }
    }

    private static class Store implements RecoveryPersistence<RecoveryDocument> {
        final Map<String, RecoveryDocument> snapshots = new HashMap<>();
        final List<String> events = new ArrayList<>();
        Failure failure;
        String failCleanup;
        @Override public void save(String id, RecoveryDocument checkpoint) throws IOException {
            events.add("save:" + id);
            if (failure == Failure.BEFORE_WRITE) throw new IOException("before write");
            snapshots.put(id, checkpoint);
            if (failure == Failure.AFTER_WRITE) throw new IOException("after write");
            if (failure == Failure.MEMORY_AFTER_WRITE) throw new OutOfMemoryError("after write");
        }
        @Override public void delete(String id) throws IOException {
            events.add("delete:" + id);
            if (id.equals(failCleanup)) throw new IOException("delete failed");
            snapshots.remove(id);
        }
        @Override public void release(String id) throws IOException {
            events.add("release:" + id);
            if (id.equals(failCleanup)) throw new IOException("release failed");
        }
    }
}
