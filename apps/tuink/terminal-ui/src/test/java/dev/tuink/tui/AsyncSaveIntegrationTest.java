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
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
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
    void quitCancelsAnUnstartedSaveBeforeAskingAboutTheDirtyDraft() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.command(TuinkCommand.APP_QUIT);
            assertTrue(window.controller.running());
            assertFalse(window.documentIo.busy());
            assertTrue(window.screenRow(window.rowStartingWith("Unsaved (1)")).contains("Discard"));
            scenario.executor.runNextOnWorker();
            window.frame();
            assertEquals("original", Files.readString(window.document));
            assertTrue(window.active().state().isDirty());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            scenario.finishOperation();
            assertTrue(window.controller.running());
            assertEquals("draft original", Files.readString(window.document));
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void quitCollectsAPublishedSaveBeforeProcessingTheExitRequest() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            scenario.executor.runNextOnWorker();
            assertEquals("draft original", Files.readString(window.document));
            assertTrue(window.active().state().isDirty(), "publication has not reached the owner yet");
            window.command(TuinkCommand.APP_QUIT);
            assertFalse(window.documentIo.busy(), "the owner collects the receipt before polling the next input");
            assertFalse(window.controller.running());
            assertEquals("draft original", Files.readString(window.document));
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void escapeCancelsTheQueuedInspectionAndItsLateTaskCannotAffectAFreshSave() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.event(new TextInput("draft "));
            var owner = window.active().tabSession();
            Path cancelledTarget = Files.writeString(directory.resolve("cancelled.txt"), "untouched");
            SaveWorkflowIntegrationTest.submitFilename(window, cancelledTarget);
            assertTrue(window.documentIo.busy());

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));

            assertFalse(window.documentIo.busy());
            assertTrue(owner.state().isDirty());
            assertEquals(window.document, JvmDocumentStorage.pathOf(owner.state().getCurrentResource()));
            assertEquals(List.of("draft original"), owner.model().snapshotLines());
            assertEquals("original", Files.readString(window.document));
            assertEquals("untouched", Files.readString(cancelledTarget));

            Path freshTarget = directory.resolve("fresh.txt");
            SaveWorkflowIntegrationTest.submitFilename(window, freshTarget);
            assertTrue(window.documentIo.busy());
            scenario.executor.runNextOnWorker();
            window.frame();

            assertTrue(window.documentIo.busy(), "the cancelled task must not release the newer operation");
            assertFalse(window.active().uiState().modalState.isSaveOverwriteConfirm());
            assertFalse(Files.exists(freshTarget));
            assertEquals("untouched", Files.readString(cancelledTarget));
            scenario.finishOperation();

            assertEquals("draft original", Files.readString(freshTarget));
            assertEquals("untouched", Files.readString(cancelledTarget));
            assertEquals("original", Files.readString(window.document));
            assertEquals(freshTarget, JvmDocumentStorage.pathOf(owner.state().getCurrentResource()));
            assertFalse(owner.state().isDirty());
            assertTrue(window.controller.running());
        }
    }

    @Test
    void saveProgressAndEscapeCancellationStayWithTheirDocumentTab() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.event(new TextInput("draft "));
            var owner = window.active().tabSession();
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.frame();
            assertTrue(window.notification.message.contains("Esc to cancel"));
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("other draft"));
            var other = window.active().tabSession();
            window.frame();
            assertTrue(window.notification.message == null || !window.notification.message.contains("Esc to cancel"));

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.documentIo.busy(), "another tab's Esc cannot cancel the owner's save");
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.frame();
            assertTrue(window.notification.message.contains("Esc to cancel"));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(window.documentIo.busy());
            assertTrue(owner.state().isDirty());
            scenario.executor.runNextOnWorker();
            window.frame();
            assertEquals("original", Files.readString(window.document));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            scenario.finishOperation();
            assertEquals("draft original", Files.readString(window.document));
            assertFalse(owner.state().isDirty());
            assertTrue(other.state().isDirty());
            assertEquals(List.of("other draft"), other.model().snapshotLines());
        }
    }

    @Test
    void searchKeepsEscapeUntilItClosesAndTheSaveHintMatchesThatPriority() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.command(TuinkCommand.SEARCH_OPEN);
            window.frame();
            assertTrue(window.selected().uiState().searchMode);
            assertFalse(window.notification.message.contains("Esc to cancel"));

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.frame();
            assertFalse(window.selected().uiState().searchMode);
            assertTrue(window.documentIo.busy(), "the first Esc closes search");
            assertTrue(window.notification.message.contains("Esc to cancel"));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(window.documentIo.busy());
            assertTrue(window.active().state().isDirty());
            scenario.executor.runNextOnWorker();
            window.frame();
            assertEquals("original", Files.readString(window.document));
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void backgroundSaveProgressPreservesPriorityAlertsAndCancellationStillWorks(boolean warning) throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            String alert = "Keep this operation warning visible";
            if (warning) window.notification.warning(alert);
            else window.notification.error(alert);

            window.frame();
            assertEquals(alert, window.notification.message);
            assertTrue(window.documentIo.busy());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(window.documentIo.busy());
            assertTrue(window.active().state().isDirty());
            scenario.executor.runNextOnWorker();
            window.frame();
            assertEquals("original", Files.readString(window.document));
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test
    void aLargeOverwriteTargetCanBeConfirmedAndSaved() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.event(new TextInput("draft "));
            var owner = window.active().tabSession();
            var cursor = owner.state().getCursor();
            Path target = directory.resolve("large-target.bin");
            long targetSize = 128L << 20;
            try (RandomAccessFile file = new RandomAccessFile(target.toFile(), "rw")) {
                file.setLength(targetSize);
            }
            SaveWorkflowIntegrationTest.submitFilename(window, target);
            scenario.executor.runNextOnWorker();
            window.frame();

            assertFalse(window.documentIo.busy());
            assertTrue(window.active().documentSession().document().io().busy(), "approval retains the observation");
            assertTrue(window.active().uiState().modalState.isSaveOverwriteConfirm());
            assertEquals(targetSize, Files.size(target));
            assertEquals("original", Files.readString(window.document));
            assertEquals(window.document, JvmDocumentStorage.pathOf(owner.state().getCurrentResource()));
            assertEquals(cursor, owner.state().getCursor());
            assertEquals(List.of("draft original"), owner.model().snapshotLines());
            assertTrue(owner.state().isDirty());

            window.event(new TextInput("y"));
            scenario.finishOperation();

            assertEquals("draft original", Files.readString(target));
            assertEquals("original", Files.readString(window.document));
            assertEquals(target, JvmDocumentStorage.pathOf(owner.state().getCurrentResource()));
            assertFalse(owner.state().isDirty());
            assertTrue(window.controller.running());
        }
    }

    @Test
    void quitCancelsTheQueuedInspectionAndCancellingQuitStillAllowsASave() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("draft "));
            var owner = window.active().tabSession();
            Path target = Files.writeString(directory.resolve("cancelled.txt"), "untouched");
            SaveWorkflowIntegrationTest.submitFilename(window, target);

            window.event(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL));

            assertFalse(window.documentIo.busy(), "quit must not wait for a read-only inspection");
            assertTrue(window.controller.running(), "unsaved work still requires the ordinary quit decision");
            assertTrue(window.screenRow(window.rowStartingWith("Unsaved (1)")).contains("Discard"));
            assertTrue(owner.state().isDirty());
            assertEquals("untouched", Files.readString(target));
            assertEquals("original", Files.readString(window.document));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            scenario.executor.runNextOnWorker();
            window.frame();

            assertFalse(window.documentIo.busy());
            assertTrue(window.controller.running());
            assertFalse(window.active().uiState().modalState.isSaveOverwriteConfirm());
            assertEquals(List.of("draft original"), owner.model().snapshotLines());
            assertEquals(window.document, JvmDocumentStorage.pathOf(owner.state().getCurrentResource()));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            scenario.finishOperation();

            assertEquals("draft original", Files.readString(window.document));
            assertEquals("untouched", Files.readString(target));
            assertFalse(owner.state().isDirty());
            assertTrue(window.controller.running(), "the cancelled quit cannot resume after a later save");
        }
    }

    @Test
    void quitWithoutConfirmationDoesNotWaitForAQueuedInspection() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = false;
            window.event(new TextInput("draft "));
            Path target = Files.writeString(directory.resolve("cancelled.txt"), "untouched");
            SaveWorkflowIntegrationTest.submitFilename(window, target);

            window.event(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL));

            assertFalse(window.documentIo.busy());
            assertFalse(window.controller.running());
            scenario.executor.runNextOnWorker();
            assertEquals("untouched", Files.readString(target));
            assertEquals("original", Files.readString(window.document));
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            assertTrue(window.active().state().isDirty());
        }
    }

    @Test
    void quitCancelsAnUnstartedSaveQueuedBehindAnInspectionThatIgnoresInterruption() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("draft "));
            var owner = window.active().tabSession();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger readCompletions = new AtomicInteger();
            window.documentIo.submitSave(() -> {
                started.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        release.await();
                        released = true;
                    } catch (InterruptedException ignored) {
                        // Simulate a provider whose read does not promptly observe cancellation.
                    }
                }
                return "late inspection";
            }, completion -> readCompletions.incrementAndGet());
            Thread reader = new Thread(scenario.executor.tasks.removeFirst(), "test-unresponsive-inspection");
            reader.start();
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertTrue(window.documentIo.cancelSave());
                window.command(TuinkCommand.DOCUMENT_SAVE);
                assertTrue(window.documentIo.busy());
                assertTrue(window.active().documentSession().document().io().busy());

                window.event(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL));

                assertFalse(window.documentIo.busy());
                assertFalse(window.active().documentSession().document().io().busy());
                assertTrue(window.controller.running());
                assertTrue(window.screenRow(window.rowStartingWith("Unsaved (1)")).contains("Discard"));
                assertTrue(owner.state().isDirty());
                assertEquals(List.of("draft original"), owner.model().snapshotLines());
                assertEquals(window.document, JvmDocumentStorage.pathOf(owner.state().getCurrentResource()));
                assertEquals("original", Files.readString(window.document));
                window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            } finally {
                release.countDown();
                reader.join(5_000L);
                assertFalse(reader.isAlive(), "the released inspection must finish");
            }
            scenario.executor.runNextOnWorker();
            window.frame();

            assertFalse(window.documentIo.busy());
            assertEquals(0, readCompletions.get(), "the abandoned read must never publish a late result");
            assertTrue(owner.state().isDirty());
            assertEquals("original", Files.readString(window.document));
            assertTrue(window.controller.running());
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
                new TerminalEditorApp.RecoveryAutosaveManager.RestoredSnapshot(window.active().documentSession(), recoveryId)), System.nanoTime()));
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
                () -> window.host.toDocumentSessions(window.tabs), warnings::add);

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
            assertTrue(window.documentIo.busy(), "The conflict starts a fresh detached target inspection");
            scenario.finishOperation();
            assertTrue(window.selected().uiState().modalState.isSaveOverwriteConfirm());
            assertTrue(window.selected().uiState().overwriteApproval.request().externalOverwrite());
            assertTrue(window.active().state().isDirty());
            assertEquals("external", Files.readString(window.document));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
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
    void repeatedQuitCancelsPreparationBeforeTheOrdinaryUnsavedDecision() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("named "));
            window.command(TuinkCommand.APP_QUIT);
            window.event(new TextInput("y"));
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("new draft"));
            window.command(TuinkCommand.APP_QUIT);
            assertFalse(window.documentIo.busy());
            assertTrue(window.controller.running());
            assertTrue(window.screenRow(window.rowStartingWith("Unsaved (2)")).contains("Discard"));
            scenario.executor.runNextOnWorker();
            window.frame();
            assertEquals("original", Files.readString(window.document));
            assertTrue(window.tabs.stream().allMatch(tab -> tab.state().isDirty()));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.command(TuinkCommand.APP_QUIT);
            window.event(new TextInput("y"));

            Path target = directory.resolve("new-draft.txt");
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(target.toString()));
            assertEquals(target.toString(), window.filename.text());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(window.documentIo.busy());
            scenario.executor.runNextOnWorker();
            window.frame();
            scenario.finishOperation();
            assertFalse(window.controller.running());
            assertEquals("new draft", Files.readString(target));
            assertEquals("named original", Files.readString(window.document));
        }
    }

    @Test
    void repeatedQuitAfterPublicationKeepsTheNextUnnamedDocumentContinuation() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("named "));
            window.command(TuinkCommand.APP_QUIT);
            window.event(new TextInput("y"));
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("new draft"));

            // Publication may finish after prepareTurn and before the next input is read.
            window.controller.prepareTurn();
            scenario.executor.runNextOnWorker();
            window.controller.handlePoll(new TuiEventLoop.Poll<>(
                KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL), false, false));
            window.frame();
            assertTrue(window.active().uiState().modalState.isSaveAs());
            assertTrue(window.controller.running());
            Path target = directory.resolve("new-draft.txt");
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(target.toString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            scenario.finishOperation();

            assertFalse(window.controller.running());
            assertEquals("new draft", Files.readString(target));
            assertEquals("named original", Files.readString(window.document));
        }
    }

    @Test
    void theTabBarsCloseCellCancelsPreparationBeforeAskingAboutAnInactiveDirtyDocument() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.showTabBarSetting = true;
            window.ui.confirmOnCloseSetting = true;
            window.ui.mouseModeSetting = true;
            window.mouseAvailable = true;
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
            assertFalse(window.documentIo.busy());
            assertTrue(window.tabs.getFirst().uiState().modalState.isCloseTabConfirm());
            scenario.executor.runNextOnWorker();
            window.frame();
            assertTrue(saved.state().isDirty());
            assertEquals("original", Files.readString(window.document));
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            scenario.finishOperation();
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
            assertSame(second, window.active().tabSession());
            assertEquals(secondTarget.toString(), window.filename.text());
            assertTrue(window.active().uiState().modalState.isSaveAs());
            window.command(TuinkCommand.TAB_PREVIOUS);
            assertSame(first, window.active().tabSession());
            assertEquals(firstTarget.toString(), window.filename.text());
            assertTrue(window.active().uiState().modalState.isSaveOverwriteConfirm());
            assertTrue(first.state().isDirty());
            assertTrue(second.state().isDirty());
            assertEquals("created externally", Files.readString(firstTarget));
            assertFalse(Files.exists(secondTarget));

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.TAB_NEXT);
            assertSame(second, window.active().tabSession());
            assertTrue(window.active().uiState().modalState.isSaveAs());
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
    void cancelledExternalOverwriteCancelsTheDeferredQuitEvenWhenCloseConfirmationIsDisabled() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.APP_QUIT);
            window.event(new TextInput("y"));
            assertTrue(window.documentIo.busy());
            window.ui.confirmOnCloseSetting = false;
            Files.writeString(window.document, "external");
            scenario.executor.runNextOnWorker();
            window.frame();

            assertTrue(window.controller.running());
            scenario.finishOperation();
            assertFalse(window.documentIo.busy());
            assertTrue(window.active().state().isDirty());
            assertTrue(java.util.stream.IntStream.range(0, window.height).mapToObj(window::screenRow)
                .anyMatch(line -> line.contains("External changes will be destroyed")));
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            assertEquals("external", Files.readString(window.document));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.controller.running());
            window.event(new TextInput("after "));
            assertEquals(List.of("draft after original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test
    void aRetiredInitiatingViewAcceptsItsReceiptWithoutHandingItsUiToTheSurvivingView() throws Exception {
        try (Scenario scenario = scenario()) {
            var window = scenario.window;
            window.event(new TextInput("draft "));
            var original = window.active();
            var document = original.documentSession().document();
            var attached = original.documentSession().attachView(window.host.clipboard);
            var surviving = new TerminalEditorApp.EditorTab(attached,
                attached.newRenderPlanBuilder(window.host.resolveHighlighter(window.document)));
            window.context.addAll(List.of(surviving));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            scenario.executor.runNextOnWorker();
            String oldStatus = original.uiState().notification.message;
            window.context.remove(original.documentSession().viewId());
            original.uiState().close();
            window.context.activatePresentation(surviving.presentationId());
            surviving.uiState().saveAsInput.set("independent.md");
            surviving.uiState().filenameRevision++;
            surviving.uiState().modalState = dev.tuink.tui.flow.UiModalState.saveAs();
            surviving.uiState().notification = window.host.new NotificationState();
            surviving.uiState().notification.warning("Independent alert");
            window.frame();
            assertFalse(document.ownsView(original.documentSession().viewId()));
            assertFalse(document.io().busy());
            assertFalse(surviving.state().isDirty());
            assertEquals("draft original", Files.readString(window.document));
            assertEquals(oldStatus, original.uiState().notification.message);
            assertEquals("independent.md", surviving.uiState().saveAsInput.text());
            assertTrue(surviving.uiState().modalState.isSaveAs());
            assertEquals("Independent alert", surviving.uiState().notification.message);
            assertEquals(surviving.presentationId(), window.selected().presentationId());
        }
    }

    @Test
    void aNewOwnerAlertSurvivesAnOlderStandaloneSaveReceipt() throws Exception {
        try (Scenario scenario = scenario()) {
            var window = scenario.window;
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.active().uiState().notification.warning("New independent alert");
            scenario.executor.runNextOnWorker(); window.frame();
            assertFalse(window.active().state().isDirty());
            assertEquals("New independent alert", window.active().uiState().notification.message);
            assertEquals("draft original", Files.readString(window.document));
        }
    }

    @Test
    void aCancelledAttemptCannotReleaseTheNewSaveSlotWhenItsQueuedWorkerRunsLater() throws Exception {
        try (Scenario scenario = scenario()) {
            var window = scenario.window;
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(window.documentIo.busy());
            window.command(TuinkCommand.DOCUMENT_SAVE);
            assertTrue(window.documentIo.busy());
            scenario.executor.runNextOnWorker(); // cancelled preparation from the first attempt
            window.frame();
            assertTrue(window.documentIo.busy());
            assertTrue(window.active().state().isDirty());
            assertEquals("original", Files.readString(window.document));
            scenario.executor.runNextOnWorker();
            window.frame();
            assertFalse(window.documentIo.busy());
            assertFalse(window.active().state().isDirty());
            assertEquals("draft original", Files.readString(window.document));
        }
    }

    @Test
    void cancellingTheGlobalFilenamePromptLeavesLaterLocalSavesIndependentOfQuit() throws Exception {
        try (Scenario scenario = scenario()) {
            var window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("unnamed draft"));
            window.command(TuinkCommand.APP_QUIT);
            window.event(new TextInput("y"));
            assertTrue(window.selected().uiState().modalState.isSaveAs());
            assertEquals(dev.tuink.tui.flow.SaveContinuation.QUIT,
                window.selected().uiState().modalState.saveContinuation());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.controller.running());
            assertTrue(window.selected().uiState().modalState.isNone());
            Path target = directory.resolve("local-after-cancel.txt");
            SaveWorkflowIntegrationTest.submitFilename(window, target);
            scenario.finishOperation();
            assertEquals("unnamed draft", Files.readString(target));
            assertTrue(window.controller.running(), "a cancelled close request cannot resume through a local save");
            assertTrue(window.selected().uiState().modalState.isNone());
        }
    }

    @Test
    void globalSaveAndQuitContinuesAfterItsInitiatingViewRetires() throws Exception {
        try (Scenario scenario = scenario()) {
            var window = scenario.window;
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("draft "));
            var original = window.active();
            var attached = original.documentSession().attachView(window.host.clipboard);
            var surviving = new TerminalEditorApp.EditorTab(attached,
                attached.newRenderPlanBuilder(window.host.resolveHighlighter(window.document)));
            window.context.addAll(List.of(surviving));
            window.command(TuinkCommand.APP_QUIT);
            window.event(new TextInput("y"));
            scenario.executor.runNextOnWorker();
            window.context.remove(original.documentSession().viewId());
            original.uiState().close();
            window.context.activatePresentation(surviving.presentationId());
            surviving.uiState().notification = window.host.new NotificationState();
            surviving.uiState().notification.warning("Independent local warning");
            window.frame();
            assertFalse(window.controller.running());
            assertFalse(surviving.state().isDirty());
            assertEquals(surviving.presentationId(), window.selected().presentationId());
            assertEquals("Independent local warning", surviving.uiState().notification.message);
            assertEquals("draft original", Files.readString(window.document));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aNewAlertSurvivesBothAutomaticSaveAsPhases(boolean error) throws Exception {
        try (Scenario scenario = scenario()) {
            var window = scenario.window;
            window.event(new TextInput("draft "));
            Path target = directory.resolve("alert-copy.txt");
            SaveWorkflowIntegrationTest.submitFilename(window, target);
            var owner = window.selected().uiState();
            String message = error ? "New independent error" : "New independent warning";
            if (error) owner.notification.error(message); else owner.notification.warning(message);
            scenario.executor.runNextOnWorker(); window.frame();
            assertTrue(window.documentIo.busy(), "inspection must start its separate write");
            assertEquals(message, owner.notification.message, "automatic write progress must retain the newer alert");
            scenario.executor.runNextOnWorker(); window.frame();
            assertFalse(window.documentIo.busy());
            assertEquals("draft original", Files.readString(target));
            assertFalse(window.active().state().isDirty());
            assertTrue(owner.saveAsInput.isEmpty());
            assertEquals(message, owner.notification.message, "the write receipt keeps the inspection's original notice stamp");
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
