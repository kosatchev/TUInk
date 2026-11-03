package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.document.PresentationId;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.editor.session.document.FrozenDocumentSession;
import dev.tuink.editor.storage.*;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.verification.MemoryDocumentStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotSaveWorkflowTest {
    @TempDir Path directory;

    @Test
    void aMissingCurrentResourceKeepsTheSnapshotUntilExplicitSaveAsSucceeds() throws Exception {
        Path original = directory.resolve("closed.md");
        byte[] raw = "# accepted  \r\n```mermaid\r\n\tA-->B\r\n```\r\n".getBytes(StandardCharsets.UTF_8);
        var context = context(loaded(original, raw));
        var captured = context.owner().subject().source();
        Files.delete(original);
        var failed = finish(context, SnapshotSaveWorkflow.saveCurrent(context));
        assertEquals(SaveWorkflow.NoticeKind.ERROR, failed.notice().kind());
        assertTrue(failed.modalState().isNone(), "missing current resources do not automatically become Save As");
        assertFalse(failed.saved());
        assertSame(captured, context.owner().subject().source());
        Path copy = directory.resolve("explicit-copy.md");
        var opened = SnapshotSaveWorkflow.openSaveAs(context);
        assertTrue(opened.modalState().isSaveAs());
        var saved = finish(context, SnapshotSaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
            opened.modalState(), copy.toString()));
        assertTrue(saved.saved());
        assertArrayEquals(raw, Files.readAllBytes(copy));
        assertFalse(Files.exists(original));
        assertSame(captured.text(), context.owner().subject().source().text());
        assertEquals(copy, JvmDocumentStorage.pathOf(context.owner().subject().binding().resource()));
    }

    @Test
    void overwriteCancelAndChooseAnotherNameKeepBothFilesAndTheFrozenBinding() throws Exception {
        var context = context(loaded(directory.resolve("source.md"), "source".getBytes(StandardCharsets.UTF_8)));
        var original = context.owner().subject().source();
        Path target = Files.writeString(directory.resolve("existing.md"), "existing");
        for (var choice : List.of(ConfirmChoice.CANCEL, ConfirmChoice.DISCARD)) {
            var requested = SnapshotSaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
                UiModalState.saveAs(), target.toString());
            var approval = finish(context, requested);
            assertTrue(approval.modalState().isSaveOverwriteConfirm());
            assertNotNull(approval.overwriteApproval());
            var decision = SnapshotSaveWorkflow.overwrite(new SnapshotSaveWorkflow.Context(context.owner(), List.of(),
                approval.overwriteApproval()), approval.modalState(), choice);
            assertEquals(choice == ConfirmChoice.DISCARD, decision.modalState().isSaveAs());
            assertFalse(context.owner().subject().io().busy());
            assertEquals("existing", Files.readString(target));
            assertSame(original, context.owner().subject().source());
        }
    }

    @Test
    void confirmingOverwritePublishesTheRequestedNameOnlyAfterTheWriteReceipt() throws Exception {
        var context = context(loaded(directory.resolve("source.md"), "raw $x^2$  \n".getBytes(StandardCharsets.UTF_8)));
        var original = context.owner().subject().source();
        Path target = Files.writeString(directory.resolve("existing.md"), "existing");
        var requested = SnapshotSaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAs(), target.toString());
        var approval = finish(context, requested);
        var approved = SnapshotSaveWorkflow.overwrite(new SnapshotSaveWorkflow.Context(context.owner(), List.of(),
            approval.overwriteApproval()), approval.modalState(), ConfirmChoice.SAVE);
        assertNotNull(approved.pendingSave());
        assertSame(original, context.owner().subject().source());
        var request = approved.pendingSave();
        var completion = worker(request::perform);
        assertEquals("raw $x^2$  \n", Files.readString(target));
        assertSame(original, context.owner().subject().source(), "the worker cannot update the UI subject's reopen target");
        var saved = SnapshotSaveWorkflow.complete(context, request, completion, null);
        assertTrue(saved.saved());
        assertEquals(target, JvmDocumentStorage.pathOf(context.owner().subject().binding().resource()));
        assertSame(original.text(), context.owner().subject().source().text());
    }

    @Test
    void aRetiredPresentationGenerationCannotPublishOrApproveAnotherSaveDialog() throws Exception {
        var context = context(loaded(directory.resolve("source.md"), "source".getBytes(StandardCharsets.UTF_8)));
        Path target = directory.resolve("stale.md");
        var requested = SnapshotSaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAs(), target.toString());
        var request = requested.pendingSave();
        var completion = worker(request::perform);
        var replacement = new SnapshotSaveWorkflow.Context(new SnapshotSaveWorkflow.Owner(context.owner().presentationId(),
            context.owner().generation() + 1, context.owner().subject()), List.of());
        var ignored = SnapshotSaveWorkflow.complete(replacement, request, completion, null);
        assertEquals(SaveWorkflow.NoticeKind.UNCHANGED, ignored.notice().kind());
        assertTrue(ignored.modalState().isNone());
        assertNull(ignored.pendingSave());
        assertFalse(context.owner().subject().io().busy());
        assertFalse(Files.exists(target));
    }

    @Test
    void filenameValidationAndCancellationReuseTheExistingFieldProtocol() throws Exception {
        var context = context(loaded(directory.resolve("source.md"), "source".getBytes(StandardCharsets.UTF_8)));
        var source = context.owner().subject().source();
        var invalid = SnapshotSaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE, UiModalState.saveAs(), "");
        assertTrue(invalid.modalState().isSaveAs());
        assertTrue(invalid.focusFilename());
        assertEquals(SaveWorkflow.NoticeKind.ERROR, invalid.notice().kind());
        assertNull(invalid.pendingSave());
        var cancelled = SnapshotSaveWorkflow.saveAsInput(context, TuinkCommand.UI_ESCAPE, UiModalState.saveAs(), "unsent.md");
        assertTrue(cancelled.modalState().isNone());
        assertEquals(SaveFlowController.TextInputEffectKind.CLEAR, cancelled.filename().kind());
        assertSame(source, context.owner().subject().source());
    }

    @Test
    void aLiveDocumentOwnerBlocksSnapshotCurrentSaveAndSaveAsIncludingAliases() throws Exception {
        Path owned = directory.resolve("owned.md");
        var source = loaded(owned, "owned".getBytes(StandardCharsets.UTF_8));
        var live = EditorDocumentSession.create(new DocumentId("live-owner"), List.of(""));
        live.openPrepared(worker(() -> JvmDocumentStorage.resource(owned).storage().load(
            JvmDocumentStorage.resource(owned), DocumentOpenMode.DETECT, false)));
        var context = new SnapshotSaveWorkflow.Context(context(source).owner(),
            List.of(new WorkspaceSaveConflictPolicy.OpenDocument(live, 7)));
        var refused = SnapshotSaveWorkflow.saveCurrent(context);
        assertNull(refused.pendingSave());
        assertTrue(refused.notice().message().contains("tab 7"));
        var refusedAs = SnapshotSaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAs(), owned.toString());
        assertNull(refusedAs.pendingSave());
        assertTrue(refusedAs.notice().message().contains("tab 7"));
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Path alias = Files.createSymbolicLink(directory.resolve("alias.md"), owned.getFileName());
            var aliasRefused = SnapshotSaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
                UiModalState.saveAs(), alias.toString());
            assertNull(aliasRefused.pendingSave());
            assertTrue(aliasRefused.notice().message().contains("tab 7"));
        }
        assertEquals("owned", Files.readString(owned));
    }

    @Test
    void ownershipIsRecheckedAfterDetachedInspectionBeforeOfferingOverwrite() throws Exception {
        var context = context(loaded(directory.resolve("source.md"), "source".getBytes(StandardCharsets.UTF_8)));
        Path target = Files.writeString(directory.resolve("other.md"), "other");
        var requested = SnapshotSaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAs(), target.toString());
        var request = requested.pendingSave();
        var completion = worker(request::perform);
        var live = EditorDocumentSession.create(new DocumentId("new-owner"), List.of(""));
        live.openPrepared(worker(() -> JvmDocumentStorage.resource(target).storage().load(
            JvmDocumentStorage.resource(target), DocumentOpenMode.DETECT, false)));
        var current = new SnapshotSaveWorkflow.Context(context.owner(), List.of(new WorkspaceSaveConflictPolicy.OpenDocument(live, 3)));
        var refused = SnapshotSaveWorkflow.complete(current, request, completion, null);
        assertTrue(refused.modalState().isSaveAs());
        assertNull(refused.overwriteApproval());
        assertFalse(context.owner().subject().io().busy());
        assertTrue(refused.notice().message().contains("tab 3"));
        assertEquals("other", Files.readString(target));
    }

    @Test
    void anEncodingFailureOffersAnExplicitUtf8SaveAsWithoutOpeningAnEditor() throws Exception {
        Path path = directory.resolve("legacy.txt");
        var observed = loaded(path, "legacy".getBytes(StandardCharsets.UTF_8));
        var metadata = DocumentFileMetadata.canonical(java.nio.charset.Charset.forName("windows-1251"), BomKind.NONE, "\n", 1);
        var source = new FrozenDocumentSource(new DocumentSnapshot(observed.text().documentId(), 6, List.of("😀Привет")),
            metadata, observed.binding());
        var context = context(source);
        var failed = finish(context, SnapshotSaveWorkflow.saveCurrent(context));
        assertTrue(failed.modalState().isSaveUtf8Confirm());
        assertSame(source, context.owner().subject().source());
        assertEquals("legacy", Files.readString(path));
        var choice = SnapshotSaveWorkflow.encodingChoice(failed.modalState(), ConfirmChoice.SAVE);
        assertTrue(choice.modalState().isSaveAs());
        Path target = directory.resolve("utf8.md");
        var saved = finish(context, SnapshotSaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
            choice.modalState(), target.toString()));
        assertTrue(saved.saved());
        assertEquals(StandardCharsets.UTF_8, context.owner().subject().metadata().charset());
        assertEquals("😀Привет", Files.readString(target));
        assertSame(source.text(), context.owner().subject().source().text());
    }

    @Test
    void anOverwriteDialogCannotAuthorizeAReplacementSubjectWithTheSamePresentationId() throws Exception {
        var source = loaded(directory.resolve("source.md"), "source".getBytes(StandardCharsets.UTF_8));
        var context = context(source);
        Path target = Files.writeString(directory.resolve("existing.md"), "existing");
        var approval = finish(context, SnapshotSaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAs(), target.toString()));
        var replacement = new SnapshotSaveWorkflow.Context(new SnapshotSaveWorkflow.Owner(context.owner().presentationId(),
            context.owner().generation(), FrozenDocumentSession.create(source)), List.of(), approval.overwriteApproval());
        var refused = SnapshotSaveWorkflow.overwrite(replacement, approval.modalState(), ConfirmChoice.SAVE);
        assertNull(refused.pendingSave());
        assertFalse(replacement.owner().subject().io().busy());
        assertTrue(context.owner().subject().io().busy(), "a foreign dialog cannot cancel the original owner's decision either");
        assertEquals("existing", Files.readString(target));
        context.owner().subject().close();
    }

    @Test
    void aProviderBackedSourceRemainsBoundWithoutInventingAJvmPathOrAnUnnamedDocument() throws Exception {
        var storage = new MemoryDocumentStorage();
        storage.put("provider-source.md", "old".getBytes(StandardCharsets.UTF_8));
        var loaded = worker(() -> storage.load(storage.resource("provider-source.md"), DocumentOpenMode.DETECT, false));
        var text = new DocumentSnapshot(new DocumentId("provider-bound"), 1, List.of("accepted raw"));
        var source = new FrozenDocumentSource(text, loaded.metadata(),
            new ResourceBinding(loaded.resource(), loaded.disposition(), loaded.baseline()));
        var context = context(source);
        assertEquals("provider-source.md", SnapshotSaveWorkflow.openSaveAs(context).filename().text());
        var saved = finish(context, SnapshotSaveWorkflow.saveCurrent(context));
        assertTrue(saved.saved());
        assertTrue(saved.notice().message().contains("memory:provider-source.md"));
        assertSame(storage, context.owner().subject().binding().resource().storage());
        assertEquals("accepted raw", new String(storage.bytes("provider-source.md"), StandardCharsets.UTF_8));
        var unbound = context(new FrozenDocumentSource(text, loaded.metadata(), ResourceBinding.unbound()));
        assertEquals("untitled.md", SnapshotSaveWorkflow.openSaveAs(unbound).filename().text());
        var unavailable = SnapshotSaveWorkflow.saveCurrent(unbound);
        assertNull(unavailable.pendingSave());
        assertEquals(SaveWorkflow.NoticeKind.ERROR, unavailable.notice().kind());
    }

    @Test
    void anUnknownPublicationIsReportedExplicitlyAndRetainsTheRawSnapshot() throws Exception {
        var storage = new MemoryDocumentStorage();
        storage.put("source.md", "old".getBytes(StandardCharsets.UTF_8));
        var loaded = worker(() -> storage.load(storage.resource("source.md"), DocumentOpenMode.DETECT, false));
        var source = new FrozenDocumentSource(new DocumentSnapshot(new DocumentId("unknown"), 1, List.of("accepted raw")),
            loaded.metadata(), new ResourceBinding(loaded.resource(), loaded.disposition(), loaded.baseline()));
        var context = context(source);
        storage.failNextWrite(MemoryDocumentStorage.WriteFailure.AFTER_COMMIT);
        var failed = finish(context, SnapshotSaveWorkflow.saveCurrent(context));
        assertTrue(failed.uncertain());
        assertEquals(DocumentIoCoordinator.FailureKind.UNKNOWN, failed.failureKind());
        assertFalse(failed.saved());
        assertSame(source, context.owner().subject().source());
        assertEquals("accepted raw", new String(storage.bytes("source.md"), StandardCharsets.UTF_8));
    }

    private static SnapshotSaveWorkflow.Context context(FrozenDocumentSource source) {
        return new SnapshotSaveWorkflow.Context(new SnapshotSaveWorkflow.Owner(new PresentationId("preview"), 4,
            FrozenDocumentSession.create(source)), List.of());
    }

    private static FrozenDocumentSource loaded(Path path, byte[] bytes) throws Exception {
        Files.write(path, bytes);
        var loaded = worker(() -> JvmDocumentStorage.resource(path).storage().load(
            JvmDocumentStorage.resource(path), DocumentOpenMode.DETECT, false));
        return new FrozenDocumentSource(new DocumentSnapshot(new DocumentId("closed:" + path.getFileName()), 3, loaded.lines()),
            loaded.metadata(), new ResourceBinding(loaded.resource(), loaded.disposition(), loaded.baseline()));
    }

    private static SnapshotSaveWorkflow.Result finish(SnapshotSaveWorkflow.Context context, SnapshotSaveWorkflow.Result result) throws Exception {
        for (int phase = 0; result.pendingSave() != null && phase < 3; phase++) {
            var request = result.pendingSave();
            try { result = SnapshotSaveWorkflow.complete(context, request, worker(request::perform), null); }
            catch (IOException failure) { result = SnapshotSaveWorkflow.complete(context, request, null, failure); }
        }
        assertNull(result.pendingSave(), "save pipeline did not settle");
        return result;
    }

    private static <T> T worker(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation);
        Thread thread = new Thread(task);
        thread.start();
        thread.join(5000);
        assertFalse(thread.isAlive());
        try { return task.get(); }
        catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw (Error) failure.getCause();
        }
    }
}
