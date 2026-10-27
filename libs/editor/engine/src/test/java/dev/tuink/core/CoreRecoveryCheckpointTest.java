package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CoreRecoveryCheckpointTest {
    @Test
    void workerMaterializesOnlyCapturedTextFormatResourceAndTheRequestedViewsSelection() throws Exception {
        var storage = new MemoryDocumentStorage();
        storage.put("source.txt", "\ufefffirst\r\ntail".getBytes(StandardCharsets.UTF_16LE));
        var first = TabSession.createEmpty(LogicalEditorTextLayout::new, new EditorClipboard());
        var backend = CoreDocumentBackend.adopt(new DocumentId("checkpoint"), first);
        var second = first.engine().document().attach(LogicalEditorTextLayout::new, new EditorClipboard());
        var secondId = backend.registerView(second);
        backend.openPrepared(worker(() -> storage.load(storage.resource("source.txt"), DocumentOpenMode.DETECT, false)));
        first.engine().insertInput("draft ");
        second.engine().restoreSelection(new Position(1, 2), new Position(0, 1));
        var status = backend.status();
        int calls = storage.calls();

        var prepared = backend.prepareRecovery(secondId);
        assertEquals(calls, storage.calls());
        first.engine().insertNewline();
        second.engine().moveDocumentStart(false);
        var checkpoint = worker(prepared::materialize);

        assertEquals(List.of("draft first", "tail"), checkpoint.lines());
        assertEquals(new TextPosition(1, 2), checkpoint.caret());
        assertEquals(new TextPosition(0, 1), checkpoint.anchor());
        assertTrue(checkpoint.dirty());
        assertEquals(status.metadata(), checkpoint.fileMetadata());
        assertEquals(status.binding().resource(), checkpoint.currentResource());
        assertEquals(status.binding().version(), checkpoint.fileBaseline());
        assertEquals(status.binding().disposition(), checkpoint.saveDisposition());
        assertEquals(calls, storage.calls());
        assertNotEquals(checkpoint.lines(), backend.snapshot().lines());
        assertNotEquals(checkpoint.caret(), backend.view(secondId).cursor());
    }

    @Test
    void onlyTheOwnerCanCaptureARegisteredView() throws Exception {
        var backend = CoreDocumentBackend.create(new DocumentId("owner"), List.of("original"));
        assertThrows(IllegalStateException.class, () -> worker(() -> backend.prepareRecovery(backend.defaultViewId())));
        assertThrows(IllegalArgumentException.class, () -> backend.prepareRecovery(new ViewId(Long.MAX_VALUE)));
        assertEquals(List.of("original"), backend.snapshot().lines());
    }

    private static <T> T worker(Callable<T> work) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            try { return executor.submit(work).get(10, TimeUnit.SECONDS); }
            catch (ExecutionException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                throw (Error) failure.getCause();
            }
        } finally { executor.shutdownNow(); }
    }
}
