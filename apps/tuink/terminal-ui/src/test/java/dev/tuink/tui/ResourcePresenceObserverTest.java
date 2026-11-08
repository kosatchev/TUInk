package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.core.CoreDocumentBackend;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.TabSession;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourcePresenceObserverTest {
    @TempDir Path directory;

    @Test
    void filesystemPresenceIsTypedAndNeitherPermissionNorOtherErrorsMeanDeletion() throws Exception {
        Path present = Files.writeString(directory.resolve("present.txt"), "contents");
        var queue = new QueueExecutor();
        try (var observer = new ResourcePresenceObserver(queue, false,
                path -> Files.readAttributes(path, BasicFileAttributes.class), () -> false)) {
            observer.schedule(subject(present));
            assertTrue(observer.poll().isEmpty(), "Scheduling and polling do not perform filesystem work");
            queue.runNext();
            assertEquals(ResourcePresenceObserver.Presence.PRESENT, only(observer).presence());
            observer.schedule(subject(directory.resolve("missing.txt")));
            queue.runNext();
            assertEquals(ResourcePresenceObserver.Presence.MISSING, only(observer).presence());
        }
        for (var failure : List.of(new AccessDeniedException("denied"), new IOException("missing is only a message"))) {
            try (var observer = new ResourcePresenceObserver(queue, false, path -> { throw failure; }, () -> false)) {
                observer.schedule(subject(present));
                queue.runNext();
                assertEquals(failure instanceof AccessDeniedException ? ResourcePresenceObserver.Presence.DENIED
                    : ResourcePresenceObserver.Presence.UNKNOWN, only(observer).presence());
            }
        }
        try (var observer = new ResourcePresenceObserver(queue, false,
                path -> { throw new SecurityException("denied"); }, () -> false)) {
            observer.schedule(subject(present));
            queue.runNext();
            assertEquals(ResourcePresenceObserver.Presence.DENIED, only(observer).presence());
        }
    }

    @Test
    void atMostOneStatAndTheLatestAdditionalRequestAreRetained() {
        var queue = new QueueExecutor();
        var calls = new ArrayList<Path>();
        var first = subject(directory.resolve("first.txt"));
        var intermediate = subject(directory.resolve("intermediate.txt"));
        var latest = subject(directory.resolve("latest.txt"));
        try (var observer = new ResourcePresenceObserver(queue, false, calls::add, () -> false)) {
            observer.schedule(first);
            for (int index = 0; index < 1000; index++) observer.schedule(intermediate);
            observer.schedule(latest);
            assertEquals(1, queue.tasks.size());
            assertEquals(List.of(), calls);
            queue.runNext();
            assertSame(first, only(observer).subject());
            assertEquals(1, queue.tasks.size(), "The owner submits only the newest deferred request");
            queue.runNext();
            assertSame(latest, only(observer).subject());
            assertEquals(List.of(directory.resolve("first.txt"), directory.resolve("latest.txt")), calls);
            assertFalse(observer.busy());
        }
    }

    @Test
    void aLateMissingObservationCannotDetachAReplacementOrAnAwayAndBackBinding() {
        var queue = new QueueExecutor();
        var document = subject(directory.resolve("original.txt"));
        try (var observer = missingObserver(queue)) {
            observer.schedule(document);
            queue.runNext();
            assertTrue(document.relocateResource(document.binding(), binding(directory.resolve("new.txt"))));
            assertTrue(observer.poll().isEmpty());

            var before = document.binding();
            observer.schedule(document);
            queue.runNext();
            assertTrue(document.relocateResource(before, ResourceBinding.unbound()));
            assertTrue(document.relocateResource(ResourceBinding.unbound(), before));
            assertTrue(observer.poll().isEmpty(), "Equal paths after relocation still belong to another owner generation");
        }
    }

    @Test
    void activeDocumentAndWindowOperationsPreventAcceptingMissingObservations() throws Exception {
        var queue = new QueueExecutor();
        var document = subject(directory.resolve("original.txt"));
        try (var observer = missingObserver(queue)) {
            observer.schedule(document);
            queue.runNext();
            var save = document.io().beginSaveAs(JvmDocumentStorage.resource(directory.resolve("save-as.txt")),
                SaveDisposition.CREATE_NEW, false);
            assertTrue(observer.poll().isEmpty());
            document.io().cancel(save);
            observer.schedule(document);
            queue.runNext();
            assertEquals(ResourcePresenceObserver.Presence.MISSING, only(observer).presence());
        }

        var busy = new AtomicBoolean();
        try (var observer = new ResourcePresenceObserver(queue, false,
                path -> { throw new NoSuchFileException(path.toString()); }, busy::get)) {
            observer.schedule(document);
            queue.runNext();
            busy.set(true);
            assertTrue(observer.poll().isEmpty());
            assertTrue(observer.busy(), "A completed observation can wait without touching the busy owner");
            busy.set(false);
            assertEquals(ResourcePresenceObserver.Presence.MISSING, only(observer).presence());
        }
    }

    @Test
    void anIntentionalNewTargetAndAStoppedDocumentAreNotObservedAsDeleted() {
        var queue = new QueueExecutor();
        var document = subject(directory.resolve("new.txt"));
        assertTrue(document.relocateResource(document.binding(), new ResourceBinding(
            JvmDocumentStorage.resource(directory.resolve("new.txt")), SaveDisposition.CREATE_NEW, null)));
        try (var observer = missingObserver(queue)) {
            observer.schedule(document);
            assertFalse(observer.busy());
            assertTrue(queue.tasks.isEmpty());
            document.io().stop();
            observer.schedule(document);
            assertFalse(observer.busy());
        }
    }

    @Test
    void aStalledStatDoesNotBlockSchedulingOrClose() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var worker = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "resource-presence-test");
            thread.setDaemon(true);
            return thread;
        });
        var observer = new ResourcePresenceObserver(worker, true, path -> {
            calls.incrementAndGet();
            entered.countDown();
            boolean interrupted = false;
            while (true) {
                try { release.await(); break; }
                catch (InterruptedException cancelled) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }, () -> false);
        try {
            var first = subject(directory.resolve("first.txt"));
            var latest = subject(directory.resolve("latest.txt"));
            observer.schedule(first);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTimeout(Duration.ofSeconds(1), () -> {
                for (int index = 0; index < 1000; index++) observer.schedule(latest);
                assertTrue(observer.poll().isEmpty());
                observer.close();
            });
            assertEquals(1, calls.get());
            assertFalse(observer.busy());
            assertTrue(observer.poll().isEmpty());
        } finally { release.countDown(); observer.close(); }
    }

    private static ResourcePresenceObserver missingObserver(QueueExecutor queue) {
        return new ResourcePresenceObserver(queue, false,
            path -> { throw new NoSuchFileException(path.toString()); }, () -> false);
    }

    private static ResourcePresenceObserver.Observation only(ResourcePresenceObserver observer) {
        var observations = observer.poll();
        assertEquals(1, observations.size());
        return observations.getFirst();
    }

    private static ResourceBinding binding(Path path) {
        return new ResourceBinding(JvmDocumentStorage.resource(path), SaveDisposition.REPLACE_EXISTING, null);
    }

    private static EditorDocumentSession subject(Path path) {
        var view = TabSession.createEmpty(LogicalEditorTextLayout::new, new EditorClipboard());
        var session = EditorDocumentSession.connect(CoreDocumentBackend.adopt(new DocumentId(path.toString()), view));
        assertTrue(session.relocateResource(ResourceBinding.unbound(), binding(path)));
        return session;
    }

    private static final class QueueExecutor implements java.util.concurrent.Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.addLast(task); }
        void runNext() { tasks.removeFirst().run(); }
    }
}
