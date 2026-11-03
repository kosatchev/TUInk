package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorState;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FileAccessAdvisoryTest {
    @Test
    void footerAndConfirmationCompositionUseOnlyCachedFileAccess() {
        Path path = noFilesystemAccess(Path.of("/document.txt"));
        QueueExecutor executor = new QueueExecutor();
        AtomicInteger probes = new AtomicInteger();
        try (FileAccessAdvisory advisory = new FileAccessAdvisory(executor, false, ignored -> {
            probes.incrementAndGet();
            return true;
        })) {
            TerminalEditorApp host = new TerminalEditorApp(advisory);
            var tab = host.newEditorTab();
            EditorState state = tab.state();
            state.setCurrentResource(JvmDocumentStorage.resource(path));
            state.setDirty(true);
            TextModel model = new TextModel(new SimpleLineBuffer());

            assertTrue(host.buildBottomBar(state, model, 120, false, 0, 1, "").contains("?? | ENC:"));
            // Confirmation labels also validate the displayed path's native URI
            // round trip, which deliberately rejects a proxy Path. Use an
            // ordinary absent path here: the injected RO observation must win
            // over the real filesystem's writable/nonexistent observation.
            state.setCurrentResource(JvmDocumentStorage.resource(Path.of("/document.txt")));
            host.buildQuitConfirmBar(List.of(tab), 120, 22);
            host.buildCloseTabConfirmBar(List.of(tab), 0, 120, 22);
            assertEquals(0, probes.get());
            assertEquals(0, executor.tasks.size(), "painting must not even submit a filesystem check");

            host.refreshFileAccess(state);
            assertEquals(0, probes.get());
            executor.runNext();
            host.refreshFileAccess(state);
            assertTrue(host.buildBottomBar(state, model, 120, false, 0, 1, "").contains("RO | ENC:"));
            assertTrue(host.buildQuitConfirmBar(List.of(tab), 120, 22).row().contains("[Save As]"));
            assertTrue(host.buildCloseTabConfirmBar(List.of(tab), 0, 120, 22).row().contains("[Save As]"));
            assertEquals(1, probes.get());
        }
    }

    @Test
    void refreshIsPeriodicAndRetainsOnlyOneOutstandingCheck() {
        QueueExecutor executor = new QueueExecutor();
        AtomicBoolean readOnly = new AtomicBoolean(true);
        Path path = Path.of("/document.txt");
        try (FileAccessAdvisory advisory = new FileAccessAdvisory(executor, false, ignored -> readOnly.get())) {
            advisory.refresh(path, 0L);
            for (int i = 0; i < 1000; i++) advisory.refresh(path, i);
            assertEquals(1, executor.tasks.size());
            assertEquals("??", advisory.indicator(path));
            executor.runNext();
            assertTrue(advisory.refresh(path, 1000L));
            assertEquals("RO", advisory.indicator(path));
            assertTrue(advisory.isReadOnly(path));
            assertEquals(1_000_000_000L, advisory.nanosUntilRefresh(1000L));
            advisory.refresh(path, 1_000_000_999L);
            assertEquals(0, executor.tasks.size());
            advisory.refresh(path, 1_000_001_000L);
            assertEquals(1, executor.tasks.size());
            readOnly.set(false);
            executor.runNext();
            assertTrue(advisory.refresh(path, 1_000_001_001L));
            assertEquals("RW", advisory.indicator(path));
        }
    }

    @Test
    void lateResultCannotReplaceTheCurrentPathsUnknownIndicator() {
        QueueExecutor executor = new QueueExecutor();
        Path oldPath = Path.of("/old.txt");
        Path newPath = Path.of("/new.txt");
        try (FileAccessAdvisory advisory = new FileAccessAdvisory(executor, false, oldPath::equals)) {
            advisory.refresh(oldPath, 0L);
            advisory.refresh(newPath, 1L);
            executor.runNext();
            advisory.refresh(newPath, 2L);
            assertEquals("??", advisory.indicator(newPath));
            assertFalse(advisory.isReadOnly(newPath));
            assertEquals(1, executor.tasks.size());
            executor.runNext();
            advisory.refresh(newPath, 3L);
            assertEquals("RW", advisory.indicator(newPath));
            assertEquals("??", advisory.indicator(oldPath));
        }
    }

    @Test
    void switchingAwayAndBackDoesNotAcceptTheEarlierVisitResult() {
        QueueExecutor executor = new QueueExecutor();
        Path path = Path.of("/document.txt");
        try (FileAccessAdvisory advisory = new FileAccessAdvisory(executor, false, ignored -> true)) {
            advisory.refresh(path, 0L);
            advisory.refresh(null, 1L);
            assertEquals("RW", advisory.indicator(null));
            advisory.refresh(path, 2L);
            executor.runNext();
            advisory.refresh(path, 3L);
            assertEquals("??", advisory.indicator(path));
            executor.runNext();
            advisory.refresh(path, 4L);
            assertEquals("RO", advisory.indicator(path));
        }
    }

    @Test
    void failedObservationIsUnknownAndCanBeRetried() {
        QueueExecutor executor = new QueueExecutor();
        AtomicInteger attempts = new AtomicInteger();
        Path path = Path.of("/document.txt");
        try (FileAccessAdvisory advisory = new FileAccessAdvisory(executor, false, ignored -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("Provider unavailable");
            return false;
        })) {
            advisory.refresh(path, 0L);
            executor.runNext();
            advisory.refresh(path, 1L);
            assertEquals("??", advisory.indicator(path));
            advisory.refresh(path, 1_000_000_001L);
            executor.runNext();
            advisory.refresh(path, 1_000_000_002L);
            assertEquals("RW", advisory.indicator(path));
        }
    }

    @Test
    void blockedProviderDoesNotBlockRefreshOrClose() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "file-access-test");
            thread.setDaemon(true);
            return thread;
        });
        FileAccessAdvisory advisory = new FileAccessAdvisory(worker, true, ignored -> {
            entered.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException cancellation) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return true;
        });
        try {
            advisory.refresh(Path.of("/stalled.txt"), 0L);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTimeout(Duration.ofSeconds(1), () -> {
                for (int i = 0; i < 1000; i++) advisory.refresh(Path.of("/other.txt"), i);
                advisory.close();
            });
            assertFalse(advisory.refresh(Path.of("/other.txt"), 2000L));
            assertEquals(-1L, advisory.nanosUntilRefresh(2000L));
        } finally {
            release.countDown();
            advisory.close();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Path noFilesystemAccess(Path delegate) {
        return (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[] {Path.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("getFileSystem")) {
                    throw new AssertionError("The UI must not consult the filesystem provider");
                }
                if (method.getName().equals("toAbsolutePath")) return proxy;
                if (method.getName().equals("equals")) return proxy == arguments[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                try {
                    return method.invoke(delegate, arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
    }

    private static final class QueueExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.addLast(task); }
        void runNext() { tasks.removeFirst().run(); }
    }
}
