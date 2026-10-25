package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.unicode.GraphemeText;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class EditorEngineEditedCaretTest {
    @Test
    void typingOverAPartialSearchMatchKeepsItsRetainedAccentBeforeTheFollowingInput() {
        EditorEngine engine = engine("e\u0301x");
        assertTrue(engine.findNext("e"));
        assertSelection(engine, 1, 0);

        engine.insertChar('A');

        assertEquals("A\u0301x", engine.model().getLine(0));
        assertCaret(engine, 0, 2);
        engine.insertChar('B');
        assertEquals("A\u0301Bx", engine.model().getLine(0));
        assertCaret(engine, 0, 3);
        assertTrue(engine.undo());
        assertCaret(engine, 0, 2);
        assertTrue(engine.undo());
        assertEquals("e\u0301x", engine.model().getLine(0));
        assertSelection(engine, 1, 0); // Undo preserves the exact UTF-16 search range.
        assertTrue(engine.redo());
        assertCaret(engine, 0, 2);
        assertTrue(engine.redo());
        assertEquals("A\u0301Bx", engine.model().getLine(0));
        assertCaret(engine, 0, 3);
    }

    @Test
    void typedAndPastedJoinersMoveTheCaretPastTheNewEmojiCluster() {
        for (Consumer<EditorEngine> insert : List.<Consumer<EditorEngine>>of(
            engine -> engine.insertInput("\u200D"), engine -> engine.pasteText("\u200D")
        )) {
            EditorEngine engine = engine("👩💻x");
            engine.moveRight();

            insert.accept(engine);

            assertEquals("👩‍💻x", engine.model().getLine(0));
            assertCaret(engine, 0, 5);
            engine.insertInput("𐐀");
            assertEquals("👩‍💻𐐀x", engine.model().getLine(0));
            assertCaret(engine, 0, 7);
            assertTrue(engine.undo());
            // Consecutive typing may share an undo step; paste is a separate one.
            if (!engine.model().getLine(0).equals("👩💻x")) assertTrue(engine.undo());
            assertEquals("👩💻x", engine.model().getLine(0));
            assertCaret(engine, 0, 2);
        }
    }

    @Test
    void deletingALineBreakMovesPastTheJoinedClusterAndPreservesUndoMetadata() {
        for (Consumer<EditorEngine> join : List.<Consumer<EditorEngine>>of(
            engine -> { engine.moveEnd(); engine.deleteForward(); },
            engine -> { engine.moveCursorTo(new Position(1, 0)); engine.backspace(); },
            engine -> { engine.restoreSelection(new Position(1, 0), new Position(0, 1)); engine.cutSelection(); }
        )) {
            EditorEngine engine = engine("e", "\u0301x");
            DocumentFileMetadata metadata = new DocumentFileMetadata(StandardCharsets.UTF_16LE,
                BomKind.UTF_16_LE, "\r", List.of("\r"));
            engine.model().restoreFileMetadata(metadata);

            join.accept(engine);

            assertEquals(List.of("e\u0301x"), engine.model().snapshotLines());
            assertCaret(engine, 0, 2);
            engine.pasteText("B");
            assertEquals("e\u0301Bx", engine.model().getLine(0));
            assertTrue(engine.undo());
            assertCaret(engine, 0, 2);
            assertTrue(engine.undo());
            assertEquals(List.of("e", "\u0301x"), engine.model().snapshotLines());
            assertEquals(metadata, engine.model().fileMetadata());
            assertTrue(engine.redo());
            assertCaret(engine, 0, 2);
        }
    }

    @Test
    void singleBulkAndWorkspaceReplacementsKeepTheirExactInputRanges() {
        for (Consumer<EditorEngine> replace : List.<Consumer<EditorEngine>>of(
            engine -> engine.replaceCurrentOrNext("e", "A"),
            engine -> engine.replaceAll("e", "A"),
            engine -> engine.applyTextReplacements(List.of(
                new TextReplacement(new Position(0, 0), new Position(0, 1), "A")))
        )) {
            EditorEngine engine = engine("e\u0301x");

            replace.accept(engine);

            assertEquals("A\u0301x", engine.model().getLine(0));
            assertCaret(engine, 0, 2);
            engine.insertChar('B');
            assertEquals("A\u0301Bx", engine.model().getLine(0));
            assertTrue(engine.undo());
            assertTrue(engine.undo());
            assertEquals("e\u0301x", engine.model().getLine(0));
            assertTrue(engine.redo());
            assertCaret(engine, 0, 2);
        }
    }

    @Test
    void aSelectionIsKeptWhenItsEndpointsRemainBoundariesAndClearedWhenWrappingJoinsOne() {
        EditorEngine ordinary = engine("hello");
        ordinary.restoreSelection(new Position(0, 4), new Position(0, 1));
        ordinary.insertChar('[');
        assertEquals("h[ell]o", ordinary.model().getLine(0));
        assertSelection(ordinary, 5, 2);

        EditorEngine prepend = engine("\u0600aX");
        assertTrue(prepend.findNext("\u0600"));
        prepend.insertChar('[');
        assertEquals("[\u0600]aX", prepend.model().getLine(0));
        assertCaret(prepend, 0, 3);
        assertTrue(prepend.undo());
        assertSelection(prepend, 1, 0);
        assertTrue(prepend.redo());
        assertCaret(prepend, 0, 3);

        EditorEngine replace = engine("hello");
        assertTrue(replace.replaceCurrentOrNext("ell", "ELL"));
        assertSelection(replace, 4, 1);
    }

    @Test
    void commentBatchesNormalizeOnlyTheEditingViewAndUndoFromAnotherViewNormalizesItsOwnCaret() {
        EditorEngine engine = engine("e\u0301x");
        engine.state().setCurrentResource(new MemoryDocumentStorage().resource(Path.of("sample.py").toString()));
        assertTrue(engine.findNext("e"));
        engine.toggleLineComment();
        assertEquals("# e\u0301x", engine.model().getLine(0));
        assertCaret(engine, 0, 4);
        assertTrue(engine.undo());
        assertSelection(engine, 1, 0);

        EditorDocument document = new EditorDocument(model("e\u0301x"));
        TabSession first = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        TabSession other = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        other.engine().restoreSelection(new Position(0, 1), new Position(0, 0));
        first.engine().applyTextReplacements(List.of(
            new TextReplacement(new Position(0, 0), new Position(0, 1), "A")));
        assertCaret(first.engine(), 0, 2);
        assertSelection(other.engine(), 1, 0); // Observer search ranges are not widened or discarded.

        first.engine().applyTextReplacements(List.of(
            new TextReplacement(new Position(0, 1), new Position(0, 2), "")));
        other.engine().moveCursorTo(new Position(0, 1));
        assertTrue(other.engine().undo());
        assertEquals("A\u0301x", document.model().getLine(0));
        assertCaret(other.engine(), 0, 2);
    }

    @Test
    void copyAndNoOpReplacementsLeaveAPartialSearchSelectionAtItsExactOffsets() {
        EditorEngine engine = engine("e\u0301x");
        assertTrue(engine.findNext("e"));
        long revision = engine.model().revision();
        assertTrue(engine.copySelection());
        assertFalse(engine.applyTextReplacements(List.of(
            new TextReplacement(new Position(0, 0), new Position(0, 1), "e"))));
        assertEquals(1, engine.replaceAll("e", "e"));
        assertSelection(engine, 1, 0);
        assertEquals(revision, engine.model().revision());
        assertFalse(engine.undo());
    }

    private static EditorEngine engine(String... lines) {
        TextModel model = model(lines);
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        return engine;
    }

    private static TextModel model(String... lines) {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, lines[0]);
        for (int line = 1; line < lines.length; line++) buffer.insertLine(line, lines[line]);
        return new TextModel(buffer);
    }

    private static void assertCaret(EditorEngine engine, int line, int column) {
        assertEquals(new Position(line, column), engine.state().getCursor());
        assertNull(engine.state().getSelectionAnchor());
        assertTrue(GraphemeText.isBoundary(engine.model().getLine(line), column));
    }

    private static void assertSelection(EditorEngine engine, int cursor, int anchor) {
        assertEquals(new Position(0, cursor), engine.state().getCursor());
        assertEquals(new Position(0, anchor), engine.state().getSelectionAnchor());
    }
}
