package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.TextInput;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.RecoveryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceLifecycleIntegrationTest {
    @TempDir Path directory;

    @Test
    void twoTerminalViewsShareHistoryAndKeepIndependentCursorAndLayout() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("alpha", "bravo"))) {
            var first = window.active().documentSession();
            var second = first.attachView(window.host.clipboard);
            window.context.addAll(List.of(tab(second)));
            first.navigate(new Position(0, 2), false);
            second.navigate(new Position(1, 1), false);

            assertSame(first.document(), second.document());
            assertNotSame(first.view().textLayout(), second.view().textLayout());
            first.execute(new DocumentCommand.Input("X"));
            assertEquals(List.of("alXpha", "bravo"), second.view().model().snapshotLines());
            assertEquals(new Position(1, 1), second.view().state().getCursor());

            window.context.activate(second.viewId());
            window.frame();
            window.command(TuinkCommand.EDIT_UNDO);

            assertEquals(List.of("alpha", "bravo"), first.view().model().snapshotLines());
            assertEquals(new Position(0, 3), first.view().state().getCursor());
            assertEquals(new Position(1, 1), second.view().state().getCursor());
            assertFalse(first.view().state().isDirty());
            assertSame(second.view().state(), window.context.state());
        }
    }

    @Test
    void rejectingAWorkspaceBatchPreservesMembershipSelectionAndRecoveryBytes() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("disk"))) {
            var draft = new RecoveryStore.Entry("draft", snapshot("only recovery copy"));
            window.restore(List.of(draft));
            var existing = window.active();
            var before = List.copyOf(window.tabs);
            var candidate = window.host.newEditorTab();

            assertThrows(IllegalArgumentException.class,
                () -> window.context.addAll(List.of(candidate, existing)));

            assertEquals(before, window.tabs);
            assertSame(existing, window.active());
            assertThrows(UnsupportedOperationException.class, () -> window.tabs.clear());
            assertNull(window.recoveryManager.finishSession(window.host.toDocumentSessions(window.tabs), false));
            assertEquals(Set.of("draft"), window.recoverySnapshots.keySet());
            assertEquals(draft.snapshot().lines(), window.recoverySnapshots.get("draft").lines());
        }
    }

    @Test
    void detachingTheFirstViewKeepsTheSameRecoveryEntryAndUsesTheSurvivingView() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("disk"))) {
            window.restore(List.of(new RecoveryStore.Entry("shared-recovery", snapshot("draft"))));
            var first = window.active().documentSession();
            var staleTarget = first.document().commandTarget(first.viewId());
            var second = first.attachView(window.host.clipboard);
            window.context.addNewTabAndActivate(tab(second));
            window.frame();

            window.context.remove(first.viewId());
            window.frame();
            window.event(new TextInput("latest "));

            var rejected = (DocumentCommandResult.Rejected) first.document().execute(staleTarget,
                new DocumentCommand.Input("stale "));
            assertEquals(DocumentCommandResult.RejectionReason.UNKNOWN_VIEW, rejected.reason());
            assertSame(second.view().state(), window.context.state());
            assertNull(window.recoveryManager.finishSession(window.host.toDocumentSessions(window.tabs), false));
            assertEquals(Set.of("shared-recovery"), window.recoverySnapshots.keySet());
            assertEquals(List.of("latest draft"), window.recoverySnapshots.get("shared-recovery").lines());
        }
    }

    @Test
    void aCloseSavePublishesToTheDocumentAfterItsOriginViewDetachesWithoutClosingTheOtherView() throws Exception {
        ControlledExecutor executor = new ControlledExecutor();
        EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"), executor);
        try {
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("draft "));
            var first = window.active().documentSession();
            window.command(TuinkCommand.TAB_CLOSE);
            window.event(new TextInput("y"));
            assertTrue(window.documentIo.busy());

            var second = first.attachView(window.host.clipboard);
            window.context.addAll(List.of(tab(second)));
            window.context.remove(first.viewId());
            assertEquals(1, window.tabs.size());
            assertThrows(IllegalStateException.class, () -> window.context.remove(second.viewId()));
            executor.runNextOnWorker();
            window.frame();

            assertFalse(window.documentIo.busy());
            assertEquals(1, window.tabs.size());
            assertEquals(second.viewId(), window.context.activeViewId());
            assertFalse(second.view().state().isDirty());
            assertEquals("draft original", Files.readString(window.document));
            window.event(new TextInput("after "));
            assertEquals(List.of("after draft original"), second.view().model().snapshotLines());
        } finally {
            executor.finishQueued();
            window.close();
        }
    }

    @Test
    void closingADirtySiblingViewKeepsHistoryAndRecoveryWithoutADiscardPrompt() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("draft "));
            var first = window.active().documentSession();
            String recoveryId = "dirty-shared-document";
            window.recoverySnapshots.put(recoveryId, first.view().engine().captureRecoverySnapshot());
            window.recoveryManager.publishRestoration(window.recoveryManager.prepareRestoration(List.of(
                new TerminalEditorApp.RecoveryAutosaveManager.RestoredSnapshot(first, recoveryId)), System.nanoTime()));
            var second = first.attachView(window.host.clipboard);
            window.context.addAll(List.of(tab(second)));
            window.frame();

            window.command(TuinkCommand.TAB_CLOSE);

            assertEquals(1, window.tabs.size());
            assertEquals(second.viewId(), window.context.activeViewId());
            assertTrue(second.view().state().isDirty());
            assertEquals(List.of("draft original"), second.view().model().snapshotLines());
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("original"), second.view().model().snapshotLines());
            window.command(TuinkCommand.EDIT_REDO);
            assertEquals(List.of("draft original"), second.view().model().snapshotLines());
            assertNull(window.recoveryManager.finishSession(window.host.toDocumentSessions(window.tabs), false));
            assertEquals(Set.of(recoveryId), window.recoverySnapshots.keySet());
            assertEquals(List.of("draft original"), window.recoverySnapshots.get(recoveryId).lines());
            assertEquals("original", Files.readString(window.document));
        }
    }

    private static TerminalEditorApp.EditorTab tab(TuiDocumentSession session) {
        return new TerminalEditorApp.EditorTab(session, session.newRenderPlanBuilder(line -> List.of()));
    }

    private static RecoverySnapshot snapshot(String text) {
        return new RecoverySnapshot(List.of(text), null, null, null, true, null, null, null, null, null);
    }

    private static final class ControlledExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override public void execute(Runnable task) { tasks.add(task); }

        void runNextOnWorker() throws InterruptedException {
            Thread worker = new Thread(tasks.removeFirst(), "test-workspace-writer");
            worker.start();
            worker.join(5_000L);
            assertFalse(worker.isAlive(), "detached writer did not finish");
        }

        void finishQueued() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }
    }
}
