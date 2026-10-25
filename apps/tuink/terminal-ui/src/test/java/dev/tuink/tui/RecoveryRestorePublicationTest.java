package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.RecoverySnapshot;
import dev.tuink.tui.flow.RecoveryStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryRestorePublicationTest {
    @TempDir Path directory;

    private enum ReadFailure { IO, RUNTIME, MEMORY }

    @ParameterizedTest
    @EnumSource(ReadFailure.class)
    void partialRestorePublishesGoodDraftsAndRetainsTheFailedEntry(ReadFailure kind) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("disk document"))) {
            var existing = window.active();
            var first = entry("first", "first draft");
            var broken = entry("broken", "unreadable draft");
            var last = entry("last", "last draft");
            for (var saved : List.of(first, broken, last)) window.recoverySnapshots.put(saved.id(), saved.snapshot());
            RecoveryStore.PendingSnapshot failing = new RecoveryStore.PendingSnapshot() {
                @Override public String id() { return broken.id(); }
                @Override public RecoverySnapshot snapshot() throws IOException {
                    switch (kind) {
                        case IO -> throw new IOException("snapshot read failed");
                        case RUNTIME -> throw new IllegalStateException("snapshot materialization failed");
                        case MEMORY -> throw new OutOfMemoryError("snapshot materialization failed");
                    }
                    throw new AssertionError("unreachable");
                }
            };

            var result = window.host.restoreRecoveredTabs(List.of(first, failing, last), window.tabs,
                window.context, window.recoveryManager, 0L);

            assertEquals(2, result.restoredCount());
            assertEquals(List.of(failing), result.failedEntries());
            assertTrue(result.failureDetails().getFirst().contains("failed"));
            assertEquals(3, window.tabs.size());
            assertSame(existing, window.tabs.getFirst());
            assertEquals(first.snapshot().lines(), window.tabs.get(1).tabSession().model().snapshotLines());
            assertEquals(last.snapshot().lines(), window.tabs.get(2).tabSession().model().snapshotLines());
            for (var tab : window.tabs) {
                assertEquals(tab.documentSession().document().documentId(), tab.renderPlanBuilder().documentId());
                assertSame(tab.tabSession().textLayout(), tab.renderPlanBuilder().textLayout());
            }
            assertNull(window.recoveryManager.finishSession(window.host.toTabSessions(window.tabs), false));
            assertEquals(Set.of("first", "broken", "last"), window.recoverySnapshots.keySet());
            assertEquals(broken.snapshot(), window.recoverySnapshots.get("broken"));
            assertEquals(first.snapshot().lines(), window.recoverySnapshots.get("first").lines());
            assertEquals(last.snapshot().lines(), window.recoverySnapshots.get("last").lines());
        }
    }

    @Test
    void abortAfterPreparingOneEntryCannotMakeItsStartupSnapshotEligibleForDeletion() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("disk document"))) {
            var existing = window.active();
            var first = entry("first", "only committed copy");
            window.recoverySnapshots.put(first.id(), first.snapshot());
            OutOfMemoryError allocationFailure = new OutOfMemoryError("next restore item allocation");
            List<RecoveryStore.PendingSnapshot> entries = new AbstractList<>() {
                @Override public int size() { return 2; }
                @Override public RecoveryStore.PendingSnapshot get(int index) {
                    if (index == 0) return first;
                    throw allocationFailure;
                }
            };

            assertSame(allocationFailure, assertThrows(OutOfMemoryError.class,
                () -> window.host.restoreRecoveredTabs(entries, window.tabs, window.context, window.recoveryManager, 0L)));

            assertEquals(List.of(existing), window.tabs);
            assertNull(window.recoveryManager.finishSession(window.host.toTabSessions(window.tabs), false));
            assertEquals(Set.of("first"), window.recoverySnapshots.keySet());
            assertSame(first.snapshot(), window.recoverySnapshots.get("first"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFailedOrPartialAppendPreservesThePlaceholderAndEveryCommittedDraft(boolean partial) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("disk document"))) {
            var placeholder = window.host.newEditorTab();
            FailingAppendList tabs = new FailingAppendList(List.of(placeholder), partial);
            var context = new TerminalEditorApp.SessionContext(tabs, 0);
            var first = entry("first", "first draft");
            var last = entry("last", "last draft");
            window.recoverySnapshots.put(first.id(), first.snapshot());
            window.recoverySnapshots.put(last.id(), last.snapshot());

            assertSame(tabs.failure, assertThrows(OutOfMemoryError.class,
                () -> window.host.restoreRecoveredTabs(List.of(first, last), tabs, context, window.recoveryManager, 0L)));

            assertEquals(List.of(placeholder), tabs);
            assertSame(placeholder.tabSession().engine(), context.engine());
            assertNull(window.recoveryManager.finishSession(window.host.toTabSessions(tabs), false));
            assertEquals(Set.of("first", "last"), window.recoverySnapshots.keySet());
            assertSame(first.snapshot(), window.recoverySnapshots.get("first"));
            assertSame(last.snapshot(), window.recoverySnapshots.get("last"));
        }
    }

    @Test
    void activationFailureAfterPublicationKeepsTheStableBackingEntry() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("disk document"))) {
            class FailingActivationList extends ArrayList<TerminalEditorApp.EditorTab> {
                boolean fail;
                FailingActivationList() { super(window.tabs); }
                @Override public TerminalEditorApp.EditorTab get(int index) {
                    if (fail && index > 0) throw new OutOfMemoryError("activation failed");
                    return super.get(index);
                }
            }
            var tabs = new FailingActivationList();
            var context = new TerminalEditorApp.SessionContext(tabs, 0);
            var first = entry("first", "recovered draft");
            window.recoverySnapshots.put(first.id(), first.snapshot());
            tabs.fail = true;

            assertThrows(OutOfMemoryError.class,
                () -> window.host.restoreRecoveredTabs(List.of(first), tabs, context, window.recoveryManager, 0L));

            tabs.fail = false;
            assertEquals(2, tabs.size());
            assertEquals(first.snapshot().lines(), tabs.get(1).tabSession().model().snapshotLines());
            assertNull(window.recoveryManager.finishSession(window.host.toTabSessions(tabs), false));
            assertEquals(Set.of("first"), window.recoverySnapshots.keySet());
            assertEquals(first.snapshot().lines(), window.recoverySnapshots.get("first").lines());
            assertTrue(window.recoverySnapshots.get("first").dirty());
        }
    }

    private static RecoveryStore.Entry entry(String id, String text) {
        return new RecoveryStore.Entry(id,
            new RecoverySnapshot(List.of(text), null, null, null, true, null, null, null, null, null));
    }

    private static final class FailingAppendList extends ArrayList<TerminalEditorApp.EditorTab> {
        private final boolean partial;
        private final OutOfMemoryError failure = new OutOfMemoryError("tab publication failed");
        private FailingAppendList(List<TerminalEditorApp.EditorTab> existing, boolean partial) {
            super(existing);
            this.partial = partial;
        }
        @Override public boolean addAll(Collection<? extends TerminalEditorApp.EditorTab> additions) {
            if (partial) super.add(additions.iterator().next());
            throw failure;
        }
    }
}
