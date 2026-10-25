package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SaveAsTargetApprovalIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aliasChangesDuringConfirmationOrAfterApprovalCannotOverwriteAnotherFile(boolean afterApproval) throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            Path approved = Files.writeString(directory.resolve("approved.txt"), "approved original");
            Path foreign = Files.writeString(directory.resolve("foreign.txt"), "foreign original");
            Path alias = Files.createSymbolicLink(directory.resolve("alias.txt"), approved);
            window.event(new TextInput("draft "));
            SaveWorkflowIntegrationTest.submitFilename(window, alias);
            assertTrue(window.documentIo.busy());
            assertEquals(1, scenario.executor.tasks.size(), "only read-only preflight may be queued");
            scenario.executor.runNextOnWorker();
            assertEquals("approved original", Files.readString(approved));
            window.frame();
            assertFalse(window.documentIo.busy(), "overwrite requires the owner's explicit decision");
            assertTrue(scenario.executor.tasks.isEmpty());

            if (!afterApproval) retarget(alias, foreign);
            window.event(new TextInput("y"));
            assertTrue(window.documentIo.busy());
            if (afterApproval) retarget(alias, foreign);
            window.event(new TextInput("ignored"));
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            scenario.executor.runNextOnWorker();
            window.frame();

            assertEquals("approved original", Files.readString(approved));
            assertEquals("foreign original", Files.readString(foreign));
            assertEquals(window.document, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertTrue(window.active().state().isDirty());
            assertTrue(window.notification.message.startsWith("Save failed:"));
            assertEquals(alias.toString(), window.filename.text());
            assertFalse(window.documentIo.busy());
        }
    }

    @Test
    void overwriteApprovalIsForTheInspectedBytesEvenWhenFileSizeAndTimestampAreRestored() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            Path target = Files.writeString(directory.resolve("target.txt"), "before");
            var modified = Files.getLastModifiedTime(target);
            window.event(new TextInput("draft "));
            SaveWorkflowIntegrationTest.submitFilename(window, target);
            scenario.executor.runNextOnWorker();
            window.frame();
            Files.writeString(target, "remote");
            Files.setLastModifiedTime(target, modified);
            window.event(new TextInput("y"));
            scenario.executor.runNextOnWorker();
            window.frame();

            assertEquals("remote", Files.readString(target));
            assertTrue(window.active().state().isDirty());
            assertEquals(window.document, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertTrue(window.notification.message.startsWith("Save failed:"));
            assertTrue(window.active().tabSession().engine().undo(), "failed approval adds no history entry");
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test
    void aNewFileRequiresOwnerPublicationBetweenInspectionAndWriting() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            Path target = directory.resolve("new.txt");
            window.event(new TextInput("draft "));
            SaveWorkflowIntegrationTest.submitFilename(window, target);
            scenario.executor.runNextOnWorker();
            assertFalse(Files.exists(target));
            assertTrue(window.active().state().isDirty());
            assertTrue(scenario.executor.tasks.isEmpty(), "worker cannot approve and enqueue a write");
            window.frame();
            assertTrue(window.documentIo.busy());
            assertEquals(1, scenario.executor.tasks.size());
            assertFalse(Files.exists(target));
            scenario.executor.runNextOnWorker();
            assertEquals("draft original", Files.readString(target));
            assertTrue(window.active().state().isDirty(), "only the owner may publish the savepoint");
            window.frame();
            assertFalse(window.active().state().isDirty());
            assertEquals(target, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
        }
    }

    @Test
    void shutdownCollectsAnInspectionWithoutEnqueueingAWriteAndRetainsTheDraftForRecovery() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            Path target = directory.resolve("not-started.txt");
            window.event(new TextInput("draft "));
            SaveWorkflowIntegrationTest.submitFilename(window, target);
            scenario.executor.runNextOnWorker();
            assertTrue(window.documentIo.busy(), "inspection has not been collected by the owner");
            assertTrue(scenario.executor.tasks.isEmpty());

            assertDoesNotThrow(window.controller::closeDocumentIo);

            assertFalse(window.documentIo.busy());
            assertTrue(scenario.executor.tasks.isEmpty(), "shutdown must not enqueue the next save phase");
            assertFalse(Files.exists(target));
            assertTrue(window.active().state().isDirty());
            assertEquals(window.document, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            window.recoveryManager.finishSession(window.host.toTabSessions(window.tabs), false);
            assertTrue(window.recoverySnapshots.values().stream()
                .anyMatch(snapshot -> snapshot.lines().equals(List.of("draft original"))));
        }
    }

    @Test
    void retargetingACurrentFileAliasToAnotherOpenTabDuringPreflightIsRejected() throws Exception {
        try (Scenario scenario = scenario()) {
            EditorWindowFixture window = scenario.window;
            Path other = Files.writeString(directory.resolve("other.txt"), "other original");
            Path alias = Files.createSymbolicLink(directory.resolve("alias.txt"), window.document);
            window.command(TuinkCommand.TAB_NEW);
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(other));
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.event(new TextInput("draft "));
            SaveWorkflowIntegrationTest.submitFilename(window, alias);
            // This alias was classified as the current file at Enter. The detached
            // inspection must not inherit that decision after it points elsewhere.
            retarget(alias, other);
            scenario.executor.runNextOnWorker();
            window.frame();
            assertFalse(window.documentIo.busy());
            assertTrue(scenario.executor.tasks.isEmpty());
            assertEquals("other original", Files.readString(other));
            assertTrue(window.active().state().isDirty());
            assertTrue(window.notification.message.contains("already open in tab"));
        }
    }

    private static void retarget(Path alias, Path target) throws Exception {
        Files.delete(alias);
        Files.createSymbolicLink(alias, target);
    }

    private Scenario scenario() throws Exception {
        ControlledExecutor executor = new ControlledExecutor();
        return new Scenario(new EditorWindowFixture(directory, false, List.of("original"), executor), executor);
    }

    private record Scenario(EditorWindowFixture window, ControlledExecutor executor) implements AutoCloseable {
        @Override public void close() {
            while (!executor.tasks.isEmpty()) executor.tasks.removeFirst().run();
            window.close();
        }
    }

    private static final class ControlledExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runNextOnWorker() throws Exception {
            Thread worker = new Thread(tasks.removeFirst(), "test-save-target-io");
            worker.start();
            worker.join(5_000L);
            assertFalse(worker.isAlive(), "detached target operation did not finish");
        }
    }
}
