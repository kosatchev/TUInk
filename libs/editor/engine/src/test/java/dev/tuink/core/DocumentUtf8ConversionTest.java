package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentUtf8ConversionTest {
    private static final Charset CP1251 = Charset.forName("windows-1251");
    @TempDir Path directory;

    @Test
    void conversionPreservesMixedSeparatorsAndAddsOneIndependentUndoStep() throws Exception {
        EditorEngine engine = legacy();
        engine.insertInput("😀");
        var lines = engine.model().snapshotLines();
        var originalFormat = engine.model().fileMetadata();
        long textRevision = engine.model().revision();
        Path target = directory.resolve("utf8.txt");

        engine.saveAsUtf8(JvmDocumentStorage.resource(target), SaveDisposition.CREATE_NEW);

        assertArrayEquals("😀Привет\r\nмир\nконец\r".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
        assertEquals(BomKind.NONE, engine.model().fileMetadata().bomKind());
        assertEquals(originalFormat.lineSeparators(), engine.model().fileMetadata().lineSeparators());
        assertFalse(engine.state().isDirty());
        assertEquals(textRevision, engine.model().revision());
        long recoveryRevision = engine.recoveryRevision();
        assertTrue(engine.undo());
        assertEquals(lines, engine.model().snapshotLines());
        assertEquals(originalFormat, engine.model().fileMetadata());
        assertTrue(engine.state().isDirty());
        assertEquals(textRevision, engine.model().revision());
        assertTrue(engine.recoveryRevision() > recoveryRevision);
        assertTrue(engine.redo());
        assertEquals(StandardCharsets.UTF_8, engine.state().getFileEncoding());
        assertFalse(engine.state().isDirty());
        assertTrue(engine.undo());
        assertTrue(engine.undo());
        assertEquals("Привет", engine.model().getLine(0));
        assertEquals(CP1251, engine.state().getFileEncoding());
        assertTrue(engine.redo());
        assertEquals(lines, engine.model().snapshotLines());
        assertTrue(engine.redo());
        assertFalse(engine.state().isDirty());
    }

    @Test
    void failedWritePreservesFormatHistoryBaselineAndSavepoint() throws Exception {
        EditorEngine engine = legacy();
        engine.insertInput("😀");
        engine.undo(); // A failed conversion must also preserve redo.
        var format = engine.model().fileMetadata();
        var baseline = JvmDocumentStorage.baselineOf(engine.model().fileBaseline());
        Path source = JvmDocumentStorage.pathOf(engine.state().getCurrentResource());
        Path occupied = Files.writeString(directory.resolve("occupied.txt"), "untouched");

        assertThrows(IOException.class, () -> engine.saveAsUtf8(JvmDocumentStorage.resource(occupied), SaveDisposition.CREATE_NEW));

        assertEquals("untouched", Files.readString(occupied));
        assertEquals(format, engine.model().fileMetadata());
        assertEquals(baseline, JvmDocumentStorage.baselineOf(engine.model().fileBaseline()));
        assertEquals(source, JvmDocumentStorage.pathOf(engine.state().getCurrentResource()));
        assertFalse(engine.state().isDirty());
        assertTrue(engine.redo());
        assertEquals("😀Привет", engine.model().getLine(0));
        assertTrue(engine.state().isDirty());
    }

    @Test
    void conversionToCurrentPathKeepsTheOriginalConflictCheck() throws Exception {
        EditorEngine engine = legacy();
        engine.insertInput("😀");
        var metadata = engine.model().fileMetadata();
        Path source = JvmDocumentStorage.pathOf(engine.state().getCurrentResource());
        Files.writeString(source, "external");
        assertThrows(IOException.class, () -> engine.saveAsUtf8(JvmDocumentStorage.resource(source), SaveDisposition.REPLACE_EXISTING));
        assertEquals("external", Files.readString(source));
        assertEquals(metadata, engine.model().fileMetadata());
        assertTrue(engine.undo());
        assertEquals("Привет", engine.model().getLine(0));
    }

    @Test
    void utf16BomIsRemovedOnlyAfterSuccessfulConversion() throws Exception {
        Path source = directory.resolve("utf16.txt");
        Files.write(source, new byte[] {(byte) 0xFF, (byte) 0xFE, 65, 0});
        EditorEngine engine = open(source);
        Path target = directory.resolve("utf8.txt");
        engine.saveAsUtf8(JvmDocumentStorage.resource(target), SaveDisposition.CREATE_NEW);
        assertArrayEquals(new byte[] {65}, Files.readAllBytes(target));
        assertTrue(engine.undo());
        assertEquals(StandardCharsets.UTF_16LE, engine.state().getFileEncoding());
        assertEquals(BomKind.UTF_16_LE, engine.model().fileMetadata().bomKind());
        assertEquals(List.of("A"), engine.model().snapshotLines());
    }

    private EditorEngine legacy() throws IOException {
        return open(Files.write(directory.resolve("source.txt"), "Привет\r\nмир\nконец\r".getBytes(CP1251)));
    }

    private EditorEngine open(Path path) throws IOException {
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        engine.openExisting(JvmDocumentStorage.resource(path));
        return engine;
    }
}
