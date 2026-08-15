package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.TabSession;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.flow.RecoveryStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecoveryAutosaveManagerTest {
    @Test
    void restoredTabAdoptsCommittedEntryWithoutDeleteBeforeReplacement() {
        FakeRecoveryPersistence persistence = new FakeRecoveryPersistence();
        TabSession source = dirtySession("draft");
        RecoverySnapshot snapshot = source.engine().captureRecoverySnapshot();
        persistence.snapshots.put("startup-entry", snapshot);

        TabSession restored = newSession();
        restored.engine().restoreRecoverySnapshot(snapshot);
        TerminalEditorApp.RecoveryAutosaveManager manager =
            new TerminalEditorApp.RecoveryAutosaveManager(persistence);

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
            new TerminalEditorApp.RecoveryAutosaveManager(persistence);
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
            new TerminalEditorApp.RecoveryAutosaveManager(persistence);

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

    private static TabSession dirtySession(String text) {
        TabSession session = newSession();
        session.engine().insertInput(text);
        return session;
    }

    private static TabSession newSession() {
        return TabSession.createEmpty(TerminalEditorTextLayout::new);
    }

    private static final class FakeRecoveryPersistence implements TerminalEditorApp.RecoveryPersistence {
        private final Map<String, RecoverySnapshot> snapshots = new HashMap<>();
        private final Set<String> failingDeletes = new HashSet<>();
        private final List<String> saveAttempts = new ArrayList<>();
        private final List<String> deleteAttempts = new ArrayList<>();

        @Override
        public void save(String entryId, RecoverySnapshot snapshot) {
            saveAttempts.add(entryId);
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
    }
}
