package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.TabSession;
import dev.tuink.core.SaveDisposition;
import dev.tuink.render.TerminalEditorTextLayout;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class RecoveryAsyncAutosaveTest {
    @Test
    void formatOnlyUndoRefreshesRecoveryEvenWhenTheTextRevisionMatches(@TempDir Path directory) throws Exception {
        Path source = Files.write(directory.resolve("legacy.txt"), "Привет".getBytes(Charset.forName("windows-1251")));
        var tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        tab.engine().openExisting(source);
        tab.engine().insertInput("😀");
        long textRevision = tab.model().revision();
        var store = new MemoryStore();
        var manager = new TerminalEditorApp.RecoveryAutosaveManager(store, Runnable::run);
        assertNull(manager.autosaveDirtyTabs(List.of(tab), 0));
        assertEquals(source, store.snapshots.values().iterator().next().currentFile());
        Path converted = directory.resolve("converted.txt");
        tab.engine().saveAsUtf8(converted, SaveDisposition.CREATE_NEW);
        assertTrue(tab.engine().undo());
        assertEquals(textRevision, tab.model().revision());
        assertTrue(tab.state().isDirty());

        assertNull(manager.autosaveDirtyTabs(List.of(tab), 3_000_000_000L));

        assertEquals(2, store.events.stream().filter("save"::equals).count());
        RecoverySnapshot snapshot = store.snapshots.values().iterator().next();
        assertEquals(converted, snapshot.currentFile());
        assertEquals(tab.model().fileBaseline(), snapshot.fileBaseline());
        assertEquals(tab.model().fileMetadata(), snapshot.fileMetadata());
        assertEquals(List.of("😀Привет"), snapshot.lines());
        assertNull(manager.finishSession(List.of(tab), true));
    }

    @Test
    void failureToStartTheWriterCannotLeaveShutdownWaitingForAnUnscheduledOperation() throws Exception {
        var tab = session("latest draft");
        var store = new MemoryStore();
        var manager = new TerminalEditorApp.RecoveryAutosaveManager(store, task -> {
            throw new OutOfMemoryError("unable to create native thread");
        });

        String warning = manager.autosaveDirtyTabs(List.of(tab), 0);

        assertNotNull(warning);
        assertTrue(warning.contains("unable to create native thread"));
        // A regression must fail within a deadline even though shutdown waits
        // uninterruptibly for a real outstanding operation to finish.
        var finalFlush = new FutureTask<>(() -> manager.finishSession(List.of(tab), false));
        Thread finisher = new Thread(finalFlush, "recovery-failed-start-test");
        finisher.setDaemon(true);
        finisher.start();
        assertNull(finalFlush.get(2, TimeUnit.SECONDS));
        assertEquals(List.of("latest draft"), store.snapshots.values().iterator().next().lines());
    }

    @Test
    void retainsOnlyOneCapturedVersionAndMovesFairlyBetweenTabs() {
        var first = session("first");
        var second = session("second");
        var queue = new ArrayDeque<Runnable>();
        var store = new MemoryStore();
        var manager = new TerminalEditorApp.RecoveryAutosaveManager(store, queue::addLast);
        List<TabSession> tabs = List.of(first, second);

        assertNull(manager.autosaveDirtyTabs(tabs, 0));
        for (int edit = 0; edit < 100; edit++) {
            first.engine().insertInput("x");
            assertNull(manager.autosaveDirtyTabs(tabs, 3_000_000_000L * (edit + 1)));
            assertEquals(1, queue.size(), "an in-flight snapshot must prevent another capture or queued write");
        }
        queue.removeFirst().run();
        assertEquals(List.of("first"), store.snapshots.values().iterator().next().lines(),
            "the worker receives the immutable version captured before subsequent edits");

        manager.autosaveDirtyTabs(tabs, 400_000_000_000L);
        assertEquals(1, queue.size());
        queue.removeFirst().run();
        assertTrue(store.snapshots.values().stream().anyMatch(snapshot -> snapshot.lines().equals(List.of("second"))),
            "a frequently edited first tab must not starve other tabs");
        assertNull(manager.finishSession(tabs, false));
        assertTrue(store.snapshots.values().stream()
            .anyMatch(snapshot -> snapshot.lines().equals(List.of("first" + "x".repeat(100)))));
    }

    @Test
    @Timeout(10)
    void blockedStorageDoesNotBlockInputAndFinalFlushUsesLatestOwnerState() throws Exception {
        var tab = session("draft");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var ioThread = new AtomicReference<Thread>();
        var store = new MemoryStore() {
            @Override public void save(String id, RecoverySnapshot snapshot) throws IOException {
                ioThread.set(Thread.currentThread());
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    await(release);
                }
                super.save(id, snapshot);
            }
        };
        var manager = new TerminalEditorApp.RecoveryAutosaveManager(store);
        try {
            assertNull(manager.autosaveDirtyTabs(List.of(tab), 0));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), ioThread.get());
            tab.engine().insertInput(" latest");
            tab.engine().moveLeft();
            var cursor = tab.state().getCursor();
            for (int turn = 0; turn < 100; turn++) {
                assertNull(manager.autosaveDirtyTabs(List.of(tab), 3_000_000_000L + turn));
            }
            assertEquals(1, calls.get());
            release.countDown();

            assertNull(manager.finishSession(List.of(tab), false));

            assertEquals(2, calls.get());
            RecoverySnapshot saved = store.snapshots.values().iterator().next();
            assertEquals(List.of("draft latest"), saved.lines());
            assertEquals(cursor, saved.cursor(), "EOF captures view changes even without a new text revision");
        } finally {
            release.countDown();
            manager.finishSession(List.of(tab), true);
        }
    }

    @Test
    @Timeout(10)
    void closingATabDuringAWriteDeletesOnlyAfterTheWriteFinishes() throws Exception {
        var tab = session("draft");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var store = new MemoryStore() {
            @Override public void save(String id, RecoverySnapshot snapshot) throws IOException {
                entered.countDown();
                await(release);
                super.save(id, snapshot);
            }
        };
        var manager = new TerminalEditorApp.RecoveryAutosaveManager(store);
        try {
            manager.syncTabs(List.of(tab));
            manager.autosaveDirtyTabs(List.of(tab), 0);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            manager.syncTabs(List.of());
            assertNull(manager.autosaveDirtyTabs(List.of(), 3_000_000_000L));
            release.countDown();

            assertNull(manager.finishSession(List.of(), true));

            assertEquals(List.of("save", "delete"), store.events);
            assertTrue(store.snapshots.isEmpty(), "an older background save must not resurrect a discarded tab");
        } finally {
            release.countDown();
            manager.finishSession(List.of(), true);
        }
    }

    @Test
    void reportsWorkerFailuresOnTheOwnerThreadAndRetriesAfterDebounce() {
        var tab = session("draft");
        var queue = new ArrayDeque<Runnable>();
        var fail = new AtomicInteger(1);
        var store = new MemoryStore() {
            @Override public void save(String id, RecoverySnapshot snapshot) throws IOException {
                if (fail.getAndDecrement() > 0) throw new IOException("simulated slow disk failure");
                super.save(id, snapshot);
            }
        };
        var manager = new TerminalEditorApp.RecoveryAutosaveManager(store, queue::addLast);
        assertNull(manager.autosaveDirtyTabs(List.of(tab), 0));
        queue.removeFirst().run();
        assertTrue(manager.autosaveDirtyTabs(List.of(tab), 1).contains("simulated slow disk failure"));
        assertNull(manager.autosaveDirtyTabs(List.of(tab), 2));
        assertTrue(queue.isEmpty());
        assertNull(manager.autosaveDirtyTabs(List.of(tab), 2_000_000_000L));
        queue.removeFirst().run();
        assertNull(manager.finishSession(List.of(tab), false));
        assertEquals(List.of("draft"), store.snapshots.values().iterator().next().lines());
    }

    @Test
    void cleanTabsNeverAllocateRecoveryIdsOnDisk() {
        var tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        var store = new MemoryStore();
        var manager = new TerminalEditorApp.RecoveryAutosaveManager(store, Runnable::run);
        manager.syncTabs(List.of(tab));
        assertNull(manager.autosaveDirtyTabs(List.of(tab), 0));
        assertNull(manager.finishSession(List.of(tab), true));
        assertTrue(store.events.isEmpty());
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("test release timed out");
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IOException(interruption);
        }
    }

    private static TabSession session(String text) {
        var tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        tab.engine().insertInput(text);
        return tab;
    }

    private static class MemoryStore implements TerminalEditorApp.RecoveryPersistence {
        final Map<String, RecoverySnapshot> snapshots = new HashMap<>();
        final List<String> events = new ArrayList<>();

        @Override public void save(String id, RecoverySnapshot snapshot) throws IOException {
            events.add("save");
            snapshots.put(id, snapshot);
        }

        @Override public void delete(String id) {
            events.add("delete");
            snapshots.remove(id);
        }

        @Override public void release(String id) { }
    }
}
