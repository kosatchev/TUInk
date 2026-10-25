package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.editor.storage.SavedDocument;
import dev.tuink.storage.jvm.FileBaseline;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorEnginePreparedFileIoTest {
    @TempDir Path directory;

    @Test
    void detachedWriteDoesNotAccessOrPublishToTheLiveModel() throws Exception {
        OwnerThreadModel model = new OwnerThreadModel();
        model.replaceLines(List.of("a😀b", "middle", "tail"), new DocumentFileMetadata(
            StandardCharsets.UTF_16LE, BomKind.UTF_16_LE, "\r\n", List.of("\r", "\n")));
        EditorEngine engine = engine(model);
        engine.insertChar('X');
        Path target = directory.resolve("detached.txt");
        Observed before = observe(engine);
        EditorEngine.PreparedSave prepared = engine.prepareSaveAs(JvmDocumentStorage.resource(target), SaveDisposition.CREATE_NEW, true);
        assertEquals(before, observe(engine));

        SavedDocument saved;
        try (var worker = Executors.newSingleThreadExecutor()) {
            saved = worker.submit(prepared::write).get();
        }

        assertEquals("Xa😀b\rmiddle\ntail", Files.readString(target));
        assertEquals(before, observe(engine), "Only owner-thread completion may adopt a write");
        engine.completeSaved(prepared, saved);
        assertEquals(target, JvmDocumentStorage.pathOf(engine.state().getCurrentResource()));
        assertEquals(StandardCharsets.UTF_8, model.fileMetadata().charset());
        assertEquals(BomKind.NONE, model.fileMetadata().bomKind());
        assertEquals(List.of("\r", "\n"), model.fileMetadata().lineSeparators());
        assertEquals(saved.baseline(), JvmDocumentStorage.baselineOf(model.fileBaseline()));
        assertFalse(engine.state().isDirty());
        Observed completed = observe(engine);
        assertThrows(IllegalStateException.class, () -> engine.completeSaved(prepared, saved));
        assertEquals(completed, observe(engine));
    }

    @Test
    void preparedBytesStayImmutableAndAnEditedOwnerCannotAdoptTheirSavepoint() throws Exception {
        EditorEngine engine = engine(new OwnerThreadModel());
        engine.insertInput("captured😀");
        Path target = directory.resolve("snapshot.txt");
        EditorEngine.PreparedSave prepared = engine.prepareSaveAs(JvmDocumentStorage.resource(target), SaveDisposition.CREATE_NEW, false);
        engine.moveCursorTo(engine.state().getCursor());
        engine.insertInput(" live edit");
        Observed edited = observe(engine);

        SavedDocument saved;
        try (var worker = Executors.newSingleThreadExecutor()) {
            saved = worker.submit(prepared::write).get();
        }

        assertEquals("captured😀", Files.readString(target));
        assertEquals("captured😀 live edit", engine.model().getLine(0));
        assertThrows(IllegalStateException.class, () -> engine.completeSaved(prepared, saved));
        assertEquals(edited, observe(engine));
        assertTrue(engine.state().isDirty());
        assertTrue(engine.undo());
        assertEquals("captured😀", engine.model().getLine(0));
        assertTrue(engine.undo());
        assertEquals("", engine.model().getLine(0));
    }

    @Test
    void utf8CompletionCreatesOneUndoableFormatTransitionAfterTheWrite() throws IOException {
        Path source = writeEncoded("source.txt", "a😀b\rmiddle\ntail", StandardCharsets.UTF_16LE, BomKind.UTF_16_LE);
        byte[] originalBytes = Files.readAllBytes(source);
        EditorEngine engine = open(source);
        engine.insertChar('X');
        engine.insertNewline();
        assertTrue(engine.undo());
        DocumentFileMetadata originalFormat = engine.model().fileMetadata();
        List<String> editedLines = engine.model().snapshotLines();
        Observed before = observe(engine);
        Path target = directory.resolve("utf8.txt");
        EditorEngine.PreparedSave prepared = engine.prepareSaveAs(JvmDocumentStorage.resource(target), SaveDisposition.CREATE_NEW, true);

        SavedDocument saved = prepared.write();

        assertEquals(before, observe(engine));
        assertArrayEquals(originalBytes, Files.readAllBytes(source));
        assertEquals("Xa😀b\rmiddle\ntail", Files.readString(target));
        engine.completeSaved(prepared, saved);
        assertEquals(editedLines, engine.model().snapshotLines());
        assertFalse(engine.state().isDirty());
        assertFalse(engine.redo(), "A format conversion replaces the old redo branch");
        assertTrue(engine.undo());
        assertEquals(originalFormat, engine.model().fileMetadata());
        assertEquals(editedLines, engine.model().snapshotLines());
        assertTrue(engine.state().isDirty());
        assertTrue(engine.redo());
        assertEquals(saved.metadata(), engine.model().fileMetadata());
        assertFalse(engine.state().isDirty());
        assertTrue(engine.undo());
        assertTrue(engine.undo());
        assertEquals(List.of("a😀b", "middle", "tail"), engine.model().snapshotLines());
    }

    @Test
    void failedEncodingAndFailedUtf8PublicationPreserveTextHistoryAndFormat() throws IOException {
        Charset legacy = Charset.forName("windows-1251");
        Path source = writeEncoded("legacy.txt", "первый\r\nстрока\nхвост", legacy, BomKind.NONE);
        byte[] sourceBytes = Files.readAllBytes(source);
        EditorEngine engine = open(source);
        engine.insertInput("😀");
        engine.insertNewline();
        assertTrue(engine.undo());
        Observed before = observe(engine);
        EditorEngine.PreparedSave legacySave = engine.prepareSave();

        assertThrows(DocumentEncodingException.class, legacySave::write);

        assertEquals(before, observe(engine));
        assertArrayEquals(sourceBytes, Files.readAllBytes(source));
        Path occupied = Files.writeString(directory.resolve("occupied.txt"), "someone else's file");
        EditorEngine.PreparedSave conversion = engine.prepareSaveAs(JvmDocumentStorage.resource(occupied), SaveDisposition.CREATE_NEW, true);
        assertEquals(before, observe(engine));

        assertThrows(FileAlreadyExistsException.class, conversion::write);

        assertEquals(before, observe(engine));
        assertEquals("someone else's file", Files.readString(occupied));
        assertArrayEquals(sourceBytes, Files.readAllBytes(source));
        assertTrue(engine.redo(), "A failed conversion must retain the redo branch");
        assertTrue(engine.undo());
        assertEquals(before.lines(), engine.model().snapshotLines());
        assertTrue(engine.undo());
        assertFalse(engine.state().isDirty());
    }

    @Test
    void aChangeOnDiskAfterPreparationCannotBypassTheOriginalBaseline() throws IOException {
        Path source = Files.writeString(directory.resolve("changed.txt"), "before");
        EditorEngine engine = open(source);
        engine.insertInput("mine ");
        EditorEngine.PreparedSave prepared = engine.prepareSave();
        Observed before = observe(engine);
        Files.writeString(source, "remote");

        IOException failure = assertThrows(IOException.class, prepared::write);

        assertTrue(failure.getMessage().contains("File changed outside TUInk"));
        assertEquals("remote", Files.readString(source));
        assertEquals(before, observe(engine));
    }

    @Test
    void completionRejectsChangedPathDispositionOrBaselineEvenWhenTextIsUnchanged() throws IOException {
        List<Consumer<EditorEngine>> changes = List.of(
            engine -> engine.state().setCurrentResource(JvmDocumentStorage.resource(directory.resolve("other.txt"))),
            engine -> engine.state().setSaveDisposition(SaveDisposition.CREATE_NEW),
            engine -> engine.model().restoreFileBaseline(null)
        );
        for (int index = 0; index < changes.size(); index++) {
            Path source = Files.writeString(directory.resolve("identity-" + index + ".txt"), "before");
            EditorEngine engine = open(source);
            engine.insertInput("mine ");
            EditorEngine.PreparedSave prepared = engine.prepareSave();
            SavedDocument saved = prepared.write();
            changes.get(index).accept(engine);
            Observed changed = observe(engine);

            assertThrows(IllegalStateException.class, () -> engine.completeSaved(prepared, saved));

            assertEquals(changed, observe(engine));
            assertTrue(engine.state().isDirty());
        }
    }

    @Test
    void savePreparationAllocationFailureIsControlledAndPreservesTheOwner() {
        final class FailingSnapshotModel extends TextModel {
            boolean fail;
            FailingSnapshotModel() { super(new SimpleLineBuffer()); }
            @Override public LineText lineText(int line) {
                if (fail) throw new OutOfMemoryError("save input snapshot allocation");
                return super.lineText(line);
            }
        }
        FailingSnapshotModel model = new FailingSnapshotModel();
        EditorEngine engine = engine(model);
        engine.insertChar('X');
        engine.insertNewline();
        assertTrue(engine.undo());
        Observed before = observe(engine);
        model.fail = true;

        IOException failure = assertThrows(IOException.class,
            () -> engine.prepareSaveAs(JvmDocumentStorage.resource(directory.resolve("memory.txt")), SaveDisposition.CREATE_NEW, true));

        model.fail = false;
        assertInstanceOf(OutOfMemoryError.class, failure.getCause());
        assertEquals(before, observe(engine));
        assertFalse(Files.exists(directory.resolve("memory.txt")));
        assertTrue(engine.redo());
    }

    @Test
    void preparedLoadIsImmutableAndPublishesWithoutReopeningTheFile() throws IOException {
        Path source = writeEncoded("read.txt", "first\rsecond\nthird", StandardCharsets.UTF_16LE, BomKind.UTF_16_LE);
        EditorEngine engine = engine(new TextModel(new SimpleLineBuffer()));
        engine.insertInput("unfinished");
        Observed before = observe(engine);

        DocumentLoad prepared = TextModel.prepareLoad(JvmDocumentStorage.resource(source), DocumentOpenMode.DETECT);

        assertEquals(before, observe(engine));
        assertThrows(UnsupportedOperationException.class, () -> prepared.lines().set(0, "different"));
        Files.delete(source);
        engine.openPrepared(prepared);
        assertEquals(List.of("first", "second", "third"), engine.model().snapshotLines());
        assertEquals(prepared.metadata(), engine.model().fileMetadata());
        assertEquals(prepared.baseline(), JvmDocumentStorage.baselineOf(engine.model().fileBaseline()));
        assertEquals(source, JvmDocumentStorage.pathOf(engine.state().getCurrentResource()));
        assertEquals(new Position(0, 0), engine.state().getCursor());
        assertFalse(engine.state().isDirty());
        assertFalse(engine.undo());
        assertFalse(engine.redo());
    }

    private EditorEngine open(Path path) throws IOException {
        EditorEngine engine = engine(new OwnerThreadModel());
        engine.openExisting(JvmDocumentStorage.resource(path));
        return engine;
    }

    private static EditorEngine engine(TextModel model) {
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        engine.setAutoPairsEnabled(false);
        engine.setAutoIndentEnabled(false);
        engine.setAutoListEnabled(false);
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        return engine;
    }

    private Path writeEncoded(String filename, String text, Charset charset, BomKind bom) throws IOException {
        byte[] prefix = bom.bytes();
        byte[] encoded = text.getBytes(charset);
        byte[] content = new byte[prefix.length + encoded.length];
        System.arraycopy(prefix, 0, content, 0, prefix.length);
        System.arraycopy(encoded, 0, content, prefix.length, encoded.length);
        return Files.write(directory.resolve(filename), content);
    }

    private static Observed observe(EditorEngine engine) {
        EditorDocumentHistory history = engine.document().history;
        return new Observed(engine.model().snapshotLines(), engine.model().fileMetadata(), JvmDocumentStorage.baselineOf(engine.model().fileBaseline()),
            JvmDocumentStorage.pathOf(engine.state().getCurrentResource()), engine.state().getSaveDisposition(), engine.state().viewMemento(),
            engine.state().getFileEncoding(), engine.state().getLineSeparator(), engine.state().isDirty(),
            engine.model().revision(), engine.recoveryRevision(), engine.navigationRevision(),
            List.copyOf(history.undoStack), List.copyOf(history.redoStack), history.currentDocumentStateId,
            history.nextDocumentStateId, history.savedDocumentStateId);
    }

    private record Observed(List<String> lines, DocumentFileMetadata metadata, FileBaseline baseline,
        Path path, SaveDisposition disposition, EditorViewMemento view, Charset reflectedEncoding,
        String reflectedSeparator, boolean dirty, long revision, long recovery, long navigation,
        List<EditorEngine.HistoryEntry> undo, List<EditorEngine.HistoryEntry> redo,
        long stateId, long nextStateId, long savedStateId) {}

    private static final class OwnerThreadModel extends TextModel {
        private final Thread owner = Thread.currentThread();
        OwnerThreadModel() { super(new SimpleLineBuffer()); }
        @Override public String getLine(int line) {
            if (Thread.currentThread() != owner) throw new AssertionError("The worker accessed the live model");
            return super.getLine(line);
        }
        @Override public LineText lineText(int line) {
            if (Thread.currentThread() != owner) throw new AssertionError("The worker accessed the live model");
            return super.lineText(line);
        }
    }
}
