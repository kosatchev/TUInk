package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            fixture.engine.open(path);
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

    private static Fixture fixture(String... lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(lines));
        EditorState state = new EditorState();
        state.setCurrentFile(Path.of("script.py"));
        EditorClipboard clipboard = new EditorClipboard();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model), clipboard);
        return new Fixture(model, state, clipboard, engine);
    }

    private record Fixture(TextModel model, EditorState state, EditorClipboard clipboard, EditorEngine engine) {}
}
