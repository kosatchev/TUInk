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

            var result = window.host.restoreRecoveredTabs(List.of(first, failing, last),
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
            assertNull(window.recoveryManager.finishSession(window.host.toDocumentSessions(window.tabs), false));
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
                () -> window.host.restoreRecoveredTabs(entries, window.context, window.recoveryManager, 0L)));

            assertEquals(List.of(existing), window.tabs);
            assertNull(window.recoveryManager.finishSession(window.host.toDocumentSessions(window.tabs), false));
            assertEquals(Set.of("first"), window.recoverySnapshots.keySet());
            assertSame(first.snapshot(), window.recoverySnapshots.get("first"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateRecoveryIdentityCannotPublishAnyDraftOrRemoveTheExistingEditor(boolean placeholder) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("disk document"))) {
            if (placeholder) window.context.replace(0, window.host.newEditorTab());
            var existing = window.active();
            var selected = window.context.activeViewId();
            var first = entry("first", "first draft");
            var last = entry("last", "last draft");
            window.recoverySnapshots.put(first.id(), first.snapshot());
            window.recoverySnapshots.put(last.id(), last.snapshot());

            assertThrows(IllegalArgumentException.class,
                () -> window.host.restoreRecoveredTabs(List.of(first, last, first),
                    window.context, window.recoveryManager, 0L));

            assertEquals(List.of(existing), window.tabs);
            assertEquals(selected, window.context.activeViewId());
            assertSame(existing.tabSession().engine(), window.context.engine());
            assertNull(window.recoveryManager.finishSession(window.host.toDocumentSessions(window.tabs), false));
            assertEquals(Set.of("first", "last"), window.recoverySnapshots.keySet());
            assertSame(first.snapshot(), window.recoverySnapshots.get("first"));
            assertSame(last.snapshot(), window.recoverySnapshots.get("last"));
        }
    }

    @Test
    void anEntryAlreadyOwnedByALiveDocumentCannotBeAdoptedByAnotherDraft() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("disk document"))) {
            var first = entry("first", "recovered draft");
            window.restore(List.of(first));
            var live = window.active();
            live.documentSession().execute(new dev.tuink.editor.command.DocumentCommand.Input("latest "));
            var previousTabs = List.copyOf(window.tabs);

            assertThrows(IllegalArgumentException.class,
                () -> window.host.restoreRecoveredTabs(List.of(first), window.context, window.recoveryManager, 0L));

            assertEquals(previousTabs, window.tabs);
            assertSame(live, window.active());
            assertNull(window.recoveryManager.finishSession(window.host.toDocumentSessions(window.tabs), false));
            assertEquals(Set.of("first"), window.recoverySnapshots.keySet());
            assertEquals(List.of("latest recovered draft"), window.recoverySnapshots.get("first").lines());
            assertTrue(window.recoverySnapshots.get("first").dirty());
        }
    }

    @Test
    void replacingThePlaceholderRetainsPublishedDraftOwnershipAfterSelectionChanges() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("disk document"))) {
            var placeholder = window.host.newEditorTab();
            window.context.replace(0, placeholder);
            var first = entry("first", "recovered draft");
            window.recoverySnapshots.put(first.id(), first.snapshot());

            window.host.restoreRecoveredTabs(List.of(first), window.context, window.recoveryManager, 0L);

            assertEquals(1, window.tabs.size());
            var restored = window.active();
            assertTrue(window.context.indexOf(placeholder.documentSession().viewId()) < 0);
            window.context.addNewTabAndActivate(window.host.newEditorTab());
            window.context.activate(restored.documentSession().viewId());
            restored.documentSession().execute(new dev.tuink.editor.command.DocumentCommand.Input("latest "));
            assertNull(window.recoveryManager.finishSession(window.host.toDocumentSessions(window.tabs), false));
            assertEquals(Set.of("first"), window.recoverySnapshots.keySet(), "published draft retains its stable entry");
            assertEquals(List.of("latest recovered draft"), window.recoverySnapshots.get("first").lines());
        }
    }

    private static RecoveryStore.Entry entry(String id, String text) {
        return new RecoveryStore.Entry(id,
            new RecoverySnapshot(List.of(text), null, null, null, true, null, null, null, null, null));
    }

}
