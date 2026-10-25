package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.runtime.TuiEventLoop;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AsyncSaveIntegrationTest {
    @TempDir Path directory;

    @Test
    void aQueuedSaveLocksOnlyItsDocumentAndPublishesTheResultOnTheOwnerTurn() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.event(new TextInput("draft "));
            var saved = window.active().tabSession();
            window.command(TuinkCommand.DOCUMENT_SAVE);
            assertTrue(window.documentIo.busy());
            assertEquals("original", Files.readString(window.document));
            window.event(new TextInput("ignored"));
            window.event(new PasteEvent("ignored", false));
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("draft original"), saved.model().snapshotLines());
            var cursor = saved.state().getCursor();
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            assertFalse(cursor.equals(saved.state().getCursor()));
            window.command(TuinkCommand.TAB_CLOSE);
            assertEquals(1, window.tabs.size());

            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("other draft"));
            var other = window.active().tabSession();
            scenario.executor.runNextOnWorker();
            assertEquals("draft original", Files.readString(window.document));
            assertTrue(saved.state().isDirty(), "worker completion cannot mutate the live document");
            window.frame();
            assertFalse(window.documentIo.busy());
            assertFalse(saved.state().isDirty());
            assertTrue(other.state().isDirty());
            assertSame(other, window.active().tabSession());
            assertEquals(List.of("other draft"), other.model().snapshotLines());
        }
    }

    @Test
    void saveAsRefreshesTheSavedTabsHighlighterWithoutActivatingIt() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.event(new TextInput("draft "));
            var saved = window.active().tabSession();
            var previousBuilder = window.context.renderPlanBuilder();
            Path target = directory.resolve("saved.java");
            SaveWorkflowIntegrationTest.submitFilename(window, target);
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("other"));
            var other = window.active().tabSession();
            var otherBuilder = window.context.renderPlanBuilder();

            scenario.executor.runNextOnWorker();
            assertEquals(window.document, JvmDocumentStorage.pathOf(saved.state().getCurrentResource()));
            assertTrue(saved.state().isDirty());
            window.frame();
            scenario.finishOperation();

            assertSame(other, window.active().tabSession());
            assertSame(otherBuilder, window.context.renderPlanBuilder());
            assertEquals(target, JvmDocumentStorage.pathOf(saved.state().getCurrentResource()));
            assertFalse(saved.state().isDirty());
            assertNotSame(previousBuilder, window.tabs.getFirst().renderPlanBuilder());
            assertSame(saved.textLayout(), window.tabs.getFirst().renderPlanBuilder().textLayout());
            assertEquals("", window.filename.text());
            assertEquals("draft original", Files.readString(target));
        }
    }

    @Test
    void closeContinuationTracksTheSavedDocumentWhenAnEarlierTabClosesDuringTheWrite() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("second"));
            var saved = window.active().tabSession();
            window.command(TuinkCommand.TAB_CLOSE);
            window.event(new TextInput("y"));
            Path target = directory.resolve("second.txt");
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(target.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(window.documentIo.busy());

            window.command(TuinkCommand.TAB_PREVIOUS);
            window.command(TuinkCommand.TAB_CLOSE);
            assertEquals(1, window.tabs.size());
            assertSame(saved, window.active().tabSession());
            scenario.executor.runNextOnWorker();
            window.frame();
            scenario.finishOperation();

            assertEquals("second", Files.readString(target));
            assertEquals(1, window.tabs.size());
            assertNotSame(saved, window.active().tabSession());
            assertFalse(window.active().state().isDirty());
            assertTrue(window.controller.running());
        }
    }

    @Test
    void quitWaitsForAnAlreadyStartedSave() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.command(TuinkCommand.APP_QUIT);
            assertTrue(window.controller.running());
            scenario.executor.runNextOnWorker();
            assertTrue(window.controller.running());
            window.frame();
            assertFalse(window.controller.running());
            assertEquals("draft original", Files.readString(window.document));
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void completingTheFinalQuitSaveStopsTheRuntimeBeforeAnotherEditCanBeRead() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("draft "));
            String recoveryId = "quit-save-draft";
            var saved = window.active().tabSession();
            window.recoverySnapshots.put(recoveryId, saved.engine().captureRecoverySnapshot());
            window.recoveryManager.publishRestoration(window.recoveryManager.prepareRestoration(List.of(
                new TerminalEditorApp.RecoveryAutosaveManager.RestoredSnapshot(saved, recoveryId)), System.nanoTime()));
            assertTrue(window.recoverySnapshots.containsKey(recoveryId));
            window.command(TuinkCommand.APP_QUIT);
            window.event(new TextInput("y"));
            assertTrue(window.documentIo.busy());
            scenario.executor.runNextOnWorker();
            assertEquals("draft original", Files.readString(window.document));
            assertTrue(window.controller.running(), "the owner has not collected the final save yet");
            assertTrue(window.active().state().isDirty());

            AtomicInteger inputPolls = new AtomicInteger();
            TuiEventLoop<TuiInputEvent> runtime = new TuiEventLoop<>(timeout -> {
                inputPolls.incrementAndGet();
                return TuiEventLoop.Read.event(new TextInput("late "));
            });
            List<String> warnings = new ArrayList<>();
            TerminalEditorApp.runWithRecovery(runtime, window.controller, window.recoveryManager,
                () -> window.host.toTabSessions(window.tabs), warnings::add);

            assertEquals(0, inputPolls.get(), "quit completed during prepareTurn; input must not be polled");
            assertFalse(window.controller.running());
            assertFalse(window.documentIo.busy());
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            assertEquals("draft original", Files.readString(window.document));
            assertFalse(window.active().state().isDirty());
            assertFalse(window.recoverySnapshots.containsKey(recoveryId));
            assertTrue(window.recoverySnapshots.isEmpty(), "a confirmed exit cleans the saved draft's recovery");
            assertTrue(warnings.isEmpty());
        }
    }

    @Test
    void quitWritesNamedTabsInSequenceAndWaitsForEveryOwnerCompletion() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("first "));
            var first = window.active().tabSession();
            window.command(TuinkCommand.TAB_NEW);
            Path secondPath = Files.writeString(directory.resolve("second.txt"), "original");
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(secondPath));
            window.frame();
            window.event(new TextInput("second "));
            var second = window.active().tabSession();
            window.command(TuinkCommand.APP_QUIT);
            window.event(new TextInput("y"));
            scenario.executor.runNextOnWorker();
            window.frame();
            assertFalse(first.state().isDirty());
            assertTrue(second.state().isDirty());
            assertTrue(window.documentIo.busy());
            assertTrue(window.controller.running());
            scenario.executor.runNextOnWorker();
            window.frame();
            assertFalse(second.state().isDirty());
            assertFalse(window.controller.running());
            assertEquals("first original", Files.readString(window.document));
            assertEquals("second original", Files.readString(secondPath));
        }
    }

    @Test
    void anExternalConflictKeepsTheDraftAndReleasesTheEditLock() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            Files.writeString(window.document, "external");
            scenario.executor.runNextOnWorker();
            window.frame();
            assertFalse(window.documentIo.busy());
            assertTrue(window.active().state().isDirty());
            assertTrue(window.notification.message.startsWith("Save failed:"));
            assertEquals("external", Files.readString(window.document));
            window.event(new TextInput("after "));
            assertEquals(List.of("draft after original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aStandaloneSavePreservesHelpOpenedWhileTheWriteIsPending(boolean saveAs) throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.event(new TextInput("draft "));
            if (saveAs) {
                SaveWorkflowIntegrationTest.submitFilename(window, directory.resolve("saved.txt"));
            } else {
                window.command(TuinkCommand.DOCUMENT_SAVE);
            }
            window.command(TuinkCommand.HELP_KEY_HINTS);
            scenario.executor.runNextOnWorker();
            window.frame();
            scenario.finishOperation();
            window.event(new TextInput("ignored while Help is open"));
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.event(new TextInput("after "));
            assertEquals(List.of("draft after original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aStandaloneSavePreservesAnotherTabsSaveAsModalAndEditedFilename(boolean saveAs) throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("draft "));
            if (saveAs) {
                SaveWorkflowIntegrationTest.submitFilename(window, directory.resolve("saved.txt"));
            } else {
                window.command(TuinkCommand.DOCUMENT_SAVE);
            }
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("other"));
            window.command(TuinkCommand.TAB_CLOSE);
            window.event(new TextInput("y"));
            Path otherTarget = directory.resolve("other.txt");
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(otherTarget.toString()));
            scenario.executor.runNextOnWorker();
            window.frame();
            scenario.finishOperation();

            assertEquals(otherTarget.toString(), window.filename.text());
            assertFalse(Files.exists(otherTarget));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(window.documentIo.busy());
            scenario.executor.runNextOnWorker();
            window.frame();
            scenario.finishOperation();
            assertEquals("other", Files.readString(otherTarget));
            assertEquals(1, window.tabs.size());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void aStandaloneSavePreservesAnotherTabsCloseConfirmation() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            var saved = window.active().tabSession();
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("other"));
            window.command(TuinkCommand.TAB_CLOSE);
            scenario.executor.runNextOnWorker();
            window.frame();
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(1, window.tabs.size());
            assertSame(saved, window.active().tabSession());
        }
    }

    @Test
    void repeatedQuitDuringAQuitSaveKeepsTheNextUnnamedDocumentContinuation() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("named "));
            window.command(TuinkCommand.APP_QUIT);
            window.event(new TextInput("y"));
            window.command(TuinkCommand.APP_QUIT);
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("new draft"));
            scenario.executor.runNextOnWorker();
            window.frame();

            Path target = directory.resolve("new-draft.txt");
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(target.toString()));
            assertEquals(target.toString(), window.filename.text());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(window.documentIo.busy());
            window.command(TuinkCommand.APP_QUIT);
            scenario.executor.runNextOnWorker();
            window.frame();
            scenario.finishOperation();
            assertFalse(window.controller.running());
            assertEquals("new draft", Files.readString(target));
            assertEquals("named original", Files.readString(window.document));
        }
    }

    @Test
    void theTabBarsCloseCellCannotCloseAnInactiveDocumentBeingSaved() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.showTabBarSetting = true;
            window.ui.mouseModeSetting = true;
            window.ui.mouseModeAvailable = true;
            window.event(new TextInput("draft "));
            var saved = window.active().tabSession();
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.command(TuinkCommand.TAB_NEW);
            int closeColumn = window.screenRow(1).indexOf(" x]") + 1;
            assertTrue(closeColumn > 0);
            window.pointer(PointerEvent.Kind.PRESS, 1, closeColumn);
            window.pointer(PointerEvent.Kind.RELEASE, 1, closeColumn);
            assertEquals(2, window.tabs.size());
            assertSame(saved, window.tabs.getFirst().tabSession());
            scenario.executor.runNextOnWorker();
            window.frame();
            assertFalse(saved.state().isDirty());
        }
    }

    @Test
    void aFailedSaveAsRestoresItsOwnFilenameAndRemembersTheOtherTabsDraft() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("first "));
            var first = window.active().tabSession();
            Path firstTarget = directory.resolve("first.txt");
            SaveWorkflowIntegrationTest.submitFilename(window, firstTarget);
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("second"));
            var second = window.active().tabSession();
            window.command(TuinkCommand.TAB_CLOSE);
            window.event(new TextInput("y"));
            Path secondTarget = directory.resolve("second.txt");
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(secondTarget.toString()));

            Files.writeString(firstTarget, "created externally");
            scenario.executor.runNextOnWorker();
            window.frame();
            assertSame(first, window.active().tabSession());
            assertEquals(firstTarget.toString(), window.filename.text());
            assertTrue(first.state().isDirty());
            assertTrue(second.state().isDirty());
            assertEquals("created externally", Files.readString(firstTarget));
            assertFalse(Files.exists(secondTarget));

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.TAB_NEXT);
            window.command(TuinkCommand.TAB_CLOSE);
            window.event(new TextInput("y"));
            assertEquals(secondTarget.toString(), window.filename.text());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            scenario.executor.runNextOnWorker();
            window.frame();
            scenario.finishOperation();
            assertEquals("second", Files.readString(secondTarget));
            assertEquals(1, window.tabs.size());
            assertSame(first, window.active().tabSession());
            assertEquals(List.of("first original"), first.model().snapshotLines());
            assertTrue(first.state().isDirty());
        }
    }

    @Test
    void aFailedWriteCancelsTheDeferredQuitEvenWhenCloseConfirmationIsDisabled() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = false;
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.command(TuinkCommand.APP_QUIT);
            Files.writeString(window.document, "external");
            scenario.executor.runNextOnWorker();
            window.frame();

            assertTrue(window.controller.running());
            assertFalse(window.documentIo.busy());
            assertTrue(window.active().state().isDirty());
            assertTrue(window.notification.message.startsWith("Save failed:"));
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            assertEquals("external", Files.readString(window.document));
            window.event(new TextInput("after "));
            assertEquals(List.of("draft after original"), window.active().tabSession().model().snapshotLines());
        }
    }

    private Scenario scenario() throws Exception {
        ControlledExecutor executor = new ControlledExecutor();
        return new Scenario(new EditorWindowFixture(directory, false, List.of("original"), executor), executor);
    }

    private record Scenario(EditorWindowFixture window, ControlledExecutor executor) implements AutoCloseable {
        void finishOperation() throws InterruptedException {
            for (int remaining = 32; window.documentIo.busy() && remaining > 0; remaining--) {
                if (!executor.tasks.isEmpty()) executor.runNextOnWorker();
                window.frame();
            }
            assertFalse(window.documentIo.busy(), "detached save lifecycle did not finish");
        }

        @Override public void close() {
            // Complete queued work even after an assertion failure, before the owner drains it.
            executor.finishQueued();
            window.close();
        }
    }

    private static final class ControlledExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override public void execute(Runnable task) {
            tasks.add(task);
        }

        void runNextOnWorker() throws InterruptedException {
            Runnable task = tasks.removeFirst();
            Thread worker = new Thread(task, "test-document-io");
            worker.start();
            worker.join(5_000L);
            assertFalse(worker.isAlive(), "detached writer did not finish");
        }

        void finishQueued() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }
    }
}
