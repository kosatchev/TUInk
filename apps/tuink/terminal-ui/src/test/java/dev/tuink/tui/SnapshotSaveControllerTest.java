package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class SnapshotSaveControllerTest {
    @TempDir Path directory;

    @Test
    void aBackgroundSnapshotSavePublishesOnlyItsOwnUiAndDoesNotActivateAnEditor() throws Exception {
        try (var fixture = fixture()) {
            PreviewTab original = fixture.preview;
            FrozenDocumentSource source = fixture.source(original);
            PreviewTab selected = fixture.addPreview(loaded(directory.resolve("other.md"), "other"), "other ");
            selected.uiState().modalState = UiModalState.saveAs();
            selected.uiState().saveAsInput.set("independent.md");
            fixture.saves.saveCurrent(original);
            assertTrue(fixture.saves.busy());
            assertEquals("original", Files.readString(directory.resolve("source.md")));
            fixture.runWorker();
            assertSame(source, fixture.source(original));
            assertEquals("accepted original", Files.readString(directory.resolve("source.md")));
            assertTrue(fixture.io.collect());
            PreviewTab saved = (PreviewTab) fixture.context.presentation(original.presentationId());
            assertEquals(original.generation() + 1, saved.generation());
            assertSame(source.text(), fixture.source(saved).text());
            assertSame(fixture.source(saved), saved.uiState().preview.sourceForFreeze());
            assertEquals(selected.presentationId(), fixture.context.activePresentation().presentationId());
            assertEquals("independent.md", selected.uiState().saveAsInput.text());
            assertTrue(selected.uiState().modalState.isSaveAs());
            assertTrue(saved.uiState().notification.message.startsWith("Saved "));
            assertEquals(1, fixture.completed.size());
            assertTrue(fixture.completed.getFirst().saved());
            assertFalse(fixture.saves.busy());
            assertTrue(fixture.context.tabs().isEmpty(), "saving a frozen snapshot must not manufacture an editing document");
        }
    }

    @Test
    void aStandaloneReceiptKeepsHelpOpenedLaterInTheSamePreview() throws Exception {
        try (var fixture = fixture()) {
            fixture.saves.saveCurrent(fixture.preview);
            fixture.preview.uiState().modalState = UiModalState.helpHints();
            var scroll = new dev.tuiframe.widget.ListView.State(3, 2);
            fixture.preview.uiState().helpScrollState = scroll;
            fixture.runWorker(); fixture.io.collect();
            assertTrue(fixture.current().uiState().modalState.isHelpHints());
            assertSame(scroll, fixture.current().uiState().helpScrollState);
            assertEquals("accepted original", Files.readString(directory.resolve("source.md")));
            assertTrue(fixture.current().uiState().notification.message.startsWith("Saved "));
            assertEquals(1, fixture.completed.size());
            assertTrue(fixture.completed.getFirst().saved());
            assertTrue(fixture.context.tabs().isEmpty());
        }
    }

    @Test
    void saveAsKeepsOneAttemptAcrossInspectionAndWriteAndRetargetsOnlyAfterTheReceipt() throws Exception {
        try (var fixture = fixture()) {
            PreviewTab preview = fixture.preview;
            FrozenDocumentSource source = fixture.source(preview);
            Path target = directory.resolve("copy.md");
            fixture.submitAs(preview, target);
            assertTrue(preview.uiState().modalState.isNone());
            fixture.runWorker();
            assertTrue(fixture.io.collect());
            assertEquals(0, fixture.completed.size());
            assertEquals(1, fixture.tasks.size());
            assertSame(source, fixture.source(preview));
            fixture.runWorker();
            assertTrue(fixture.saves.writing());
            assertEquals(DocumentIoCoordinator.CancelResult.DRAINING_WRITE, fixture.saves.cancel(preview));
            assertSame(source, fixture.source(preview));
            assertTrue(fixture.io.collect());
            PreviewTab saved = fixture.current();
            assertEquals(target, JvmDocumentStorage.pathOf(fixture.source(saved).binding().resource()));
            assertTrue(saved.uiState().saveAsInput.isEmpty());
            assertTrue(saved.uiState().modalState.isNone());
            assertNull(saved.uiState().snapshotOverwriteApproval);
            assertEquals(1, fixture.completed.size());
            assertTrue(fixture.completed.getFirst().saved());
            assertEquals("accepted original", Files.readString(target));
            assertTrue(fixture.context.tabs().isEmpty());
        }
    }

    @Test
    void overwriteWaitsForItsOwnerDecisionAndDoesNotCancelAnotherWindowOperation() throws Exception {
        try (var fixture = fixture()) {
            Path target = Files.writeString(directory.resolve("existing.md"), "existing");
            fixture.submitAs(fixture.preview, target);
            fixture.runWorker(); fixture.io.collect();
            var approval = fixture.preview.uiState().snapshotOverwriteApproval;
            assertNotNull(approval);
            assertTrue(fixture.preview.uiState().modalState.isSaveOverwriteConfirm());
            assertTrue(fixture.saves.busy());
            assertFalse(fixture.saves.writing());
            assertTrue(fixture.completed.isEmpty());
            fixture.io.submitSave(() -> "another operation", completion -> assertEquals("another operation", completion.value()));
            fixture.saves.confirm(fixture.preview, ConfirmChoice.SAVE);
            assertSame(approval, fixture.preview.uiState().snapshotOverwriteApproval);
            assertEquals(DocumentIoCoordinator.CancelResult.CANCELLED, fixture.saves.cancel(fixture.preview));
            assertNull(fixture.preview.uiState().snapshotOverwriteApproval);
            assertTrue(fixture.io.busy(), "cancelling this approval must not cancel another document's worker");
            fixture.runWorker(); fixture.io.collect();
            assertEquals("existing", Files.readString(target));
            assertFalse(fixture.saves.busy());
            assertTrue(fixture.completed.isEmpty());
        }
    }

    @Test
    void cancellingQueuedPreparationRetainsTheSnapshotWithoutWritingOrCallingBack() throws Exception {
        try (var fixture = fixture()) {
            var source = fixture.source(fixture.preview);
            fixture.saves.saveCurrent(fixture.preview);
            assertEquals(DocumentIoCoordinator.CancelResult.CANCELLED, fixture.saves.cancel(fixture.preview));
            assertFalse(fixture.io.busy());
            fixture.runWorker();
            assertFalse(fixture.io.collect());
            assertEquals("original", Files.readString(directory.resolve("source.md")));
            assertSame(source, fixture.source(fixture.preview));
            assertTrue(fixture.completed.isEmpty());
            assertFalse(fixture.saves.busy());
        }
    }

    @Test
    void aLateReceiptStillCompletesItsSubjectButCannotMutateARetiredUi() throws Exception {
        try (var fixture = fixture()) {
            var preview = fixture.preview;
            var subject = ((PreviewTab.ClosedSnapshot) preview.source()).subject();
            fixture.saves.saveCurrent(preview);
            fixture.runWorker();
            String status = preview.uiState().notification.message;
            fixture.context.removePreview(preview.presentationId());
            preview.close();
            assertTrue(fixture.io.collect());
            assertEquals(1, subject.generation());
            assertEquals(status, preview.uiState().notification.message);
            assertNull(fixture.context.presentation(preview.presentationId()));
            assertEquals(1, fixture.completed.size());
            assertFalse(fixture.completed.getFirst().saved(), "a retired presentation cannot receive a successful UI effect");
        }
    }

    @Test
    void aNewFilenameDraftIsNotClearedByThePreviousSaveReceipt() throws Exception {
        try (var fixture = fixture()) {
            Path submitted = directory.resolve("submitted.md");
            Path later = directory.resolve("later.md");
            fixture.submitAs(fixture.preview, submitted);
            fixture.preview.uiState().saveAsInput.set(later.toString());
            fixture.preview.uiState().filenameRevision++;
            fixture.preview.uiState().modalState = UiModalState.saveAs();
            fixture.runWorker(); fixture.io.collect();
            fixture.runWorker(); fixture.io.collect();
            assertEquals("accepted original", Files.readString(submitted));
            assertFalse(Files.exists(later));
            assertEquals(later.toString(), fixture.current().uiState().saveAsInput.text());
            assertTrue(fixture.current().uiState().modalState.isSaveAs());
            assertEquals(1, fixture.completed.size());
        }
    }

    @Test
    void overwriteApprovalNamesTheSubmittedTargetWhileRetainingTheNewFilenameDraft() throws Exception {
        try (var fixture = fixture()) {
            Path submitted = Files.writeString(directory.resolve("submitted-existing.md"), "existing");
            Path newer = directory.resolve("new-draft.md");
            fixture.submitAs(fixture.preview, submitted);
            fixture.preview.uiState().saveAsInput.set(newer.toString());
            fixture.preview.uiState().filenameRevision++;
            fixture.preview.uiState().modalState = UiModalState.saveAs();
            fixture.runWorker(); fixture.io.collect();
            assertTrue(fixture.preview.uiState().modalState.isSaveOverwriteConfirm());
            assertEquals(submitted, fixture.preview.uiState().modalState.pendingOverwritePath());
            assertEquals(newer.toString(), fixture.preview.uiState().saveAsInput.text());
            assertTrue(fixture.completed.isEmpty());
            fixture.saves.confirm(fixture.preview, ConfirmChoice.SAVE);
            fixture.runWorker(); fixture.io.collect();
            assertEquals("accepted original", Files.readString(submitted));
            assertFalse(Files.exists(newer));
            assertEquals(newer.toString(), fixture.current().uiState().saveAsInput.text());
            fixture.saves.openSaveAs(fixture.current());
            assertTrue(fixture.current().uiState().modalState.isSaveAs());
            assertEquals(newer.toString(), fixture.current().uiState().saveAsInput.text());
            assertEquals(1, fixture.completed.size());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ConfirmChoice.class, names = {"CANCEL", "DISCARD"})
    void decliningTheOldOverwritePreservesTheNewDraftAndRechecksItsWorkspaceOwner(ConfirmChoice choice) throws Exception {
        try (var fixture = fixture()) {
            Path submitted = Files.writeString(directory.resolve("submitted-existing.md"), "existing");
            Path owned = directory.resolve("owned-draft.md");
            var loaded = loaded(owned, "owned");
            fixture.submitAs(fixture.preview, submitted);
            fixture.preview.uiState().saveAsInput.set(owned.toString());
            fixture.preview.uiState().filenameRevision++;
            fixture.preview.uiState().modalState = UiModalState.saveAs();
            var editor = fixture.editor(loaded, "dirty ");
            fixture.context.addNewTabAndActivate(editor);
            fixture.runWorker(); fixture.io.collect();
            assertTrue(fixture.preview.uiState().modalState.isSaveOverwriteConfirm());
            assertEquals(submitted, fixture.preview.uiState().modalState.pendingOverwritePath());
            fixture.saves.confirm(fixture.preview, choice);
            if (choice == ConfirmChoice.CANCEL) fixture.saves.openSaveAs(fixture.preview);
            assertTrue(fixture.preview.uiState().modalState.isSaveAs());
            assertEquals(owned.toString(), fixture.preview.uiState().saveAsInput.text());
            fixture.saves.input(fixture.preview, TuinkCommand.EDIT_INSERT_NEWLINE);
            assertTrue(fixture.tasks.isEmpty());
            assertFalse(fixture.saves.busy());
            assertTrue(fixture.preview.uiState().notification.message.contains("already open"));
            assertEquals("existing", Files.readString(submitted));
            assertEquals("owned", Files.readString(owned));
            assertEquals(List.of("dirty owned"), editor.documentSession().document().snapshot().lines());
            assertEquals(editor.presentationId(), fixture.context.activePresentation().presentationId());
        }
    }

    @Test
    void anUnknownWriteKeepsTheSnapshotVisibleAndReportsOneTerminalUncertainOutcome() throws Exception {
        var storage = new MemoryDocumentStorage();
        storage.put("source.md", "original".getBytes(StandardCharsets.UTF_8));
        try (var fixture = new Fixture(worker(() -> storage.load(storage.resource("source.md"), DocumentOpenMode.DETECT, false)))) {
            var source = fixture.source(fixture.preview);
            storage.failNextWrite(MemoryDocumentStorage.WriteFailure.AFTER_COMMIT);
            fixture.saves.saveCurrent(fixture.preview);
            fixture.runWorker(); fixture.io.collect();
            assertEquals(1, fixture.completed.size());
            assertTrue(fixture.completed.getFirst().uncertain());
            assertSame(source, fixture.source(fixture.current()));
            assertTrue(fixture.current().uiState().notification.requiresAttention());
            assertEquals("accepted original", new String(storage.bytes("source.md"), StandardCharsets.UTF_8));
            assertFalse(fixture.saves.busy());
            assertTrue(fixture.context.tabs().isEmpty());
        }
    }

    @Test
    void shutdownCancelsUnstartedWorkAndDrainsACompletedPublicationWithoutUiCallbacks() throws Exception {
        try (var fixture = fixture()) {
            fixture.saves.saveCurrent(fixture.preview);
            fixture.saves.close();
            fixture.runWorker();
            assertFalse(fixture.io.collect());
            assertEquals("original", Files.readString(directory.resolve("source.md")));
            assertTrue(fixture.completed.isEmpty());
        }
        try (var fixture = fixture()) {
            fixture.saves.saveCurrent(fixture.preview);
            fixture.runWorker();
            fixture.saves.close();
            fixture.io.close();
            assertEquals("accepted original", Files.readString(directory.resolve("source.md")));
            assertEquals(1, ((PreviewTab.ClosedSnapshot) fixture.preview.source()).subject().generation());
            assertTrue(fixture.completed.isEmpty());
        }
    }

    @Test
    void aNewAlertIsNotOverwrittenByAnOlderSnapshotSaveReceipt() throws Exception {
        try (var fixture = fixture()) {
            fixture.saves.saveCurrent(fixture.preview);
            fixture.preview.uiState().notification.warning("A newer warning");
            fixture.runWorker(); fixture.io.collect();
            assertEquals("A newer warning", fixture.current().uiState().notification.message);
            assertEquals("accepted original", Files.readString(directory.resolve("source.md")));
            assertTrue(fixture.completed.getFirst().saved());
        }
    }

    @Test
    void aSuccessfulSaveAsPublishesToItsOwnAdvancedGeneration() throws Exception {
        try (var fixture = fixture()) {
            var before = UiRecipient.presentation(fixture.preview, UiRecipient.Flow.LOCAL);
            fixture.submitAs(fixture.preview, directory.resolve("generation.md"));
            fixture.runWorker(); fixture.io.collect();
            fixture.runWorker(); fixture.io.collect();
            assertFalse(before.current(fixture.context, null));
            assertEquals(before.generation() + 1, fixture.current().generation());
            assertTrue(fixture.current().uiState().saveAsInput.isEmpty());
            assertTrue(fixture.current().uiState().notification.message.startsWith("Saved "));
            assertTrue(fixture.current().uiState().modalState.isNone());
        }
    }

    @Test
    void windowCloseRevokesQueuedSnapshotPreparationAfterItsUiRetires() throws Exception {
        try (var fixture = fixture()) {
            fixture.saves.saveCurrent(fixture.preview);
            fixture.context.removePreview(fixture.preview.presentationId());
            fixture.preview.close();
            fixture.saves.cancelPendingForClose();
            assertFalse(fixture.io.busy());
            assertFalse(fixture.saves.busy());
            fixture.runWorker();
            assertFalse(fixture.io.collect());
            assertEquals("original", Files.readString(directory.resolve("source.md")));
            assertTrue(fixture.completed.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aNewAlertSurvivesSnapshotSaveAsInspectionAndItsAutomaticWrite(boolean error) throws Exception {
        try (var fixture = fixture()) {
            Path target = directory.resolve("alert-snapshot.md");
            fixture.submitAs(fixture.preview, target);
            var owner = fixture.preview.uiState();
            String message = error ? "New snapshot error" : "New snapshot warning";
            if (error) owner.notification.error(message); else owner.notification.warning(message);
            fixture.runWorker(); fixture.io.collect();
            assertTrue(fixture.io.busy());
            assertEquals(message, owner.notification.message);
            fixture.runWorker(); fixture.io.collect();
            assertFalse(fixture.io.busy());
            assertEquals("accepted original", Files.readString(target));
            assertTrue(fixture.current().uiState().saveAsInput.isEmpty());
            assertTrue(fixture.completed.getFirst().saved());
            assertEquals(message, fixture.current().uiState().notification.message,
                "an automatic phase cannot adopt the newer alert as its own baseline");
        }
    }

    private Fixture fixture() throws Exception { return new Fixture(loaded(directory.resolve("source.md"), "original")); }

    private static DocumentLoad loaded(Path path, String text) throws Exception {
        Files.writeString(path, text);
        return worker(() -> JvmDocumentStorage.resource(path).storage().load(
            JvmDocumentStorage.resource(path), DocumentOpenMode.DETECT, false));
    }

    private static final class Fixture implements AutoCloseable {
        final TerminalEditorApp host = new TerminalEditorApp();
        final TerminalEditorApp.SessionContext context;
        final MarkdownPreviewService service = new MarkdownPreviewService(() -> {});
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final DocumentIoController io = new DocumentIoController(tasks::addLast);
        final List<SnapshotSaveWorkflow.Result> completed = new ArrayList<>();
        final SnapshotSaveController saves;
        final PreviewTab preview;

        Fixture(DocumentLoad loaded) {
            host.styles = UiStyles.forTheme(UiStyles.Theme.CYAN);
            var editor = editor(loaded, "accepted ");
            context = new TerminalEditorApp.SessionContext(List.of(editor), 0);
            preview = freeze(editor);
            saves = new SnapshotSaveController(host, context, io, () -> {}, completion -> completed.add(completion.result()));
        }

        PreviewTab addPreview(DocumentLoad loaded, String prefix) {
            var editor = editor(loaded, prefix);
            context.addNewTabAndActivate(editor);
            return freeze(editor);
        }

        private TerminalEditorApp.EditorTab editor(DocumentLoad loaded, String prefix) {
            var editor = host.newEditorTab();
            editor.documentSession().document().openPrepared(loaded);
            editor.documentSession().document().execute(editor.documentSession().viewId(), new DocumentCommand.Input(prefix));
            return editor;
        }

        private PreviewTab freeze(TerminalEditorApp.EditorTab editor) {
            var source = editor.documentSession().document().freezeSource();
            var initial = new PreviewTab(editor);
            assertTrue(initial.uiState().preview.initialize(source, initial.generation(), service));
            context.addPreview(initial, editor.documentSession().viewId());
            context.remove(editor.documentSession().viewId());
            editor.uiState().close();
            return (PreviewTab) context.presentation(initial.presentationId());
        }

        FrozenDocumentSource source(PreviewTab preview) { return ((PreviewTab.ClosedSnapshot) preview.source()).source(); }
        PreviewTab current() { return (PreviewTab) context.presentation(preview.presentationId()); }
        void submitAs(PreviewTab preview, Path target) {
            saves.openSaveAs(preview);
            preview.uiState().saveAsInput.set(target.toString());
            preview.uiState().filenameRevision++;
            saves.input(preview, TuinkCommand.EDIT_INSERT_NEWLINE);
        }
        void runWorker() throws Exception {
            Thread thread = new Thread(tasks.removeFirst());
            thread.start(); thread.join(5000); assertFalse(thread.isAlive());
        }
        @Override public void close() {
            saves.close(); io.close();
            for (var tab : context.presentations()) if (tab instanceof PreviewTab retained) retained.close();
            else tab.uiState().close();
            service.close();
        }
    }

    private static <T> T worker(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation);
        Thread thread = new Thread(task); thread.start(); thread.join(5000); assertFalse(thread.isAlive());
        try { return task.get(); }
        catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw (Error) failure.getCause();
        }
    }
}
