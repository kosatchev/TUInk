package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.command.DocumentCommandTarget;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.format.DocumentEncoding;
import dev.tuink.editor.format.DocumentFormatSpec;
import dev.tuink.editor.format.LineEndingPolicy;
import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.StorageOperation;
import java.util.List;
import java.util.AbstractList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import org.junit.jupiter.api.Test;

class EditorEngineFormatAtomicityTest {
    @Test
    void mandatoryResultFailureRollsBackFormatHistorySavepointAndEveryView() throws Exception {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("one", "two", ""), new DocumentFileMetadata(
            java.nio.charset.StandardCharsets.UTF_8, BomKind.NONE, "\r\n", List.of("\r\n", "\r")));
        FaultEngine engine = new FaultEngine(model);
        engine.insertChar('X');
        engine.insertNewline();
        assertTrue(engine.undo());
        var other = engine.document().attach(LogicalEditorTextLayout::new, new EditorClipboard());
        engine.restoreSelection(new Position(1, 2), new Position(1, 1));
        engine.state().setViewport(new ViewportState(0, 0, 1, 2));
        other.engine().restoreSelection(new Position(0, 2), new Position(0, 0));
        var history = engine.document().history;
        var undo = history.undoStack;
        var redo = history.redoStack;
        var metadata = model.fileMetadata();
        var text = model.snapshotLines();
        var view = engine.state().viewMemento();
        var foreignView = other.state().viewMemento();
        var recovery = engine.recoveryRevision();
        var navigation = engine.navigationRevision();
        var stateId = history.currentDocumentStateId;
        var nextStateId = history.nextDocumentStateId;
        var savedStateId = history.savedDocumentStateId;
        var prepared = engine.prepareFormatChange(new DocumentFormatSpec(DocumentEncoding.UTF16BE_BOM, LineEndingPolicy.LF));
        var validation = prepared.perform(StorageOperation.untracked());
        engine.failResult = true;

        assertThrows(DocumentEditLimitException.class,
            () -> execute(engine, new DocumentCommand.SetDocumentFormat(prepared, validation)));

