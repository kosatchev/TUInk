package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.runtime.TuiApplicationRunner;
import dev.tuiframe.runtime.TuiEventLoop;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.TabSession;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.render.EditorViewNavigation;
import dev.tuink.tui.flow.RecoveryStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class RecoveryAutosaveManagerTest {
    @Test
    void autosaveCapturesDeletionBeforeTheNextFrameNormalizesTheViewport() {
        TabSession session = scrolledSessionAfterDeletingAllText();
        int liveTopLine = session.state().getTopLine();
        FakeRecoveryPersistence persistence = new FakeRecoveryPersistence();
        TerminalEditorApp.RecoveryAutosaveManager manager =
            new TerminalEditorApp.RecoveryAutosaveManager(persistence, Runnable::run);

        assertNull(manager.autosaveDirtyTabs(List.of(session), 0L));

        RecoverySnapshot saved = persistence.snapshots.values().iterator().next();
        assertEquals(List.of(""), saved.lines());
        assertEquals(new Position(0, 0), saved.cursor());
        assertEquals(0, saved.viewport().topLine());
        assertEquals(0, saved.viewport().topWrapSegment());
        assertTrue(saved.dirty());
        assertEquals(liveTopLine, session.state().getTopLine());
        TabSession restored = newSession();
        restored.engine().restoreRecoverySnapshot(saved);
        assertEquals(List.of(""), restored.model().snapshotLines());
        assertEquals(0, restored.state().getTopLine());
    }

    @Test
    void endOfInputRecoversDeletionBeforeAnyViewportUpdateOrAutosave() throws IOException {
        TabSession session = scrolledSessionAfterDeletingAllText();
        FakeRecoveryPersistence persistence = new FakeRecoveryPersistence();
        TerminalEditorApp.RecoveryAutosaveManager manager =
            new TerminalEditorApp.RecoveryAutosaveManager(persistence, Runnable::run);
        TuiEventLoop<TuiInputEvent> loop = new TuiEventLoop<>(timeout -> TuiEventLoop.Read.end());
        List<String> warnings = new ArrayList<>();

        TerminalEditorApp.runWithRecovery(
            loop, new FakeFrontend(), manager, () -> List.of(session), warnings::add
        );

        RecoverySnapshot saved = persistence.snapshots.values().iterator().next();
        assertEquals(List.of(""), saved.lines());
        assertEquals(0, saved.viewport().topLine());
        assertEquals(List.of(), warnings);
        assertEquals(List.of(), persistence.deleteAttempts);
    }

    @Test
    void endOfInputPreservesDirtyTextAndCapturesLatestCursorWithoutTextRevisionChange() throws IOException {
        FakeRecoveryPersistence persistence = new FakeRecoveryPersistence();
        TabSession session = dirtySession("draft");
        RecoverySnapshot snapshot = session.engine().captureRecoverySnapshot();
        persistence.snapshots.put("startup-entry", snapshot);
        TerminalEditorApp.RecoveryAutosaveManager manager =
            new TerminalEditorApp.RecoveryAutosaveManager(persistence, Runnable::run);
        manager.adoptSnapshot(session, "startup-entry", 0L);
        long savedRevision = session.model().revision();
        session.state().setCursor(new Position(0, 2));
        session.state().setSelectionAnchor(new Position(0, 1));
        TuiEventLoop<TuiInputEvent> loop = new TuiEventLoop<>(timeout -> TuiEventLoop.Read.end());
        FakeFrontend frontend = new FakeFrontend();
        List<String> warnings = new ArrayList<>();

        TerminalEditorApp.runWithRecovery(loop, frontend, manager, () -> List.of(session), warnings::add);

        assertFalse(loop.running());
        assertTrue(frontend.running());
        assertTrue(frontend.endOfInputSeen);
        assertEquals(savedRevision, session.model().revision());
        RecoverySnapshot saved = persistence.snapshots.get("startup-entry");
        assertEquals(snapshot.lines(), saved.lines());
        assertTrue(saved.dirty());
        assertEquals(new Position(0, 2), saved.cursor());
        assertEquals(new Position(0, 1), saved.selectionAnchor());
        assertEquals(List.of("startup-entry"), persistence.saveAttempts);
        assertEquals(List.of(), persistence.deleteAttempts);
        assertEquals(List.of(), warnings);
    }

    @Test
    void endOfInputCreatesRecoveryForDirtyTabWithoutPreviousAutosave() throws IOException {
        FakeRecoveryPersistence persistence = new FakeRecoveryPersistence();
        TabSession session = dirtySession("unsaved first draft");
        TerminalEditorApp.RecoveryAutosaveManager manager =
            new TerminalEditorApp.RecoveryAutosaveManager(persistence, Runnable::run);
        TuiEventLoop<TuiInputEvent> loop = new TuiEventLoop<>(timeout -> TuiEventLoop.Read.end());
        List<String> warnings = new ArrayList<>();

        TerminalEditorApp.runWithRecovery(
            loop, new FakeFrontend(), manager, () -> List.of(session), warnings::add
        );

        assertEquals(1, persistence.snapshots.size());
        RecoverySnapshot saved = persistence.snapshots.values().iterator().next();
        assertEquals(List.of("unsaved first draft"), saved.lines());
        assertTrue(saved.dirty());
        assertEquals(List.of(), persistence.deleteAttempts);
        assertEquals(List.of(), warnings);
    }

    @Test
    void interruptDuringPollFlushesFinalEditInsideAutosaveDebounce() throws IOException {
        FakeRecoveryPersistence persistence = new FakeRecoveryPersistence();
        TabSession session = dirtySession("draft");
        persistence.snapshots.put("startup-entry", session.engine().captureRecoverySnapshot());
        TerminalEditorApp.RecoveryAutosaveManager manager =
            new TerminalEditorApp.RecoveryAutosaveManager(persistence, () -> 1L, Runnable::run);
        manager.adoptSnapshot(session, "startup-entry", 0L);
        AtomicReference<TuiEventLoop<TuiInputEvent>> loopReference = new AtomicReference<>();
        TuiEventLoop<TuiInputEvent> loop = new TuiEventLoop<>(timeout -> {
            loopReference.get().requestStop();
            return TuiEventLoop.Read.event(new TextInput("!"));
        });
        loopReference.set(loop);
        FakeFrontend frontend = new FakeFrontend();
        frontend.onPoll = poll -> {
            session.engine().insertInput(((TextInput) poll.event()).text());
            assertNull(manager.autosaveDirtyTabs(List.of(session), 1L));
            assertEquals(List.of(), persistence.saveAttempts);
        };
        List<String> warnings = new ArrayList<>();

        TerminalEditorApp.runWithRecovery(loop, frontend, manager, () -> List.of(session), warnings::add);

        assertTrue(frontend.running());
        assertEquals(List.of("draft!"), persistence.snapshots.get("startup-entry").lines());
        assertEquals(List.of("startup-entry"), persistence.saveAttempts);
        assertEquals(List.of(), persistence.deleteAttempts);
        assertEquals(List.of(), warnings);
    }

    @Test
    void confirmedFrontendQuitDiscardsItsRecoverySnapshot() throws IOException {
        FakeRecoveryPersistence persistence = new FakeRecoveryPersistence();
        TabSession session = dirtySession("explicitly discarded draft");
        persistence.snapshots.put("startup-entry", session.engine().captureRecoverySnapshot());
        TerminalEditorApp.RecoveryAutosaveManager manager =
            new TerminalEditorApp.RecoveryAutosaveManager(persistence, Runnable::run);
        manager.adoptSnapshot(session, "startup-entry", 0L);
        TuiEventLoop<TuiInputEvent> loop = new TuiEventLoop<>(
            timeout -> TuiEventLoop.Read.event(new TextInput("q"))
        );
        FakeFrontend frontend = new FakeFrontend();
        frontend.onPoll = poll -> frontend.running = false;
        List<String> warnings = new ArrayList<>();

        TerminalEditorApp.runWithRecovery(loop, frontend, manager, () -> List.of(session), warnings::add);

        assertTrue(loop.running());
        assertFalse(frontend.running());
        assertFalse(persistence.snapshots.containsKey("startup-entry"));
        assertEquals(List.of("startup-entry"), persistence.deleteAttempts);
        assertEquals(List.of(), persistence.saveAttempts);
        assertEquals(List.of(), warnings);
    }

    @Test
    void pollingFailureFlushesDirtyStateAndPropagatesOriginalFailure() {
        FakeRecoveryPersistence persistence = new FakeRecoveryPersistence();
        TabSession session = dirtySession("draft");
        persistence.snapshots.put("startup-entry", session.engine().captureRecoverySnapshot());
        TerminalEditorApp.RecoveryAutosaveManager manager =
            new TerminalEditorApp.RecoveryAutosaveManager(persistence, () -> 1L, Runnable::run);
        manager.adoptSnapshot(session, "startup-entry", 0L);
        session.engine().insertInput(" after autosave");
        IOException inputFailure = new IOException("simulated terminal input failure");
        TuiEventLoop<TuiInputEvent> loop = new TuiEventLoop<>(timeout -> {
            throw inputFailure;
        });
        List<String> warnings = new ArrayList<>();

        IOException thrown = assertThrows(IOException.class, () -> TerminalEditorApp.runWithRecovery(
            loop, new FakeFrontend(), manager, () -> List.of(session), warnings::add
        ));

        assertSame(inputFailure, thrown);
        assertEquals(List.of("draft after autosave"), persistence.snapshots.get("startup-entry").lines());
        assertEquals(List.of("startup-entry"), persistence.saveAttempts);
        assertEquals(List.of(), persistence.deleteAttempts);
        assertEquals(List.of(), warnings);
    }

    @Test
    void renderFailurePreservesDirtyStateEvenIfFrontendAlreadyStopped() {
        FakeRecoveryPersistence persistence = new FakeRecoveryPersistence();
        TabSession session = dirtySession("draft");
        persistence.snapshots.put("startup-entry", session.engine().captureRecoverySnapshot());
        TerminalEditorApp.RecoveryAutosaveManager manager =
            new TerminalEditorApp.RecoveryAutosaveManager(persistence, () -> 1L, Runnable::run);
        manager.adoptSnapshot(session, "startup-entry", 0L);
        session.engine().insertInput(" after autosave");
        IllegalStateException renderFailure = new IllegalStateException("simulated renderer failure");
        TuiEventLoop<TuiInputEvent> loop = new TuiEventLoop<>(timeout -> {
            throw new AssertionError("input must not be polled after rendering fails");
        });
        FakeFrontend frontend = new FakeFrontend();
        frontend.onRender = () -> {
            frontend.running = false;
            throw renderFailure;
        };
        List<String> warnings = new ArrayList<>();

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> TerminalEditorApp.runWithRecovery(loop, frontend, manager, () -> List.of(session), warnings::add)
        );

        assertSame(renderFailure, thrown);
        assertEquals(List.of("draft after autosave"), persistence.snapshots.get("startup-entry").lines());
        assertEquals(List.of("startup-entry"), persistence.saveAttempts);
        assertEquals(List.of(), persistence.deleteAttempts);
        assertEquals(List.of(), warnings);
    }

    @Test
    void failedFinalSaveKeepsPreviousSnapshotAndReportsDespiteRecentAutosaveError() throws IOException {
        FakeRecoveryPersistence persistence = new FakeRecoveryPersistence();
        TabSession session = dirtySession("previously committed draft");
        RecoverySnapshot previous = session.engine().captureRecoverySnapshot();
        persistence.snapshots.put("startup-entry", previous);
        persistence.failingSaves.add("startup-entry");
        TerminalEditorApp.RecoveryAutosaveManager manager =
            new TerminalEditorApp.RecoveryAutosaveManager(persistence, () -> 3_000_000_002L, Runnable::run);
        manager.adoptSnapshot(session, "startup-entry", 0L);
        session.engine().insertInput(" latest edit");
        assertTrue(manager.autosaveDirtyTabs(List.of(session), 3_000_000_000L)
            .contains("Recovery autosave failed"));
        assertNull(manager.autosaveDirtyTabs(List.of(session), 3_000_000_001L));
        TuiEventLoop<TuiInputEvent> loop = new TuiEventLoop<>(timeout -> TuiEventLoop.Read.end());
        List<String> warnings = new ArrayList<>();

        TerminalEditorApp.runWithRecovery(
            loop, new FakeFrontend(), manager, () -> List.of(session), warnings::add
        );

        assertSame(previous, persistence.snapshots.get("startup-entry"));
        assertEquals(List.of("startup-entry", "startup-entry"), persistence.saveAttempts);
        assertEquals(List.of(), persistence.deleteAttempts);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("Recovery autosave failed"));
        assertTrue(warnings.get(0).contains("simulated save failure"));
    }

    @Test
    void runnerFailureRemainsPrimaryWhenFinalRecoverySaveThrowsUncheckedException() {
        FakeRecoveryPersistence persistence = new FakeRecoveryPersistence();
        TabSession session = dirtySession("previously committed draft");
        RecoverySnapshot previous = session.engine().captureRecoverySnapshot();
        persistence.snapshots.put("startup-entry", previous);
        IllegalStateException saveFailure = new IllegalStateException("simulated unexpected save failure");
        persistence.uncheckedSaveFailure = saveFailure;
        TerminalEditorApp.RecoveryAutosaveManager manager =
            new TerminalEditorApp.RecoveryAutosaveManager(persistence, () -> 1L, Runnable::run);
        manager.adoptSnapshot(session, "startup-entry", 0L);
        session.engine().insertInput(" latest edit");
        IOException inputFailure = new IOException("simulated terminal input failure");
        TuiEventLoop<TuiInputEvent> loop = new TuiEventLoop<>(timeout -> {
            throw inputFailure;
        });

        IOException thrown = assertThrows(IOException.class, () -> TerminalEditorApp.runWithRecovery(
            loop, new FakeFrontend(), manager, () -> List.of(session), warning -> { }
        ));

        assertSame(inputFailure, thrown);
        assertEquals(List.of(saveFailure), List.of(thrown.getSuppressed()));
        assertSame(previous, persistence.snapshots.get("startup-entry"));
        assertEquals(List.of(), persistence.deleteAttempts);
    }

    @Test
    void restoredTabAdoptsCommittedEntryWithoutDeleteBeforeReplacement() {
        FakeRecoveryPersistence persistence = new FakeRecoveryPersistence();
        TabSession source = dirtySession("draft");
        RecoverySnapshot snapshot = source.engine().captureRecoverySnapshot();
        persistence.snapshots.put("startup-entry", snapshot);

        TabSession restored = newSession();
        restored.engine().restoreRecoverySnapshot(snapshot);
        TerminalEditorApp.RecoveryAutosaveManager manager =
            new TerminalEditorApp.RecoveryAutosaveManager(persistence, Runnable::run);

        manager.adoptSnapshot(restored, "startup-entry", 0L);
        manager.syncTabs(List.of(restored));

        assertNull(manager.autosaveDirtyTabs(List.of(restored), 1L));
        assertEquals(List.of(), persistence.deleteAttempts);
        assertEquals(List.of(), persistence.saveAttempts);
        assertEquals(snapshot, persistence.snapshots.get("startup-entry"));

        restored.engine().insertInput("!");
        assertNull(manager.autosaveDirtyTabs(List.of(restored), 3_000_000_000L));

        assertEquals(List.of("startup-entry"), persistence.saveAttempts);
        assertEquals(List.of(), persistence.deleteAttempts);
        assertTrue(persistence.snapshots.containsKey("startup-entry"));
    }

    @Test
    void failedCleanupOfRemovedTabRemainsQueuedAndIsRetried() {
        FakeRecoveryPersistence persistence = new FakeRecoveryPersistence();
        TabSession restored = dirtySession("recover me");
        RecoverySnapshot snapshot = restored.engine().captureRecoverySnapshot();
        persistence.snapshots.put("startup-entry", snapshot);
        persistence.failingDeletes.add("startup-entry");
        TerminalEditorApp.RecoveryAutosaveManager manager =
            new TerminalEditorApp.RecoveryAutosaveManager(persistence, Runnable::run);
        manager.adoptSnapshot(restored, "startup-entry", 0L);

        manager.syncTabs(List.of());
        String warning = manager.autosaveDirtyTabs(List.of(), 1L);

        assertTrue(warning.contains("Recovery cleanup failed"));
        assertEquals(List.of("startup-entry"), persistence.deleteAttempts);
        assertTrue(persistence.snapshots.containsKey("startup-entry"));

        persistence.failingDeletes.clear();
        assertNull(manager.autosaveDirtyTabs(List.of(), 3_000_000_001L));

        assertEquals(List.of("startup-entry", "startup-entry"), persistence.deleteAttempts);
        assertFalse(persistence.snapshots.containsKey("startup-entry"));
    }

    @Test
    void explicitDiscardReportsFailuresAndReturnsThemForRetry() {
        FakeRecoveryPersistence persistence = new FakeRecoveryPersistence();
        RecoverySnapshot snapshot = dirtySession("draft").engine().captureRecoverySnapshot();
        RecoveryStore.Entry first = new RecoveryStore.Entry("first", snapshot);
        RecoveryStore.Entry second = new RecoveryStore.Entry("second", snapshot);
        persistence.snapshots.put(first.id(), snapshot);
        persistence.snapshots.put(second.id(), snapshot);
        persistence.failingDeletes.add(second.id());
        TerminalEditorApp.RecoveryAutosaveManager manager =
            new TerminalEditorApp.RecoveryAutosaveManager(persistence, Runnable::run);

        TerminalEditorApp.RecoveryAutosaveManager.DeletionResult result =
            manager.deleteEntries(List.of(first, second));

        assertFalse(result.successful());
        assertEquals(1, result.deletedCount());
        assertEquals(List.of(second), result.failedEntries());
        assertTrue(result.failureMessage().contains("kept for retry or restore"));
        assertFalse(persistence.snapshots.containsKey(first.id()));
        assertTrue(persistence.snapshots.containsKey(second.id()));

        persistence.failingDeletes.clear();
        TerminalEditorApp.RecoveryAutosaveManager.DeletionResult retry =
            manager.deleteEntries(result.failedEntries());

        assertTrue(retry.successful());
        assertEquals(1, retry.deletedCount());
        assertFalse(persistence.snapshots.containsKey(second.id()));
    }

    @Test
    void firstFailedSaveWaitsBeforeRetryingAndCapturesTheLatestEdit() {
        FakeRecoveryPersistence persistence = new FakeRecoveryPersistence();
        persistence.failAllSaves = true;
        TabSession session = dirtySession("draft");
        TerminalEditorApp.RecoveryAutosaveManager manager =
            new TerminalEditorApp.RecoveryAutosaveManager(persistence, Runnable::run);

        assertTrue(manager.autosaveDirtyTabs(List.of(session), 0L).contains("Recovery autosave failed"));
        for (int turn = 1; turn < 100; turn++) {
            assertNull(manager.autosaveDirtyTabs(List.of(session), turn * 16_000_000L));
        }
        assertEquals(1, persistence.saveAttempts.size());
        assertTrue(persistence.snapshots.isEmpty());

        session.engine().insertInput(" latest");
        persistence.failAllSaves = false;
        assertNull(manager.autosaveDirtyTabs(List.of(session), 2_000_000_000L));

        assertEquals(2, persistence.saveAttempts.size());
        assertEquals(List.of("draft latest"), persistence.snapshots.values().iterator().next().lines());
        assertNull(manager.autosaveDirtyTabs(List.of(session), 4_000_000_000L));
        assertEquals(2, persistence.saveAttempts.size());
    }

    @Test
    void failedUpdateThrottlesRetriesAndKeepsTheLastCommittedSnapshot() {
        FakeRecoveryPersistence persistence = new FakeRecoveryPersistence();
        TabSession session = dirtySession("draft");
        TerminalEditorApp.RecoveryAutosaveManager manager =
            new TerminalEditorApp.RecoveryAutosaveManager(persistence, Runnable::run);
        assertNull(manager.autosaveDirtyTabs(List.of(session), 0L));
        RecoverySnapshot committed = persistence.snapshots.values().iterator().next();
        session.engine().insertInput(" latest");
        persistence.failAllSaves = true;

        assertTrue(manager.autosaveDirtyTabs(List.of(session), 2_000_000_000L)
            .contains("Recovery autosave failed"));
        assertNull(manager.autosaveDirtyTabs(List.of(session), 2_000_000_001L));
        assertNull(manager.autosaveDirtyTabs(List.of(session), 3_999_999_999L));
        assertEquals(2, persistence.saveAttempts.size());
        assertSame(committed, persistence.snapshots.values().iterator().next());

        persistence.failAllSaves = false;
        assertNull(manager.autosaveDirtyTabs(List.of(session), 4_000_000_000L));
        assertEquals(3, persistence.saveAttempts.size());
        assertEquals(List.of("draft latest"), persistence.snapshots.values().iterator().next().lines());
    }

    private static TabSession scrolledSessionAfterDeletingAllText() {
        TabSession session = newSession();
        session.model().replaceLines(java.util.Collections.nCopies(100, "line"));
        session.engine().moveCursorTo(new Position(99, 4));
        EditorViewNavigation.ensureCursorVisible(session.engine(), 20, 80, false, false);
        assertTrue(session.state().getTopLine() > 0);
        session.engine().selectAll();
        session.engine().deleteForward();
        assertEquals(1, session.model().lineCount());
        assertTrue(session.state().getTopLine() >= session.model().lineCount());
        return session;
    }

    private static TabSession dirtySession(String text) {
        TabSession session = newSession();
        session.engine().insertInput(text);
        return session;
    }

    private static TabSession newSession() {
        return TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
    }

    private static final class FakeFrontend implements TuiApplicationRunner.Frontend<TuiInputEvent> {
        private boolean running = true;
        private boolean endOfInputSeen;
        private Runnable onRender = () -> { };
        private Consumer<TuiEventLoop.Poll<TuiInputEvent>> onPoll = poll -> { };

        @Override
        public boolean running() {
            return running;
        }

        @Override
        public void prepareTurn() {
        }

        @Override
        public boolean renderFrame(TuiEventLoop.FrameTicket frameTicket) {
            onRender.run();
            return true;
        }

        @Override
        public long nanosUntilWakeup(long nowNanos) {
            return -1L;
        }

        @Override
        public void handlePoll(TuiEventLoop.Poll<TuiInputEvent> poll) {
            endOfInputSeen |= poll.endOfInput();
            onPoll.accept(poll);
        }
    }

    private static final class FakeRecoveryPersistence implements TerminalEditorApp.RecoveryPersistence {
        private final Map<String, RecoverySnapshot> snapshots = new HashMap<>();
        private final Set<String> failingDeletes = new HashSet<>();
        private final Set<String> failingSaves = new HashSet<>();
        private final List<String> saveAttempts = new ArrayList<>();
        private final List<String> deleteAttempts = new ArrayList<>();
        private RuntimeException uncheckedSaveFailure;
        private boolean failAllSaves;

        @Override
        public void save(String entryId, RecoverySnapshot snapshot) throws IOException {
            saveAttempts.add(entryId);
            if (uncheckedSaveFailure != null) {
                throw uncheckedSaveFailure;
            }
            if (failAllSaves || failingSaves.contains(entryId)) {
                throw new IOException("simulated save failure for " + entryId);
            }
            snapshots.put(entryId, snapshot);
        }

        @Override
        public void delete(String entryId) throws IOException {
            deleteAttempts.add(entryId);
            if (failingDeletes.contains(entryId)) {
                throw new IOException("simulated delete failure for " + entryId);
            }
            snapshots.remove(entryId);
        }

        @Override
        public void release(String entryId) {
            // The in-memory port has no process ownership to release.
        }
    }
}
