package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.core.EditorClipboard;
import dev.tuink.editor.clipboard.ClipboardPort;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SystemClipboardControllerTest {
    @Test void localOnlyPasteIsSynchronousAndDoesNotStartAnExecutorOrWarn() {
        var clipboard = clipboard("local");
        var warnings = new ArrayList<String>();
        var pasted = new ArrayList<String>();
        try (var controller = new SystemClipboardController(clipboard, null, () -> fail("No wakeup needed"),
            warnings::add, task -> fail("No worker needed"))) {
            controller.paste(() -> true, () -> pasted.add(clipboard.text()));
            assertEquals(List.of("local"), pasted);
            assertFalse(controller.deferInput(() -> fail("Not deferred")));
            controller.paste(() -> false, () -> fail("A retired target cannot paste"));
            clipboard.setText("copy");
            controller.synchronize();
            controller.collect();
            assertFalse(controller.busy());
            assertTrue(warnings.isEmpty());
        }
    }

    @Test void workerReadDoesNotImportOrApplyUntilTheOwnerCollectsAndNeverEchoesImports() throws Exception {
        var fixture = new Fixture();
        var appliedOn = new AtomicReference<Thread>();
        fixture.controller.paste(() -> true, () -> appliedOn.set(Thread.currentThread()));
        assertTrue(fixture.controller.busy());
        fixture.executor.runNextOnWorker();
        assertEquals("local", fixture.clipboard.text());
        assertNull(appliedOn.get());
        assertNotSame(Thread.currentThread(), fixture.port.lastThread);
        assertEquals(1, fixture.wakeups.get());
        fixture.controller.collect();
        assertEquals("external", fixture.clipboard.text());
        assertSame(Thread.currentThread(), appliedOn.get());
        fixture.controller.synchronize();
        fixture.controller.collect();
        assertFalse(fixture.controller.busy());
        assertEquals(List.of("read"), fixture.port.operations);
        fixture.close();
    }

    @Test void emptyStringIsAnAcceptedExternalValueRatherThanAnUnavailableRead() {
        var fixture = new Fixture();
        fixture.port.external = Optional.of("");
        var pasted = new ArrayList<String>();
        fixture.controller.paste(() -> true, () -> pasted.add(fixture.clipboard.text()));
        fixture.executor.runNext();
        fixture.controller.collect();
        assertEquals(List.of(""), pasted);
        assertEquals("", fixture.clipboard.text());
        assertTrue(fixture.warnings.isEmpty());
        assertEquals(List.of("read"), fixture.port.operations);
        fixture.close();
    }

    @Test void unavailableAndThrowingReadsWarnAndUseTheInternalClipboard() {
        for (boolean throwing : List.of(false, true)) {
            var fixture = new Fixture();
            fixture.port.external = Optional.empty();
            fixture.port.failRead = throwing;
            var pasted = new ArrayList<String>();
            fixture.controller.paste(() -> true, () -> pasted.add(fixture.clipboard.text()));
            fixture.executor.runNext();
            fixture.controller.collect();
            assertEquals(List.of("local"), pasted);
            assertEquals("local", fixture.clipboard.text());
            assertEquals(1, fixture.warnings.size());
            assertFalse(fixture.controller.busy());
            fixture.close();
        }
    }

    @Test void repeatedCopyPublishesAgainEvenWithIdenticalTextButIdleSynchronizationDoesNot() {
        var fixture = new Fixture();
        for (int copy = 0; copy < 2; copy++) {
            fixture.clipboard.setText("same");
            fixture.controller.synchronize();
            assertEquals(1, fixture.executor.size());
            fixture.executor.runNext();
            fixture.controller.collect();
            fixture.controller.synchronize();
            fixture.controller.collect();
            assertEquals(0, fixture.executor.size());
        }
        assertEquals(List.of("write:same", "write:same"), fixture.port.operations);
        fixture.close();
    }

    @Test void writesCoalesceToTheNewestPublicationAndReadRunsAfterAllPrecedingWrites() {
        var fixture = new Fixture();
        fixture.clipboard.setText("first"); fixture.controller.synchronize();
        fixture.clipboard.setText("superseded"); fixture.controller.synchronize();
        fixture.clipboard.setText("latest"); fixture.controller.synchronize();
        var pasted = new ArrayList<String>();
        fixture.controller.paste(() -> true, () -> pasted.add(fixture.clipboard.text()));
        assertEquals(1, fixture.executor.size(), "Only the active operation is submitted");
        fixture.executor.runNext(); fixture.controller.collect();
        assertEquals(List.of("write:first"), fixture.port.operations);
        assertEquals(1, fixture.executor.size());
        fixture.executor.runNext(); fixture.controller.collect();
        assertEquals(List.of("write:first", "write:latest"), fixture.port.operations);
        assertTrue(pasted.isEmpty());
        fixture.executor.runNext(); fixture.controller.collect();
        assertEquals(List.of("write:first", "write:latest", "read"), fixture.port.operations);
        assertEquals(List.of("latest"), pasted);
        assertTrue(fixture.warnings.isEmpty());
        assertFalse(fixture.controller.busy());
        fixture.close();
    }

    @Test void failedWritesRetainLocalTextAndDoNotBlockTheFollowingRead() {
        var fixture = new Fixture();
        fixture.port.writeAllowed = false;
        fixture.clipboard.setText("copied"); fixture.controller.synchronize();
        fixture.port.external = Optional.empty();
        var pasted = new ArrayList<String>();
        fixture.controller.paste(() -> true, () -> pasted.add(fixture.clipboard.text()));
        fixture.executor.runNext(); fixture.controller.collect();
        assertEquals("copied", fixture.clipboard.text());
        fixture.executor.runNext(); fixture.controller.collect();
        assertEquals(List.of("copied"), pasted);
        assertEquals(2, fixture.warnings.size());
        assertFalse(fixture.controller.busy());
        fixture.close();
    }

    @Test void aRetiredTargetDiscardsTheReadWithoutChangingTheInternalClipboard() {
        var fixture = new Fixture();
        var order = new ArrayList<String>();
        fixture.controller.paste(() -> false, () -> fail("Retired recipient"));
        assertTrue(fixture.controller.deferInput(() -> order.add("next input")));
        fixture.executor.runNext(); fixture.controller.collect();
        assertEquals("local", fixture.clipboard.text());
        assertEquals(List.of("next input"), order);
        assertFalse(fixture.controller.busy());
        fixture.close();
    }

    @Test void eachCollectionReplaysOneEventAndReentrantPreparationCannotDrainAnother() {
        var fixture = new Fixture();
        var order = new ArrayList<String>();
        fixture.controller.paste(() -> true, () -> order.add("paste:" + fixture.clipboard.text()));
        assertTrue(fixture.controller.deferInput(() -> {
            order.add("first");
            fixture.controller.collect();
            fixture.controller.synchronize();
            assertFalse(fixture.controller.deferInput(() -> fail("Replayed input must bypass deferral")));
        }));
        assertTrue(fixture.controller.deferInput(() -> order.add("second")));
        fixture.executor.runNext(); fixture.controller.collect();
        assertEquals(List.of("paste:external", "first"), order);
        assertTrue(fixture.controller.busy(), "The backlog keeps the runner awake");
        assertTrue(fixture.controller.deferInput(() -> order.add("third")), "Fresh input cannot overtake a backlog");
        fixture.controller.collect();
        assertEquals(List.of("paste:external", "first"), order, "A second collection cannot cross the frame fence");
        fixture.controller.frameEnded();
        fixture.controller.collect();
        assertEquals(List.of("paste:external", "first", "second"), order);
        fixture.controller.frameEnded();
        fixture.controller.collect();
        assertEquals(List.of("paste:external", "first", "second", "third"), order);
        assertFalse(fixture.controller.busy());
        fixture.close();
    }

    @Test void aReplayedSecondPastePausesTheRemainingFifoUntilItsOwnReadCompletes() {
        var fixture = new Fixture();
        var order = new ArrayList<String>();
        fixture.controller.paste(() -> true, () -> order.add("paste1:" + fixture.clipboard.text()));
        fixture.controller.deferInput(() -> order.add("key1"));
        fixture.controller.deferInput(() -> fixture.controller.paste(() -> true,
            () -> order.add("paste2:" + fixture.clipboard.text())));
        fixture.controller.deferInput(() -> order.add("key2"));
        fixture.executor.runNext(); fixture.controller.collect();
        assertEquals(List.of("paste1:external", "key1"), order);
        fixture.controller.frameEnded();
        fixture.controller.collect();
        assertEquals(1, fixture.executor.size());
        fixture.controller.collect();
        assertEquals(List.of("paste1:external", "key1"), order);
        fixture.port.external = Optional.of("new external");
        fixture.executor.runNext(); fixture.controller.frameEnded(); fixture.controller.collect();
        assertEquals(List.of("paste1:external", "key1", "paste2:new external", "key2"), order);
        assertFalse(fixture.controller.busy());
        fixture.close();
    }

    @Test void overflowingInputUsesOneLocalFallbackThenKeepsEveryEventInOrderAndRejectsTheLateImport() {
        var fixture = new Fixture();
        var order = new ArrayList<String>();
        fixture.controller.paste(() -> true, () -> order.add("paste:" + fixture.clipboard.text()));
        for (int index = 0; index < 257; index++) {
            int value = index;
            assertTrue(fixture.controller.deferInput(() -> order.add(Integer.toString(value))));
        }
        assertEquals(List.of("paste:local", "0"), order);
        assertEquals(1, fixture.warnings.size());
        fixture.executor.runNext(); fixture.controller.collect();
        assertEquals(List.of("paste:local", "0"), order, "Overflow cannot replay another event before a frame");
        assertEquals("local", fixture.clipboard.text(), "The revoked read cannot import its late text");
        for (int index = 0; index < 256; index++) {
            fixture.controller.frameEnded();
            fixture.controller.collect();
        }
        var expected = new ArrayList<String>(); expected.add("paste:local");
        for (int index = 0; index < 257; index++) expected.add(Integer.toString(index));
        assertEquals(expected, order);
        assertFalse(fixture.controller.busy());
        assertEquals(1, fixture.port.operations.size());
        fixture.close();
    }

    @Test void rejectedSubmissionFallsBackAndAnExecutorWhichRunsThenThrowsKeepsTheActualResult() {
        for (boolean started : List.of(false, true)) {
            var clipboard = clipboard("local");
            var port = new Port();
            var warnings = new ArrayList<String>();
            var pasted = new ArrayList<String>();
            Executor executor = task -> {
                if (started) task.run();
                throw new RejectedExecutionException("synthetic submission failure");
            };
            try (var controller = new SystemClipboardController(clipboard, port, () -> {}, warnings::add, executor)) {
                controller.paste(() -> true, () -> pasted.add(clipboard.text()));
                controller.collect();
                assertEquals(List.of(started ? "external" : "local"), pasted);
                assertEquals(started ? 0 : 1, warnings.size());
                assertFalse(controller.busy());
            }
        }
    }

    @Test void closeFlushesTheLatestWriteAndNeverImportsAReadyButUnpublishedRead() {
        var clipboard = clipboard("local");
        var port = new Port();
        var warnings = new ArrayList<String>();
        var controller = new SystemClipboardController(clipboard, port, () -> {}, warnings::add, Runnable::run);
        controller.paste(() -> true, () -> fail("Disposal revokes paste"));
        clipboard.setText("last copy");
        controller.close(0);
        assertEquals("last copy", clipboard.text());
        assertEquals(List.of("read", "write:last copy"), port.operations);
        assertTrue(warnings.isEmpty());
        assertFalse(controller.busy());
        controller.close(0);
    }

    @Test void closeDeadlineRevokesAnUnstartedReadWithoutWaitingOrApplyingLateInput() {
        var fixture = new Fixture();
        fixture.controller.paste(() -> true, () -> fail("Disposed paste"));
        fixture.controller.deferInput(() -> fail("Disposed input"));
        fixture.controller.close(0);
        fixture.executor.runNext();
        fixture.controller.collect();
        assertEquals("local", fixture.clipboard.text());
        assertTrue(fixture.port.operations.isEmpty());
        assertEquals(1, fixture.warnings.size());
        assertFalse(fixture.controller.busy());
    }

    @Test void aCancelledRunningReadCanFinishLateWithoutImportApplyOrKeepingItsWakeup() throws Exception {
        var clipboard = clipboard("local");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = new ManualExecutor();
        var wakeups = new AtomicInteger();
        var port = new ClipboardPort() {
            @Override public Optional<String> readText() {
                entered.countDown();
                boolean interrupted = false;
                for (;;) {
                    try { release.await(); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
                return Optional.of("late external");
            }
            @Override public boolean writeText(String text) { fail("No write was requested"); return false; }
        };
        var controller = new SystemClipboardController(clipboard, port, wakeups::incrementAndGet, ignored -> {}, executor);
        controller.paste(() -> true, () -> fail("Disposed target"));
        var worker = new Thread(executor.tasks.remove());
        worker.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            controller.close(0);
            assertFalse(controller.busy());
        } finally {
            release.countDown();
            worker.join(2_000);
        }
        assertFalse(worker.isAlive());
        controller.collect();
        assertEquals("local", clipboard.text());
        assertEquals(0, wakeups.get(), "Close detaches the worker from the retired window");
    }

    @Test void aRuntimeWriteFailureDoesNotRetryOrDiscardTheLocalPublication() {
        var fixture = new Fixture();
        fixture.port.failWrite = true;
        fixture.clipboard.setText("copied");
        fixture.controller.synchronize();
        fixture.executor.runNext(); fixture.controller.collect();
        fixture.controller.synchronize(); fixture.controller.collect();
        assertEquals("copied", fixture.clipboard.text());
        assertEquals(1, fixture.warnings.size());
        assertEquals(0, fixture.executor.size());
        assertFalse(fixture.controller.busy());
        fixture.close();
    }

    @Test void controllerMutationIsRestrictedToItsUiOwner() throws Exception {
        var fixture = new Fixture();
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try { fixture.controller.collect(); }
            catch (Throwable rejected) { failure.set(rejected); }
        });
        thread.start(); thread.join(2_000);
        assertFalse(thread.isAlive());
        assertInstanceOf(IllegalStateException.class, failure.get());
        fixture.close();
    }

    @Test void aNoopReplayedInputStillSchedulesTheFrameWhichReleasesTheFence() {
        var fixture = new Fixture();
        fixture.controller.paste(() -> true, () -> {});
        fixture.controller.deferInput(() -> {});
        fixture.controller.deferInput(() -> {});
        fixture.executor.runNext();
        int before = fixture.wakeups.get();
        fixture.controller.collect();
        assertEquals(before + 1, fixture.wakeups.get());
        assertTrue(fixture.controller.busy());
        fixture.controller.frameEnded();
        fixture.controller.collect();
        assertEquals(before + 2, fixture.wakeups.get());
        assertFalse(fixture.controller.busy());
        fixture.close();
    }

    private static EditorClipboard clipboard(String value) {
        var clipboard = new EditorClipboard(); clipboard.setText(value); return clipboard;
    }
    private static final class Fixture implements AutoCloseable {
        final EditorClipboard clipboard = clipboard("local");
        final Port port = new Port();
        final ManualExecutor executor = new ManualExecutor();
        final List<String> warnings = new ArrayList<>();
        final AtomicInteger wakeups = new AtomicInteger();
        final SystemClipboardController controller = new SystemClipboardController(clipboard, port,
            wakeups::incrementAndGet, warnings::add, executor);
        @Override public void close() { controller.close(0); }
    }
    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        int size() { return tasks.size(); }
        void runNext() { tasks.remove().run(); }
        void runNextOnWorker() throws InterruptedException {
            var thread = new Thread(tasks.remove());
            thread.start(); thread.join(2_000);
            assertFalse(thread.isAlive());
        }
    }
    private static final class Port implements ClipboardPort {
        Optional<String> external = Optional.of("external");
        boolean writeAllowed = true;
        boolean failRead;
        boolean failWrite;
        Thread lastThread;
        final List<String> operations = new ArrayList<>();
        @Override public Optional<String> readText() {
            lastThread = Thread.currentThread(); operations.add("read");
            if (failRead) throw new IllegalStateException("synthetic read failure");
            return external;
        }
        @Override public boolean writeText(String text) {
            lastThread = Thread.currentThread(); operations.add("write:" + text);
            if (failWrite) throw new IllegalStateException("synthetic write failure");
            if (writeAllowed) external = Optional.of(text);
            return writeAllowed;
        }
    }
}
