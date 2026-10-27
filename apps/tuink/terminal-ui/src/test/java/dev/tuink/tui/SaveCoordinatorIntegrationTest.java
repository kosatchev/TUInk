package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SaveAsSuggestionStore;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SaveCoordinatorIntegrationTest {
    @TempDir Path directory;

    @Test
    void windowUsesTheSessionsSaveLockEvenWithoutAWindowRequest() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var session = window.active().documentSession().document();
            var work = session.io().beginSave();
            assertFalse(window.documentIo.busy(), "the host has not submitted this session operation");

            window.event(new TextInput("blocked"));
            window.command(TuinkCommand.EDIT_UNDO);
            window.command(TuinkCommand.TAB_CLOSE);
            assertDoesNotThrow(() -> window.command(TuinkCommand.DOCUMENT_SAVE));
            assertDoesNotThrow(() -> window.command(TuinkCommand.DOCUMENT_SAVE_AS));
            assertEquals(DocumentIoCoordinator.State.WRITING, session.io().state());
            // A filename prompt has not captured an operation. Closing that
            // prompt cannot cancel a save begun independently through session.
            var context = new SaveWorkflow.Context(List.of(window.active().documentSession()), 0,
                new SaveAsSuggestionStore());
            var cancelledPrompt = SaveWorkflow.saveAsInput(context, TuinkCommand.UI_ESCAPE,
                UiModalState.saveAs(), "independent-name.txt");
            assertTrue(cancelledPrompt.modalState().isNone());
            assertEquals(DocumentIoCoordinator.State.WRITING, session.io().state());
            assertEquals(1, window.tabs.size());
            assertEquals(List.of("draft original"), window.active().tabSession().model().snapshotLines());
            var cursor = window.active().state().getCursor();
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            assertNotEquals(cursor, window.active().state().getCursor());

            assertEquals(DocumentIoCoordinator.CancelResult.CANCELLED, session.io().cancel());
            window.event(new TextInput("after "));
            assertEquals(List.of("draft oafter riginal"), window.active().tabSession().model().snapshotLines());
            assertFalse(work.perform().succeeded(), "cancelled work cannot start the external write");
            assertEquals("original", Files.readString(window.document));
        }
    }

    @Test
    void decliningOverwriteReleasesThePortableLockAndInvalidatesTheOldApproval() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            Path target = Files.writeString(directory.resolve("existing.txt"), "untouched");
            SaveWorkflowIntegrationTest.submitFilename(window, target);
            var session = window.active().documentSession().document();
            assertEquals(DocumentIoCoordinator.State.AWAITING_APPROVAL, session.io().state());
            assertInstanceOf(DocumentCommandResult.Rejected.class,
                session.execute(session.defaultViewId(), new DocumentCommand.Input("bypass")));

            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(DocumentIoCoordinator.State.IDLE, session.io().state());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.event(new TextInput("after "));
            assertEquals(List.of("draft after original"), window.active().tabSession().model().snapshotLines());
            assertEquals("untouched", Files.readString(target));
        }
    }

    @Test
    void shutdownPublishesTheStartedQuitWriteWithoutCapturingTheNextStoppedDocument() throws Exception {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"), queue::add);
             AutoCloseable drain = () -> { while (!queue.isEmpty()) queue.remove().run(); }) {
            window.event(new TextInput("first "));
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            Path secondPath = Files.writeString(directory.resolve("second.txt"), "second original");
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(secondPath));
            window.frame();
            window.event(new TextInput("second "));
            var second = window.active();
            window.command(TuinkCommand.APP_QUIT);
            window.event(new TextInput("y"));
            assertEquals(1, queue.size());
            Thread worker = new Thread(queue.remove(), "quit-write-before-shutdown");
            worker.start();
            worker.join(5_000L);
            assertFalse(worker.isAlive());

            assertDoesNotThrow(window.controller::closeDocumentIo);

            assertFalse(window.documentIo.busy());
            assertTrue(queue.isEmpty(), "shutdown cannot start the next quit-save phase");
            assertFalse(first.state().isDirty(), "the completed write publishes before final recovery");
            assertEquals("first original", Files.readString(window.document));
            assertTrue(second.state().isDirty());
            assertEquals("second original", Files.readString(secondPath));
            assertEquals(DocumentIoCoordinator.State.STOPPED, second.documentSession().document().io().state());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCancelledInspectionsLateCompletionKeepsAnIndependentHelpOrFilenameDialog(boolean filenameDialog)
        throws Exception {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"), queue::add);
             AutoCloseable drain = () -> { while (!queue.isEmpty()) queue.remove().run(); }) {
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("draft "));
            var original = window.active();
            Path abandoned = directory.resolve("abandoned.txt");
            SaveWorkflowIntegrationTest.submitFilename(window, abandoned);
            Path independent = directory.resolve("independent.txt");
            if (filenameDialog) {
                window.command(TuinkCommand.TAB_NEW);
                window.event(new TextInput("independent draft"));
                window.command(TuinkCommand.TAB_CLOSE);
                window.event(new TextInput("y"));
                window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
                window.event(new TextInput(independent.toString()));
            } else {
                window.command(TuinkCommand.HELP_KEY_HINTS);
            }
            runNext(queue);
            assertEquals(DocumentIoCoordinator.CancelResult.CANCELLED,
                original.documentSession().document().io().cancel());
            window.frame();

            assertFalse(window.documentIo.busy());
            assertTrue(queue.isEmpty());
            assertFalse(Files.exists(abandoned));
            assertTrue(original.state().isDirty());
            if (filenameDialog) {
                assertEquals(independent.toString(), window.filename.text());
                assertTrue(window.screenRow(window.height - 2).startsWith("Save As: "));
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                while (window.documentIo.busy()) {
                    runNext(queue);
                    window.frame();
                }
                assertEquals("independent draft", Files.readString(independent));
                assertEquals(1, window.tabs.size(), "the independent close continuation remains attached to its document");
                assertSame(original, window.active());
            } else {
                window.event(new TextInput("ignored while Help is open"));
                assertEquals(List.of("draft original"), original.tabSession().model().snapshotLines());
                window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
                window.event(new TextInput("after "));
                assertEquals(List.of("draft after original"), original.tabSession().model().snapshotLines());
            }
        }
    }

    private static void runNext(ArrayDeque<Runnable> queue) throws InterruptedException {
        Thread worker = new Thread(queue.remove(), "late-save-completion");
        worker.start();
        worker.join(5_000L);
        assertFalse(worker.isAlive(), "the detached phase did not finish");
    }
}
