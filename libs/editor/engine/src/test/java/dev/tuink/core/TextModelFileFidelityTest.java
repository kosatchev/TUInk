package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextModelFileFidelityTest {
    @TempDir
    Path tempDir;

    @Test
    void noEditSavePreservesUtf8BomFinalEolAndMixedSeparators() throws IOException {
        byte[] original = withBom(
            BomKind.UTF_8,
            "first\r\nsecond\nthird\rlast\r\n",
            StandardCharsets.UTF_8
        );
        Path source = tempDir.resolve("mixed.txt");
        Path target = tempDir.resolve("saved.txt");
        Files.write(source, original);
        TextModel model = new TextModel(new SimpleLineBuffer());

        TextModel.LoadMetadata loaded = model.load(source);
        model.save(target);

        assertEquals(List.of("first", "second", "third", "last", ""), model.snapshotLines());
        assertEquals(StandardCharsets.UTF_8, loaded.charset());
        assertEquals(BomKind.UTF_8, loaded.fileMetadata().bomKind());
        assertEquals(EditorState.LINE_SEPARATOR_CRLF, loaded.lineSeparator());
        assertEquals(
            List.of(
                EditorState.LINE_SEPARATOR_CRLF,
                EditorState.LINE_SEPARATOR_LF,
                EditorState.LINE_SEPARATOR_CR,
                EditorState.LINE_SEPARATOR_CRLF
            ),
            loaded.fileMetadata().lineSeparators()
        );
        assertArrayEquals(original, Files.readAllBytes(target));
    }

    @Test
    void noEditSavePreservesUtf16ByteOrderMarkers() throws IOException {
        assertNoEditRoundTrip(
            "utf16le.txt",
            BomKind.UTF_16_LE,
            StandardCharsets.UTF_16LE,
            "alpha\r\nβ\n"
        );
        assertNoEditRoundTrip(
            "utf16be.txt",
            BomKind.UTF_16_BE,
            StandardCharsets.UTF_16BE,
            "alpha\r\nβ\n"
        );
    }

    @Test
    void loadedAndInsertedFinalEolHaveOneCanonicalEditableRepresentation() throws IOException {
        Path source = tempDir.resolve("loaded-final-eol.txt");
        Path withoutFinalEol = tempDir.resolve("without-final-eol.txt");
        Files.writeString(source, "a\n", StandardCharsets.UTF_8);
        TextModel loaded = new TextModel(new SimpleLineBuffer());
        loaded.load(source);
        TextModel inserted = new TextModel(new SimpleLineBuffer());
        inserted.insertText(new Position(0, 0), "a\n");

        assertEquals(List.of("a", ""), loaded.snapshotLines());
        assertEquals(inserted.snapshotLines(), loaded.snapshotLines());
        assertEquals(inserted.fileMetadata(), loaded.fileMetadata());

        assertEquals(new Position(0, 1), loaded.deleteBackward(new Position(1, 0)));
        loaded.save(withoutFinalEol);
        assertEquals(List.of("a"), loaded.snapshotLines());
        assertEquals(List.of(), loaded.fileMetadata().lineSeparators());
        assertEquals("a", Files.readString(withoutFinalEol, StandardCharsets.UTF_8));
    }

    @Test
    void noEditSaveThroughEditorEnginePreservesFileBytes() throws IOException {
        byte[] original = withBom(
            BomKind.UTF_8,
            "one\ntwo\r\n",
            StandardCharsets.UTF_8
        );
        Path path = tempDir.resolve("engine.txt");
        Files.write(path, original);
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TestEditorTextLayout(model));

        engine.open(path);
        engine.save();

        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test
    void insertedLinesUseDefaultSeparatorWithoutChangingExistingSeparators() throws IOException {
        Path source = tempDir.resolve("mixed.txt");
        Path target = tempDir.resolve("edited.txt");
        Files.writeString(source, "a\r\nb\nc\r\n", StandardCharsets.UTF_8);
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(source);

        model.insertNewline(new Position(1, 1));
        model.save(target);

        assertEquals(
            List.of(
                EditorState.LINE_SEPARATOR_CRLF,
                EditorState.LINE_SEPARATOR_CRLF,
                EditorState.LINE_SEPARATOR_LF,
                EditorState.LINE_SEPARATOR_CRLF
            ),
            model.fileMetadata().lineSeparators()
        );
        assertEquals("a\r\nb\r\n\nc\r\n", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void recoverySnapshotRestoresFileMetadataForSubsequentSave() throws IOException {
        byte[] original = withBom(
            BomKind.UTF_8,
            "first\r\nsecond\n",
            StandardCharsets.UTF_8
        );
        Path source = tempDir.resolve("source.txt");
        Path restoredFile = tempDir.resolve("restored.txt");
        Files.write(source, original);
        TextModel sourceModel = new TextModel(new SimpleLineBuffer());
        sourceModel.load(source);
        EditorState sourceState = new EditorState();
        RecoverySnapshot snapshot = RecoverySnapshot.capture(sourceModel, sourceState);
        TextModel restoredModel = new TextModel(new SimpleLineBuffer());

        snapshot.restore(restoredModel, new EditorState());
        restoredModel.save(restoredFile);

        assertArrayEquals(original, Files.readAllBytes(restoredFile));
    }

    @Test
    void undoRestoresSeparatorRemovedByJoiningLines() throws IOException {
        Path source = tempDir.resolve("undo.txt");
        Files.writeString(source, "a\r\nb\nc", StandardCharsets.UTF_8);
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TestEditorTextLayout(model));
        engine.open(source);
        state.setCursor(new Position(1, 0));

        engine.backspace();
        assertEquals(List.of(EditorState.LINE_SEPARATOR_LF), model.fileMetadata().lineSeparators());

        engine.undo();

        assertEquals(
            List.of(EditorState.LINE_SEPARATOR_CRLF, EditorState.LINE_SEPARATOR_LF),
            model.fileMetadata().lineSeparators()
        );
    }

    @Test
    void operationHistoryRestoresMixedSeparatorsAfterMultilineSelectionEdit() throws IOException {
        byte[] original = withBom(
            BomKind.UTF_8,
            "one\r\ntwo\nthree\r\n",
            StandardCharsets.UTF_8
        );
        Path source = tempDir.resolve("operation-undo.txt");
        Path restored = tempDir.resolve("operation-restored.txt");
        Files.write(source, original);
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TestEditorTextLayout(model));
        engine.open(source);
        DocumentFileMetadata originalMetadata = model.fileMetadata();

        engine.beginSelectionAt(new Position(0, 1));
        engine.extendSelectionTo(new Position(2, 2));
        engine.insertChar('X');
        DocumentFileMetadata editedMetadata = model.fileMetadata();

        engine.undo();
        model.save(restored);
        assertEquals(originalMetadata, model.fileMetadata());
        assertArrayEquals(original, Files.readAllBytes(restored));

        engine.redo();
        assertEquals(editedMetadata, model.fileMetadata());
    }

    @Test
    void openingNewPathStartsAnEmptyCanonicalDocument() throws IOException {
        Path source = tempDir.resolve("source.txt");
        Files.write(
            source,
            withBom(BomKind.UTF_8, "one\r\ntwo\r\n", StandardCharsets.UTF_8)
        );
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TestEditorTextLayout(model));
        engine.open(source);

        engine.open(tempDir.resolve("new.txt"));

        assertEquals(StandardCharsets.UTF_8, model.fileMetadata().charset());
        assertEquals(BomKind.NONE, model.fileMetadata().bomKind());
        assertEquals(EditorState.LINE_SEPARATOR_LF, model.fileMetadata().defaultLineSeparator());
        assertEquals(List.of(""), model.snapshotLines());
        assertEquals(List.of(), model.fileMetadata().lineSeparators());
        assertFalse(state.isDirty());
    }

    @Test
    void unmappableEditsFailInsteadOfSilentlyCorruptingTheOriginalFile() throws IOException {
        Charset windows1251 = Charset.forName("windows-1251");
        byte[] original = "Привет".getBytes(windows1251);
        Path source = tempDir.resolve("cp1251.txt");
        Files.write(source, original);
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(source);
        model.insertText(new Position(0, model.getLine(0).length()), "😀");

        assertThrows(IOException.class, () -> model.save(source));

        assertArrayEquals(original, Files.readAllBytes(source));
        assertEquals(windows1251, model.fileMetadata().charset());
    }

    @Test
    void malformedBomDeclaredInputIsRejectedBeforeReplacingTheDocument() throws IOException {
        Path source = tempDir.resolve("malformed-utf8.txt");
        Files.write(source, new byte[] {
            (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, (byte) 0xC3, 0x28
        });
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), "safe");
        long revision = model.revision();

        assertThrows(IOException.class, () -> model.load(source));

        assertEquals(List.of("safe"), model.snapshotLines());
        assertEquals(revision, model.revision());
    }

    @Test
    void malformedFallbackEncodingIsRejectedBeforeReplacingTheDocument() throws IOException {
        Path source = tempDir.resolve("malformed-cp1251.txt");
        Files.write(source, new byte[] {(byte) 0x98});
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), "safe");
        long revision = model.revision();

        assertThrows(IOException.class, () -> model.load(source));

        assertEquals(List.of("safe"), model.snapshotLines());
        assertEquals(revision, model.revision());
    }

    @Test
    void metadataRejectsCharsetsThatWouldEmitAnUntrackedBom() {
        assertThrows(
            IllegalArgumentException.class,
            () -> DocumentFileMetadata.canonical(
                StandardCharsets.UTF_16,
                BomKind.NONE,
                EditorState.LINE_SEPARATOR_LF,
                1
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> DocumentFileMetadata.canonical(
                StandardCharsets.UTF_16LE,
                BomKind.NONE,
                EditorState.LINE_SEPARATOR_LF,
                1
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> DocumentFileMetadata.canonical(
                StandardCharsets.UTF_16BE,
                BomKind.NONE,
                EditorState.LINE_SEPARATOR_LF,
                1
            )
        );
    }

    @Test
    void explicitUtf16ExportsAlwaysIncludeTheMatchingBomAndReopenCorrectly() throws IOException {
        for (Charset charset : List.of(StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.insertText(new Position(0, 0), "TUInk 😀");
            Path target = tempDir.resolve("explicit-" + charset.name() + ".txt");

            model.save(target, charset, EditorState.LINE_SEPARATOR_LF);

            BomKind expectedBom = StandardCharsets.UTF_16LE.equals(charset)
                ? BomKind.UTF_16_LE
                : BomKind.UTF_16_BE;
            byte[] bytes = Files.readAllBytes(target);
            assertTrue(startsWith(bytes, expectedBom.bytes()));
            TextModel reopened = new TextModel(new SimpleLineBuffer());
            reopened.load(target);
            assertEquals(List.of("TUInk 😀"), reopened.snapshotLines());
            assertEquals(charset, reopened.fileMetadata().charset());
            assertEquals(expectedBom, reopened.fileMetadata().bomKind());
        }
    }

    @Test
    void lineOrientedBoundariesRejectEmbeddedSeparatorsBeforeMutation() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        long revision = model.revision();

        assertThrows(
            IllegalArgumentException.class,
            () -> model.replaceLines(List.of("one\ntwo"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new RecoverySnapshot(
                List.of("one\rtwo"),
                new Position(0, 0),
                null,
                null,
                true,
                null,
                SaveDisposition.CREATE_NEW,
                null,
                null,
                null
            )
        );

        assertEquals(List.of(""), model.snapshotLines());
        assertEquals(revision, model.revision());
    }

    private void assertNoEditRoundTrip(
        String name,
        BomKind bomKind,
        Charset charset,
        String text
    ) throws IOException {
        byte[] original = withBom(bomKind, text, charset);
        Path source = tempDir.resolve(name);
        Path target = tempDir.resolve("saved-" + name);
        Files.write(source, original);
        TextModel model = new TextModel(new SimpleLineBuffer());

        TextModel.LoadMetadata loaded = model.load(source);
        model.save(target);

        assertEquals(charset, loaded.charset());
        assertEquals(bomKind, loaded.fileMetadata().bomKind());
        assertArrayEquals(original, Files.readAllBytes(target));
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] withBom(BomKind bomKind, String text, Charset charset) {
        byte[] bom = bomKind.bytes();
        byte[] encoded = text.getBytes(charset);
        byte[] out = new byte[bom.length + encoded.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(encoded, 0, out, bom.length, encoded.length);
        return out;
    }
}
