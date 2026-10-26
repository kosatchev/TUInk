package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.core.CoreDocumentBackend;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.TabSession;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextEdit;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.editor.document.WorkspaceEdit;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmStorageSessionRoundTripTest {
    @TempDir Path directory;

    @Test
    void publicSessionPreservesFileFormatHistoryAndRecoveryThroughJvmAdapter() throws Exception {
        Path source = directory.resolve("source.txt");
        byte[] original = "\uFEFForiginal\r\ntail\r".getBytes(StandardCharsets.UTF_8);
        Files.write(source, original);
        var resource = JvmDocumentStorage.resource(source);
        TabSession view = TabSession.createEmpty(LogicalEditorTextLayout::new, new EditorClipboard());
        var backend = CoreDocumentBackend.adopt(new DocumentId("jvm-source"), view);
        var session = EditorDocumentSession.connect(backend);
        session.openPrepared(worker(() -> resource.storage().load(resource, DocumentOpenMode.DETECT, false)));
        prepend(session, "draft ");
        long revision = session.snapshot().revision();
        Path destination = directory.resolve("copy.txt");
        var capture = session.prepareSaveAsInput(JvmDocumentStorage.resource(destination), SaveDisposition.CREATE_NEW, false);
        var inspection = worker(capture::inspectTarget);
        var operation = session.approveSaveAs(inspection);
        var receipt = worker(operation::write);
        assertTrue(session.status().dirty());
        assertEquals(resource.id(), session.status().binding().resource().id());
        session.completeSaved(operation, receipt);
        assertEquals(revision, session.snapshot().revision());
        assertFalse(session.status().dirty());
        assertArrayEquals(original, Files.readAllBytes(source));
        assertArrayEquals("\uFEFFdraft original\r\ntail\r".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(destination));
        assertTrue(session.undo());
        assertTrue(session.status().dirty());
        assertTrue(session.redo());
        assertFalse(session.status().dirty());

        prepend(session, "recovered ");
        var checkpoint = view.engine().captureRecoverySnapshot();
        TabSession restoredView = TabSession.createEmpty(LogicalEditorTextLayout::new, new EditorClipboard());
        var restoredBackend = CoreDocumentBackend.adopt(new DocumentId("jvm-restored"), restoredView);
        var restored = EditorDocumentSession.connect(restoredBackend);
        restored.restoreRecovery(restoredBackend.defaultViewId(), checkpoint);
        assertEquals(session.snapshot().lines(), restored.snapshot().lines());
        assertTrue(restored.status().dirty());
        var save = restored.prepareSave();
        restored.completeSaved(save, worker(save::write));
        assertFalse(restored.status().dirty());
        assertArrayEquals("\uFEFFrecovered draft original\r\ntail\r".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(destination));
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    private static void prepend(EditorDocumentSession session, String text) {
        var snapshot = session.snapshot();
        var origin = new TextPosition(0, 0);
        assertInstanceOf(WorkspaceEditResult.Applied.class, session.applyWorkspaceEdit(
            new WorkspaceEdit(snapshot.documentId(), snapshot.revision(),
                List.of(new TextEdit(new TextRange(origin, origin), text)))));
    }

    private static <T> T worker(Callable<T> task) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(task).get();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Exception exception) throw exception;
            if (failed.getCause() instanceof Error error) throw error;
            throw failed;
        } finally {
            executor.shutdownNow();
        }
    }
}
