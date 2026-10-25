package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.storage.jvm.FileBaseline;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MixedLineSeparatorEditingTest {
    @TempDir
    Path directory;

    @Test
    void emptyingALineBetweenCrAndLfRoundTripsAndKeepsExactHistory() throws Exception {
        for (int deletion = 0; deletion < 3; deletion++) {
            Path file = write("delete-" + deletion + ".txt", "a\rb\nc");
            Fixture fixture = open(file);
            DocumentFileMetadata original = fixture.model.fileMetadata();
            if (deletion == 0) {
                fixture.engine.moveCursorTo(new Position(1, 1));
                fixture.engine.backspace();
            } else if (deletion == 1) {
                fixture.engine.moveCursorTo(new Position(1, 0));
                fixture.engine.deleteForward();
            } else {
                fixture.engine.restoreSelection(new Position(1, 1), new Position(1, 0));
                fixture.engine.cutSelection();
            }

            assertEquals(List.of("a", "", "c"), fixture.model.snapshotLines());
            assertEquals(List.of("\r", "\r\n"), fixture.model.fileMetadata().lineSeparators());
            DocumentFileMetadata edited = fixture.model.fileMetadata();
            fixture.engine.save();
            assertEquals("a\r\r\nc", Files.readString(file));
            assertReopened(file, fixture.model.snapshotLines(), edited);
            assertFalse(fixture.state.isDirty());
            assertBaseline(fixture, file);

            assertTrue(fixture.engine.undo());
            assertEquals(List.of("a", "b", "c"), fixture.model.snapshotLines());
            assertEquals(original, fixture.model.fileMetadata());
            assertTrue(fixture.state.isDirty());
            assertTrue(fixture.engine.redo());
            assertEquals(edited, fixture.model.fileMetadata());
            assertFalse(fixture.state.isDirty(), "redo returns to the saved edited state");

            assertTrue(fixture.engine.undo());
            fixture.engine.save();
            assertEquals("a\rb\nc", Files.readString(file));
            assertFalse(fixture.state.isDirty());
            assertBaseline(fixture, file);
            assertTrue(fixture.engine.redo());
            assertTrue(fixture.state.isDirty());
            fixture.engine.save();
            assertReopened(file, List.of("a", "", "c"), edited);
        }
    }

    @Test
    void insertingAnEmptyLfLineAfterCrKeepsTheNewLogicalLine() throws Exception {
        Path file = write("insert.txt", "a\rb\nc\nd");
        Fixture fixture = open(file);
        DocumentFileMetadata original = fixture.model.fileMetadata();
        fixture.engine.moveCursorTo(new Position(1, 0));

        fixture.engine.insertNewline();

        assertEquals(List.of("a", "", "b", "c", "d"), fixture.model.snapshotLines());
        assertEquals(List.of("\r", "\r\n", "\n", "\n"), fixture.model.fileMetadata().lineSeparators());
        fixture.engine.save();
        assertReopened(file, fixture.model.snapshotLines(), fixture.model.fileMetadata());
        assertTrue(fixture.engine.undo());
        assertEquals(List.of("a", "b", "c", "d"), fixture.model.snapshotLines());
        assertEquals(original, fixture.model.fileMetadata());
        assertTrue(fixture.engine.redo());
        assertFalse(fixture.state.isDirty());
    }

    @Test
    void replacingNonemptyContentDoesNotNormalizeATemporaryEmptyLine() throws Exception {
        for (int replacement = 0; replacement < 4; replacement++) {
            Path file = write("replace-" + replacement + ".txt", "a\rb\nc");
            Fixture fixture = open(file);
            DocumentFileMetadata original = fixture.model.fileMetadata();
            fixture.engine.restoreSelection(new Position(1, 1), new Position(1, 0));

            switch (replacement) {
                case 0 -> fixture.engine.insertChar('B');
                case 1 -> fixture.engine.pasteText("B");
                case 2 -> fixture.engine.replaceCurrentOrNext("b", "B");
                case 3 -> fixture.engine.applyTextReplacements(List.of(new TextReplacement(
                    new Position(1, 0), new Position(1, 1), "B"
                )));
                default -> throw new AssertionError();
            }

            assertEquals(original, fixture.model.fileMetadata());
            fixture.engine.save();
            assertEquals("a\rB\nc", Files.readString(file));
            assertTrue(fixture.engine.undo());
            assertEquals(List.of("a", "b", "c"), fixture.model.snapshotLines());
            assertEquals(original, fixture.model.fileMetadata());
            assertTrue(fixture.engine.redo());
            assertEquals(original, fixture.model.fileMetadata());
        }
    }

    @Test
    void coalescedDeletionRestoresTheOriginalBoundarySeparators() throws Exception {
        Fixture fixture = open(write("coalesced.txt", "a\rbc\nz"));
        DocumentFileMetadata original = fixture.model.fileMetadata();
        fixture.engine.moveCursorTo(new Position(1, 2));

        fixture.engine.backspace();
        fixture.engine.backspace();

        assertEquals(List.of("a", "", "z"), fixture.model.snapshotLines());
        assertEquals(List.of("\r", "\r\n"), fixture.model.fileMetadata().lineSeparators());
        assertTrue(fixture.engine.undo());
        assertEquals(List.of("a", "bc", "z"), fixture.model.snapshotLines());
        assertEquals(original, fixture.model.fileMetadata());
        assertFalse(fixture.engine.undo());
        assertTrue(fixture.engine.redo());
        assertEquals(List.of("\r", "\r\n"), fixture.model.fileMetadata().lineSeparators());
    }

    @Test
    void ordinaryCharacterHistoryDoesNotRebuildUnchangedSeparatorMetadata() throws Exception {
        Fixture fixture = open(write("ordinary.txt", "one\ntwo\nthree"));
        DocumentFileMetadata metadata = fixture.model.fileMetadata();
        fixture.engine.moveCursorTo(new Position(1, 0));
        for (char ch : "abc".toCharArray()) {
            fixture.engine.insertChar(ch);
        }

        assertSame(metadata, fixture.model.fileMetadata());
        assertTrue(fixture.engine.undo());
        assertEquals("two", fixture.model.getLine(1));
        assertSame(metadata, fixture.model.fileMetadata(), "coalesced character undo must not copy all document EOLs");
        assertTrue(fixture.engine.redo());
        assertEquals("abctwo", fixture.model.getLine(1));
        assertSame(metadata, fixture.model.fileMetadata());

        fixture.engine.moveCursorTo(new Position(0, 0));
        fixture.engine.insertChar('X');
        assertTrue(fixture.engine.undo());
        assertEquals("one", fixture.model.getLine(0));
        assertSame(metadata, fixture.model.fileMetadata());
        assertTrue(fixture.engine.redo());
        assertEquals("Xone", fixture.model.getLine(0));
        assertSame(metadata, fixture.model.fileMetadata());
    }

    @Test
    void aSnapshotBatchNormalizesOnlyNewCollisionsAndRestoresThemTogether() throws Exception {
        Path file = write("batch.txt", "a\rb\nc\rd\ne");
        Fixture fixture = open(file);
        DocumentFileMetadata original = fixture.model.fileMetadata();

        fixture.engine.applyTextReplacements(List.of(
            new TextReplacement(new Position(1, 0), new Position(1, 1), ""),
            new TextReplacement(new Position(3, 0), new Position(3, 1), "")
        ));

        assertEquals(List.of("a", "", "c", "", "e"), fixture.model.snapshotLines());
        assertEquals(List.of("\r", "\r\n", "\r", "\r\n"), fixture.model.fileMetadata().lineSeparators());
        fixture.engine.save();
        assertReopened(file, fixture.model.snapshotLines(), fixture.model.fileMetadata());
        assertTrue(fixture.engine.undo());
        assertEquals(List.of("a", "b", "c", "d", "e"), fixture.model.snapshotLines());
        assertEquals(original, fixture.model.fileMetadata());
        assertTrue(fixture.engine.redo());
        assertFalse(fixture.state.isDirty());
    }

    @Test
    void directModelSaveCommitsCanonicalMetadataOnlyAfterTheFileCommit() throws Exception {
        Path source = write("source.txt", "a\rb\nc");
        Path occupied = write("occupied.txt", "keep");
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(JvmDocumentStorage.resource(source));
        model.deleteRange(new Position(1, 0), new Position(1, 1));
        DocumentFileMetadata before = model.fileMetadata();
        FileBaseline baseline = JvmDocumentStorage.baselineOf(model.fileBaseline());
        long revision = model.revision();

        assertThrows(IOException.class, () -> model.save(JvmDocumentStorage.resource(occupied), before, SaveDisposition.CREATE_NEW));

        assertEquals("keep", Files.readString(occupied));
        assertEquals("a\rb\nc", Files.readString(source));
        assertEquals(before, model.fileMetadata());
        assertEquals(baseline, JvmDocumentStorage.baselineOf(model.fileBaseline()));
        assertEquals(revision, model.revision());
        model.save(JvmDocumentStorage.resource(source));
        assertEquals(List.of("\r", "\r\n"), model.fileMetadata().lineSeparators());
        assertReopened(source, List.of("a", "", "c"), model.fileMetadata());
    }

    @Test
    void replacementAndRecoveryAcceptOlderAmbiguousMetadataWithoutLosingLines() throws Exception {
        TextModel model = new TextModel(new SimpleLineBuffer());
        DocumentFileMetadata ambiguous = new DocumentFileMetadata(
            StandardCharsets.UTF_8, BomKind.NONE, "\r", List.of("\r", "\n")
        );
        model.replaceLines(List.of("a", "", "c"), ambiguous);
        assertEquals(List.of("\r", "\r\n"), model.fileMetadata().lineSeparators());
        RecoverySnapshot snapshot = new RecoverySnapshot(
            List.of("a", "", "c"), new Position(1, 0), new ViewportState(0, 0, 0, 0), null,
            true, null, SaveDisposition.CREATE_NEW, ambiguous, null, Instant.EPOCH
        );
        assertEquals(ambiguous, snapshot.fileMetadata(), "simulate an older snapshot before collision normalization");
        TextModel restored = new TextModel(new SimpleLineBuffer());
        EditorEngine restoredEngine = new EditorEngine(restored, new EditorState(), new LogicalEditorTextLayout(restored));
        restoredEngine.restoreRecoverySnapshot(snapshot);
        assertEquals(List.of("\r", "\r\n"), restored.fileMetadata().lineSeparators());
        Path file = directory.resolve("recovered.txt");
        restoredEngine.saveAs(JvmDocumentStorage.resource(file));
        assertReopened(file, List.of("a", "", "c"), restored.fileMetadata());
    }

    @Test
    void anExternalSaveConflictKeepsNormalizedEditsAndTheirExactHistory() throws Exception {
        Path file = write("conflict.txt", "a\rb\nc");
        Fixture fixture = open(file);
        DocumentFileMetadata original = fixture.model.fileMetadata();
        FileBaseline baseline = JvmDocumentStorage.baselineOf(fixture.model.fileBaseline());
        fixture.engine.moveCursorTo(new Position(1, 1));
        fixture.engine.backspace();
        DocumentFileMetadata edited = fixture.model.fileMetadata();
        Files.writeString(file, "outside");

        assertThrows(IOException.class, fixture.engine::save);

        assertEquals("outside", Files.readString(file));
        assertEquals(List.of("a", "", "c"), fixture.model.snapshotLines());
        assertEquals(edited, fixture.model.fileMetadata());
        assertEquals(baseline, JvmDocumentStorage.baselineOf(fixture.model.fileBaseline()));
        assertTrue(fixture.state.isDirty());
        assertTrue(fixture.engine.undo());
        assertEquals(List.of("a", "b", "c"), fixture.model.snapshotLines());
        assertEquals(original, fixture.model.fileMetadata());
        assertEquals(new Position(1, 1), fixture.state.getCursor());
        assertFalse(fixture.state.isDirty());
        assertTrue(fixture.engine.redo());
        assertEquals(List.of("a", "", "c"), fixture.model.snapshotLines());
        assertEquals(edited, fixture.model.fileMetadata());
        assertEquals(new Position(1, 0), fixture.state.getCursor());
        assertEquals(baseline, JvmDocumentStorage.baselineOf(fixture.model.fileBaseline()));
        assertTrue(fixture.state.isDirty());
        assertEquals("outside", Files.readString(file));
    }

    @Test
    void utf8AndUtf16BomAndEncodingRemainExactAcrossSaveUndoAndRedo() throws Exception {
        record Encoding(Charset charset, BomKind bom) {}
        for (Encoding encoding : List.of(
            new Encoding(StandardCharsets.UTF_8, BomKind.UTF_8),
            new Encoding(StandardCharsets.UTF_16LE, BomKind.UTF_16_LE),
            new Encoding(StandardCharsets.UTF_16BE, BomKind.UTF_16_BE)
        )) {
            Path file = directory.resolve(encoding.charset().name() + ".txt");
            byte[] original = encoded("α\rb\n中", encoding.charset(), encoding.bom());
            Files.write(file, original);
            Fixture fixture = open(file);
            fixture.engine.moveCursorTo(new Position(1, 1));
            fixture.engine.backspace();
            fixture.engine.save();
            assertArrayEquals(encoded("α\r\r\n中", encoding.charset(), encoding.bom()), Files.readAllBytes(file));
            assertReopened(file, List.of("α", "", "中"), fixture.model.fileMetadata());
            assertTrue(fixture.engine.undo());
            fixture.engine.save();
            assertArrayEquals(original, Files.readAllBytes(file));
            assertTrue(fixture.engine.redo());
            fixture.engine.save();
            assertReopened(file, List.of("α", "", "中"), fixture.model.fileMetadata());
        }
    }

    private Path write(String name, String text) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, text);
        return file;
    }

    private static Fixture open(Path file) throws IOException {
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.openExisting(JvmDocumentStorage.resource(file));
        return new Fixture(model, state, engine);
    }

    private static void assertReopened(Path file, List<String> lines, DocumentFileMetadata metadata) throws IOException {
        TextModel reopened = new TextModel(new SimpleLineBuffer());
        reopened.load(JvmDocumentStorage.resource(file));
        assertEquals(lines, reopened.snapshotLines());
        assertEquals(metadata, reopened.fileMetadata());
    }

    private static void assertBaseline(Fixture fixture, Path file) throws Exception {
        assertEquals(file.toRealPath(), JvmDocumentStorage.baselineOf(fixture.model.fileBaseline()).target());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))),
            JvmDocumentStorage.baselineOf(fixture.model.fileBaseline()).sha256());
    }

    private static byte[] encoded(String text, Charset charset, BomKind bom) {
        byte[] content = text.getBytes(charset);
        byte[] marker = bom.bytes();
        byte[] result = new byte[marker.length + content.length];
        System.arraycopy(marker, 0, result, 0, marker.length);
        System.arraycopy(content, 0, result, marker.length, content.length);
        return result;
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {}
}
