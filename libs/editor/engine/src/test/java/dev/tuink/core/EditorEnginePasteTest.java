package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorEnginePasteTest {
    @Test
    void singleCharactersAreInsertedWithoutPairingOrOvertyping() {
        for (String text : List.of("(", "[", "{", "\"", "'")) {
            Fixture fixture = fixture("");

            assertTrue(fixture.engine.pasteText(text));

            assertEquals(text, fixture.model.getLine(0));
            assertEquals(new Position(0, 1), fixture.state.getCursor());
        }
        for (String text : List.of(")", "]", "}", "\"", "'")) {
            Fixture fixture = fixture(text);

            assertTrue(fixture.engine.pasteText(text));

            assertEquals(text + text, fixture.model.getLine(0));
            assertEquals(new Position(0, 1), fixture.state.getCursor());
        }
    }

    @Test
    void pastingDoesNotRunKeywordDedenting() {
        Fixture fixture = fixture("if ready:", "    work()", "    ");
        fixture.engine.moveCursorTo(new Position(2, 4));

        fixture.engine.pasteText("else:");

        assertEquals("    else:", fixture.model.getLine(2));
        assertEquals(new Position(2, 9), fixture.state.getCursor());
    }

    @Test
    void bothPasteSourcesHaveTheirOwnUndoStepBetweenTypingEdits() {
        for (boolean applicationClipboard : List.of(false, true)) {
            Fixture fixture = fixture("");
            fixture.clipboard.setText("PASTED");
            fixture.engine.insertChar('a');

            if (applicationClipboard) {
                fixture.engine.pasteClipboard();
            } else {
                fixture.engine.pasteText("PASTED");
            }
            fixture.engine.insertChar('b');

            assertTrue(fixture.engine.undo());
            assertEquals("aPASTED", fixture.model.getLine(0));
            assertTrue(fixture.engine.undo());
            assertEquals("a", fixture.model.getLine(0));
            assertTrue(fixture.engine.undo());
            assertEquals("", fixture.model.getLine(0));
            assertFalse(fixture.state.isDirty());
            assertFalse(fixture.engine.undo());
            assertTrue(fixture.engine.redo());
            assertTrue(fixture.engine.redo());
            assertTrue(fixture.engine.redo());
            assertEquals("aPASTEDb", fixture.model.getLine(0));
            assertFalse(fixture.engine.redo());
        }
    }

    @Test
    void externalPasteKeepsTheApplicationClipboard() {
        Fixture fixture = fixture("");
        fixture.clipboard.setText("internal");

        fixture.engine.pasteText("external");

        assertEquals("internal", fixture.clipboard.text());
        assertTrue(fixture.engine.pasteClipboard());
        assertEquals("externalinternal", fixture.model.getLine(0));
        assertEquals("internal", fixture.clipboard.text());
    }

    @Test
    void malformedAndEmptyPastesPreserveTheSelectionAndHistory() {
        Fixture fixture = fixture("keep selected text");
        fixture.engine.restoreSelection(new Position(0, 13), new Position(0, 5));
        EditorViewMemento view = fixture.state.viewMemento();
        long revision = fixture.model.revision();
        long navigationRevision = fixture.engine.navigationRevision();

        assertThrows(IllegalArgumentException.class, () -> fixture.engine.pasteText("bad\uD800"));
        assertThrows(IllegalArgumentException.class, () -> fixture.engine.pasteText("\uDC00bad"));
        assertFalse(fixture.engine.pasteText(""));

        assertEquals("keep selected text", fixture.model.getLine(0));
        assertEquals(view, fixture.state.viewMemento());
        assertEquals(revision, fixture.model.revision());
        assertEquals(navigationRevision, fixture.engine.navigationRevision());
        assertFalse(fixture.state.isDirty());
        assertFalse(fixture.engine.undo());
    }

    @Test
    void literalUnicodePastePreservesFileMetadataThroughUndoRedoAndSave(@TempDir Path directory) throws Exception {
        String original = "\uFEFFone\r\ntwo\nthree\rfour";
        Path path = directory.resolve("mixed.txt");
        for (boolean reversed : List.of(false, true)) {
            Files.writeString(path, original, StandardCharsets.UTF_8);
            Fixture fixture = fixture("");
            fixture.engine.open(JvmDocumentStorage.resource(path));
            Position start = new Position(0, 1);
            Position end = new Position(2, 2);
            fixture.engine.restoreSelection(reversed ? start : end, reversed ? end : start);
            DocumentFileMetadata before = fixture.model.fileMetadata();

            fixture.engine.pasteText("👩‍💻\rnew\r\nx");

            assertEquals(List.of("o👩‍💻", "new", "xree", "four"), fixture.model.snapshotLines());
            assertEquals(List.of("\r\n", "\r\n", "\r"), fixture.model.fileMetadata().lineSeparators());
            assertEquals(BomKind.UTF_8, fixture.model.fileMetadata().bomKind());
            assertEquals(StandardCharsets.UTF_8, fixture.state.getFileEncoding());
            assertEquals(new Position(2, 1), fixture.state.getCursor());
            assertNull(fixture.state.getSelectionAnchor());
            DocumentFileMetadata after = fixture.model.fileMetadata();
            assertTrue(fixture.engine.undo());
            assertEquals(List.of("one", "two", "three", "four"), fixture.model.snapshotLines());
            assertEquals(before, fixture.model.fileMetadata());
            assertEquals(reversed ? start : end, fixture.state.getCursor());
            assertEquals(reversed ? end : start, fixture.state.getSelectionAnchor());
            assertFalse(fixture.state.isDirty());
            assertFalse(fixture.engine.undo());
            assertTrue(fixture.engine.redo());
            assertEquals(after, fixture.model.fileMetadata());
            fixture.engine.save();
            assertArrayEquals("\uFEFFo👩‍💻\r\nnew\r\nxree\rfour".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(path));
            assertFalse(fixture.state.isDirty());
        }
    }

    @Test
    void identicalLogicalPastePreservesMixedLineEndingsAndTheCleanSavepoint(@TempDir Path directory) throws Exception {
        byte[] original = "\uFEFFone\r\ntwo\nthree\rfour".getBytes(StandardCharsets.UTF_8);
        Path path = directory.resolve("identical.txt");
        for (boolean reversed : List.of(false, true)) {
            Files.write(path, original);
            Fixture fixture = fixture("");
            fixture.engine.open(JvmDocumentStorage.resource(path));
            Position start = new Position(0, 1);
            Position end = new Position(2, 2);
            fixture.engine.restoreSelection(reversed ? start : end, reversed ? end : start);
            DocumentFileMetadata metadata = fixture.model.fileMetadata();
            long revision = fixture.model.revision();

            assertTrue(fixture.engine.pasteText("ne\r\ntwo\rth"));

            assertEquals(revision, fixture.model.revision());
            assertEquals(metadata, fixture.model.fileMetadata());
            assertEquals(end, fixture.state.getCursor());
            assertNull(fixture.state.getSelectionAnchor());
            assertFalse(fixture.state.isDirty());
            assertFalse(fixture.engine.undo());
            assertFalse(fixture.engine.redo());
            fixture.engine.save();
            assertArrayEquals(original, Files.readAllBytes(path));
        }
    }

    @Test
    void identicalClipboardPasteKeepsExistingUndoAndRedoEntries() {
        Fixture fixture = fixture("base");
        fixture.engine.insertInput("first ");
        fixture.engine.moveCursorTo(new Position(0, 0));
        fixture.engine.insertInput("second ");
        assertTrue(fixture.engine.undo());
        assertEquals("first base", fixture.model.getLine(0));
        fixture.engine.restoreSelection(new Position(0, 5), new Position(0, 0));
        fixture.clipboard.setText("first");
        long revision = fixture.model.revision();

        assertTrue(fixture.engine.pasteClipboard());

        assertEquals(revision, fixture.model.revision());
        assertTrue(fixture.state.isDirty());
        assertEquals(new Position(0, 5), fixture.state.getCursor());
        assertNull(fixture.state.getSelectionAnchor());
        assertTrue(fixture.engine.redo());
        assertEquals("second first base", fixture.model.getLine(0));
        assertTrue(fixture.engine.undo());
        assertTrue(fixture.engine.undo());
        assertEquals("base", fixture.model.getLine(0));
        assertFalse(fixture.state.isDirty());
    }

    @Test
    void smallPasteDoesNotReadTheUnchangedDocumentPrefix() {
        class CountingModel extends TextModel {
            int reads;
            CountingModel() { super(new SimpleLineBuffer()); }
            @Override public String getLine(int line) {
                reads++;
                return super.getLine(line);
            }
        }
        CountingModel model = new CountingModel();
        model.replaceLines(Collections.nCopies(10_000, "unchanged"));
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        engine.moveDocumentEnd(false);
        model.reads = 0;

        assertTrue(engine.pasteText(" pasted"));

        assertTrue(model.reads < 50, "A local paste must not scan or snapshot every unchanged line");
        assertEquals("unchanged pasted", model.getLine(9999));
        assertTrue(engine.undo());
        assertEquals("unchanged", model.getLine(9999));
        assertFalse(engine.state().isDirty());
    }

    private static Fixture fixture(String... lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(lines));
        EditorState state = new EditorState();
        state.setCurrentResource(new MemoryDocumentStorage().resource(Path.of("script.py").toString()));
        EditorClipboard clipboard = new EditorClipboard();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model), clipboard);
        return new Fixture(model, state, clipboard, engine);
    }

    private record Fixture(TextModel model, EditorState state, EditorClipboard clipboard, EditorEngine engine) {}
}
