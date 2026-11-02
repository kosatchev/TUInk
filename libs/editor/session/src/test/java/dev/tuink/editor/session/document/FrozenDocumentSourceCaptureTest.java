package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.document.DocumentId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FrozenDocumentSourceCaptureTest {
    @Test
    void ownerCaptureSharesThePublishedSourceAndDoesNotChangeWhenTheEditorChanges() {
        var session = EditorDocumentSession.create(new DocumentId("capture"), List.of("first  ", "\tlast", ""));
        var frozen = session.freezeSource();
        assertSame(session.snapshot(), frozen.text());
        assertSame(session.status().metadata(), frozen.metadata());
        assertEquals(session.status().binding(), frozen.binding());
        session.execute(session.defaultViewId(), new DocumentCommand.Input("edited "));
        assertEquals(List.of("first  ", "\tlast", ""), frozen.text().lines());
        assertEquals(List.of("edited first  ", "\tlast", ""), session.freezeSource().text().lines());
        assertEquals(2, frozen.metadata().lineSeparators().size());
    }

    @Test
    void workerCannotCombineAnOldPublishedSnapshotWithLiveFileState() throws Exception {
        var session = EditorDocumentSession.create(new DocumentId("owner"), List.of("text"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { session.freezeSource(); }
            catch (Throwable rejected) { failure.set(rejected); }
        });
        worker.start();
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertInstanceOf(IllegalStateException.class, failure.get());
    }
}