        assertEquals(metadata, model.fileMetadata());
        assertEquals(text, model.snapshotLines());
        assertEquals(view, engine.state().viewMemento());
        assertEquals(foreignView, other.state().viewMemento());
        assertEquals(recovery, engine.recoveryRevision());
        assertEquals(navigation, engine.navigationRevision());
        assertSame(undo, history.undoStack);
        assertSame(redo, history.redoStack);
        assertEquals(stateId, history.currentDocumentStateId);
        assertEquals(nextStateId, history.nextDocumentStateId);
        assertEquals(savedStateId, history.savedDocumentStateId);
        engine.failResult = false;
        var result = execute(engine, new DocumentCommand.SetDocumentFormat(prepared, validation));
        assertTrue(result.performed());
        assertFalse(result.textChanged());
        assertFalse(result.viewChanged());
        assertTrue(engine.undo());
        assertEquals(metadata, model.fileMetadata());
        assertThrows(IllegalStateException.class,
            () -> execute(engine, new DocumentCommand.SetDocumentFormat(prepared, validation)));
    }

    @Test
    void failedFormatUndoAndRedoPrepareNothingObservableUntilTheResultIsReady() throws Exception {
        var engine = new FaultEngine(new TextModel(new SimpleLineBuffer()));
        var before = engine.model().fileMetadata();
        var prepared = engine.prepareFormatChange(new DocumentFormatSpec(DocumentEncoding.UTF8_BOM, LineEndingPolicy.CR));
        execute(engine, new DocumentCommand.SetDocumentFormat(prepared, prepared.perform(StorageOperation.untracked())));
        var after = engine.model().fileMetadata();
        for (boolean undoing : List.of(true, false)) {
            var metadata = engine.model().fileMetadata();
            long recovery = engine.recoveryRevision();
            boolean dirty = engine.state().isDirty();
            assertThrows(DocumentEditLimitException.class, () -> {
                if (undoing) engine.undo(changed -> { throw new OutOfMemoryError("format undo result"); });
                else engine.redo(changed -> { throw new OutOfMemoryError("format redo result"); });
            });
            assertEquals(metadata, engine.model().fileMetadata());
            assertEquals(recovery, engine.recoveryRevision());
            assertEquals(dirty, engine.state().isDirty());
            assertTrue(undoing ? engine.undo() : engine.redo());
            assertEquals(undoing ? before : after, engine.model().fileMetadata());
            assertEquals(!undoing, engine.state().isDirty());
        }
    }

    @Test
    void foreignValidationAndForgedProofCannotChangeTheDocument() throws Exception {
        var first = new FaultEngine(new TextModel(new SimpleLineBuffer()));
        var second = new FaultEngine(new TextModel(new SimpleLineBuffer()));
        var spec = new DocumentFormatSpec(DocumentEncoding.UTF8_BOM, LineEndingPolicy.LF);
        var a = first.prepareFormatChange(spec);
        var b = second.prepareFormatChange(spec);
        var av = a.perform(StorageOperation.untracked());
        var bv = b.perform(StorageOperation.untracked());
        assertThrows(IllegalArgumentException.class, () -> execute(first, new DocumentCommand.SetDocumentFormat(a, bv)));
        assertThrows(IllegalArgumentException.class, () -> execute(second, new DocumentCommand.SetDocumentFormat(a, av)));
        assertThrows(IllegalStateException.class, () -> a.perform(StorageOperation.untracked()));
        assertFalse(first.state().isDirty());
        assertFalse(second.state().isDirty());
        assertFalse(first.undo());
        assertFalse(second.undo());
    }

    @Test
    void failedOwnerCaptureBecomesAnAdmissionFailureWithoutChangingTheDraft() {
        class SnapshotFaultModel extends TextModel {
            private boolean failSnapshot;
            SnapshotFaultModel() { super(new SimpleLineBuffer()); }
            @Override public List<LineText> snapshotTextLines() {
                if (failSnapshot) throw new OutOfMemoryError("format snapshot");
                return super.snapshotTextLines();
            }
        }
        var model = new SnapshotFaultModel();
        var engine = new FaultEngine(model);
        engine.insertChar('X');
        var before = model.fileMetadata();
        long revision = model.revision();
        long recovery = engine.recoveryRevision();
        model.failSnapshot = true;
        assertThrows(DocumentEditLimitException.class,
            () -> engine.prepareFormatChange(new DocumentFormatSpec(DocumentEncoding.CP866, LineEndingPolicy.LF)));
        model.failSnapshot = false;
        assertEquals("X", model.getLine(0));
        assertEquals(before, model.fileMetadata());
        assertEquals(revision, model.revision());
        assertEquals(recovery, engine.recoveryRevision());
        assertTrue(engine.undo());
        assertEquals("", model.getLine(0));
    }

    @Test
    void cancellationAfterValidationStartsAndAfterItFinishesCannotPublishFormat() throws Exception {
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var model = new TextModel(new SimpleLineBuffer()) {
            @Override public List<LineText> snapshotTextLines() {
                var captured = super.snapshotTextLines();
                return new AbstractList<>() {
                    @Override public int size() { return captured.size(); }
                    @Override public LineText get(int index) {
                        if (Thread.currentThread().getName().equals("format-cancel-validation")) {
                            entered.countDown();
                            try {
                                if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("Validation was not released");
                            } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                        }
                        return captured.get(index);
                    }
                };
            }
        };
        model.replaceLines(List.of("draft ".repeat(4096)));
        var engine = new FaultEngine(model);
        var before = model.fileMetadata();
        var spec = new DocumentFormatSpec(DocumentEncoding.UTF8_BOM, LineEndingPolicy.CR);
        var prepared = engine.prepareFormatChange(spec);
        var operation = StorageOperation.untracked();
        var work = new FutureTask<>(() -> prepared.perform(operation));
        var worker = new Thread(work, "format-cancel-validation");
        worker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(operation.requestCancellation());
        } finally { resume.countDown(); }
        var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> work.get(5, TimeUnit.SECONDS));
        worker.join();
        assertInstanceOf(java.io.InterruptedIOException.class, failure.getCause());
        assertEquals(before, model.fileMetadata());
        assertFalse(engine.state().isDirty());
        assertFalse(engine.undo());

        var ready = engine.prepareFormatChange(spec);
        var cancelledAfterReady = StorageOperation.untracked();
        var validation = ready.perform(cancelledAfterReady);
        assertTrue(cancelledAfterReady.requestCancellation());
        assertThrows(IllegalStateException.class,
            () -> execute(engine, new DocumentCommand.SetDocumentFormat(ready, validation)));
        assertEquals(before, model.fileMetadata());
        assertFalse(engine.state().isDirty());
    }

    @Test
    void unavailableOptionalCharsetReportsItsNameAndLeavesTheDraftIntact() throws Exception {
        class OptionalCharset extends Charset {
            private boolean available = true;
            OptionalCharset() { super("X-optional-test", new String[0]); }
            @Override public boolean contains(Charset other) { return StandardCharsets.UTF_8.contains(other); }
            @Override public CharsetDecoder newDecoder() { return StandardCharsets.UTF_8.newDecoder(); }
            @Override public CharsetEncoder newEncoder() {
                if (!available) throw new UnsupportedCharsetException(name());
                return StandardCharsets.UTF_8.newEncoder();
            }
        }
        var charset = new OptionalCharset();
        var model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("draft", "text"), new DocumentFileMetadata(charset, BomKind.NONE, "\n", List.of("\n")));
        var engine = new FaultEngine(model);
        var before = model.fileMetadata();
        long recovery = engine.recoveryRevision();
        var prepared = engine.prepareFormatChange(new DocumentFormatSpec(null, LineEndingPolicy.CR));
        charset.available = false;

        var failure = assertThrows(java.io.IOException.class, () -> prepared.perform(StorageOperation.untracked()));

        assertEquals("Requested document encoding unavailable: X-optional-test", failure.getMessage());
        assertInstanceOf(UnsupportedCharsetException.class, failure.getCause());
        assertEquals(before, model.fileMetadata());
        assertEquals(List.of("draft", "text"), model.snapshotLines());
        assertEquals(recovery, engine.recoveryRevision());
        assertFalse(engine.state().isDirty());
        assertFalse(engine.undo());
    }

    private static DocumentCommandResult.Applied execute(EditorEngine engine, DocumentCommand command) {
        return engine.executeCommand(new DocumentCommandTarget(new DocumentId("test"),
            new ViewId(engine.state().viewId()), engine.model().revision(), engine.recoveryRevision(),
            engine.navigationRevision()), command);
    }

    private static final class FaultEngine extends EditorEngine {
        private boolean failResult;
        private FaultEngine(TextModel model) {
            super(model, new EditorState(), new LogicalEditorTextLayout(model), new EditorClipboard());
        }
        @Override protected DocumentCommandResult.Applied prepareCommandResult(DocumentCommandTarget target,
            long revision, boolean textChanged, boolean viewChanged, boolean performed, int affectedCount,
            DocumentCommandResult.CommentAction commentAction) {
            if (failResult) throw new OutOfMemoryError("format result allocation");
            return super.prepareCommandResult(target, revision, textChanged, viewChanged, performed, affectedCount, commentAction);
        }
    }
}
