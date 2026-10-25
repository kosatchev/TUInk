package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorEngineClipboardTest {
    @TempDir
    Path tempDir;

    @Test
    void copiesMultilineUnicodeSelectionBetweenTabsAndReplacesTheDestinationSelection() {
        EditorClipboard clipboard = new EditorClipboard();
        TabSession source = tab(clipboard, "prefix 👩‍💻\nПривет suffix");
        TabSession destination = tab(clipboard, "before discard after");
        source.engine().beginSelectionAt(new Position(0, "prefix ".length()));
        source.engine().extendSelectionTo(new Position(1, "Привет".length()));
        destination.engine().beginSelectionAt(new Position(0, "before ".length()));
        destination.engine().extendSelectionTo(new Position(0, "before discard".length()));

        assertTrue(source.engine().copySelection());
        assertTrue(destination.engine().pasteClipboard());

        assertEquals(List.of("before 👩‍💻", "Привет after"), destination.model().snapshotLines());
        assertEquals(new Position(1, "Привет".length()), destination.state().getCursor());
        assertFalse(destination.state().hasSelection());
        assertEquals(List.of("prefix 👩‍💻", "Привет suffix"), source.model().snapshotLines());
        assertTrue(destination.engine().undo());
        assertEquals(List.of("before discard after"), destination.model().snapshotLines());
        assertTrue(destination.engine().redo());
        assertEquals(List.of("before 👩‍💻", "Привет after"), destination.model().snapshotLines());
    }

    @Test
    void undoingACutRestoresTheSourceWithoutDiscardingTheSharedClipboard() {
        EditorClipboard clipboard = new EditorClipboard();
        TabSession source = tab(clipboard, "left selected right");
        source.engine().beginSelectionAt(new Position(0, "left ".length()));
        source.engine().extendSelectionTo(new Position(0, "left selected".length()));

        assertTrue(source.engine().cutSelection());
        assertEquals("left  right", source.model().getLine(0));
        assertTrue(source.engine().undo());
        assertEquals("left selected right", source.model().getLine(0));

        TabSession destination = tab(clipboard, "");
        assertTrue(destination.engine().pasteClipboard());
        assertEquals("selected", destination.model().getLine(0));
        assertTrue(destination.engine().undo());
        assertEquals("", destination.model().getLine(0));
        assertTrue(destination.engine().pasteClipboard());
        assertEquals("selected", destination.model().getLine(0));
    }

    @Test
    void copiesTheCurrentLineWithItsNewlineAcrossTabs() {
        EditorClipboard clipboard = new EditorClipboard();
        TabSession source = tab(clipboard, "first\nsecond");
        TabSession destination = tab(clipboard, "tail");

        assertTrue(source.engine().copyCurrentLine());
        assertTrue(destination.engine().pasteClipboard());

        assertEquals(List.of("first", "tail"), destination.model().snapshotLines());
        assertEquals(List.of("first", "second"), source.model().snapshotLines());
    }

    @Test
    void cutsTheCurrentLineWithItsNewlineAcrossTabs() {
        EditorClipboard clipboard = new EditorClipboard();
        TabSession source = tab(clipboard, "first\nsecond");
        TabSession destination = tab(clipboard, "tail");

        assertTrue(source.engine().cutCurrentLine());
        assertTrue(destination.engine().pasteClipboard());

        assertEquals(List.of("second"), source.model().snapshotLines());
        assertEquals(List.of("first", "tail"), destination.model().snapshotLines());
        assertTrue(source.engine().undo());
        assertEquals(List.of("first", "second"), source.model().snapshotLines());
    }

    @Test
    void copyingAndCuttingTheLastLineDoNotAddANewline() {
        EditorClipboard clipboard = new EditorClipboard();
        TabSession source = tab(clipboard, "first\nlast");
        source.engine().moveCursorTo(new Position(1, 0));
        TabSession destination = tab(clipboard, "tail");

        assertTrue(source.engine().copyCurrentLine());
        assertTrue(destination.engine().pasteClipboard());
        assertEquals(List.of("lasttail"), destination.model().snapshotLines());
        assertTrue(destination.engine().undo());

        assertTrue(source.engine().cutCurrentLine());
        assertTrue(destination.engine().pasteClipboard());
        assertEquals(List.of("lasttail"), destination.model().snapshotLines());
        assertEquals(List.of("first", ""), source.model().snapshotLines());
    }

    @Test
    void copyingOrCuttingAnEmptyFinalLinePreservesTheClipboardSavepointAndRedo() {
        for (boolean cut : List.of(false, true)) {
            for (List<String> lines : List.of(List.of(""), List.of("first", ""))) {
                TextModel model = new TextModel(new SimpleLineBuffer());
                model.replaceLines(lines);
                EditorState state = new EditorState();
                EditorClipboard clipboard = new EditorClipboard();
                clipboard.setText("previously copied text😀");
                EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model), clipboard);
                engine.moveDocumentEnd(false);
                engine.insertInput("x");
                assertTrue(engine.undo());
                long revision = model.revision();
                long navigationRevision = engine.navigationRevision();
                DocumentFileMetadata metadata = model.fileMetadata();
                Position cursor = state.getCursor();

                assertFalse(cut ? engine.cutCurrentLine() : engine.copyCurrentLine());

                assertEquals("previously copied text😀", clipboard.text());
                assertEquals(lines, model.snapshotLines());
                assertEquals(metadata, model.fileMetadata());
                assertEquals(revision, model.revision());
                assertEquals(navigationRevision, engine.navigationRevision());
                assertEquals(cursor, state.getCursor());
                assertFalse(state.isDirty());
                assertFalse(engine.undo());
                assertTrue(engine.redo());
                assertEquals("x", model.getLine(model.lineCount() - 1));
                assertTrue(state.isDirty());
            }
        }
    }

    @Test
    void emptyLinesBeforeEofStillCopyAndCutTheirNewline() {
        for (boolean cut : List.of(false, true)) {
            EditorClipboard clipboard = new EditorClipboard();
            clipboard.setText("previous text");
            TabSession source = tab(clipboard, "first\n\nlast");
            source.engine().moveCursorTo(new Position(1, 0));

            assertTrue(cut ? source.engine().cutCurrentLine() : source.engine().copyCurrentLine());

            assertEquals("\n", clipboard.text());
            assertEquals(cut ? List.of("first", "last") : List.of("first", "", "last"),
                source.model().snapshotLines());
            if (cut) {
                assertTrue(source.engine().undo());
                assertEquals(List.of("first", "", "last"), source.model().snapshotLines());
            }
        }
    }

    @Test
    void retainsCopiedTextAfterTheSourceTabIsClosedAndANewTabIsCreated() {
        EditorClipboard clipboard = new EditorClipboard();
        List<TabSession> tabs = new ArrayList<>();
        tabs.add(tab(clipboard, "copied before closing"));
        assertTrue(tabs.getFirst().engine().copyCurrentLine());
        tabs.clear();

        tabs.add(tab(clipboard, ""));

        assertTrue(tabs.getFirst().engine().pasteClipboard());
        assertEquals("copied before closing", tabs.getFirst().model().getLine(0));
    }

    @Test
    void keepsIndependentWorkspacesAndStandaloneEnginesIsolated() {
        EditorClipboard firstWorkspace = new EditorClipboard();
        TabSession source = tab(firstWorkspace, "private text");
        assertTrue(source.engine().copyCurrentLine());

        TabSession otherWorkspace = tab(new EditorClipboard(), "untouched");
        assertFalse(otherWorkspace.engine().pasteClipboard());
        assertEquals("untouched", otherWorkspace.model().getLine(0));

        EditorEngine standaloneSource = standaloneEngine();
        standaloneSource.insertInput("standalone text");
        assertTrue(standaloneSource.copyCurrentLine());
        EditorEngine standaloneDestination = standaloneEngine();
        assertFalse(standaloneDestination.pasteClipboard());
        assertEquals("", standaloneDestination.model().getLine(0));

        TabSession firstWorkspaceDestination = tab(firstWorkspace, "");
        assertTrue(firstWorkspaceDestination.engine().pasteClipboard());
        assertEquals("private text", firstWorkspaceDestination.model().getLine(0));
    }

    @Test
    void openingAndRestoringDocumentsUseTheWorkspaceClipboardWithoutPersistingIt() throws IOException {
        EditorClipboard clipboard = new EditorClipboard();
        TabSession source = tab(clipboard, "workspace text");
        assertTrue(source.engine().copyCurrentLine());
        RecoverySnapshot snapshot = source.engine().captureRecoverySnapshot();
        Path opened = tempDir.resolve("opened.txt");
        Files.writeString(opened, "disk");
        source.engine().open(JvmDocumentStorage.resource(opened));

        TabSession restored = tab(clipboard, "");
        restored.engine().restoreRecoverySnapshot(snapshot);
        restored.engine().moveCursorTo(new Position(0, "workspace text".length()));
        assertTrue(restored.engine().pasteClipboard());
        assertEquals("workspace textworkspace text", restored.model().getLine(0));

        TabSession destination = tab(clipboard, "");
        assertTrue(destination.engine().pasteClipboard());
        assertEquals("workspace text", destination.model().getLine(0));
        TabSession freshWorkspace = tab(new EditorClipboard(), "");
        freshWorkspace.engine().restoreRecoverySnapshot(snapshot);
        assertFalse(freshWorkspace.engine().pasteClipboard());
        assertEquals("workspace text", freshWorkspace.model().getLine(0));
    }

    @Test
    void copyingOrCuttingWithoutASelectionKeepsTheLastCopiedText() {
        EditorClipboard clipboard = new EditorClipboard();
        TabSession source = tab(clipboard, "copied");
        assertTrue(source.engine().copyCurrentLine());
        TabSession other = tab(clipboard, "other");

        assertFalse(other.engine().copySelection());
        assertFalse(other.engine().cutSelection());

        TabSession destination = tab(clipboard, "");
        assertTrue(destination.engine().pasteClipboard());
        assertEquals("copied", destination.model().getLine(0));
    }

    @Test
    void multilinePasteRestoresMixedSeparatorsAndBothSelectionDirectionsAsOneUndoStep() throws IOException {
        String original = "one\r\ntwo\nthree\rfour";
        Path path = tempDir.resolve("mixed.txt");
        for (boolean reverse : List.of(false, true)) {
            Files.writeString(path, original);
            EditorClipboard clipboard = new EditorClipboard();
            clipboard.setText("😀\rnew\r\nx");
            TabSession destination = tab(clipboard, "");
            EditorEngine engine = destination.engine();
            engine.open(JvmDocumentStorage.resource(path));
            Position start = new Position(0, 1);
            Position end = new Position(2, 2);
            engine.restoreSelection(reverse ? start : end, reverse ? end : start);
            DocumentFileMetadata beforeMetadata = destination.model().fileMetadata();

            assertTrue(engine.pasteClipboard());

            assertEquals(List.of("o😀", "new", "xree", "four"), destination.model().snapshotLines());
            assertEquals(List.of("\r\n", "\r\n", "\r"), destination.model().fileMetadata().lineSeparators());
            assertEquals(new Position(2, 1), destination.state().getCursor());
            assertNull(destination.state().getSelectionAnchor());
            assertTrue(destination.state().isDirty());
            DocumentFileMetadata afterMetadata = destination.model().fileMetadata();

            assertTrue(engine.undo());
            assertEquals(List.of("one", "two", "three", "four"), destination.model().snapshotLines());
            assertEquals(beforeMetadata, destination.model().fileMetadata());
            assertEquals(reverse ? start : end, destination.state().getCursor());
            assertEquals(reverse ? end : start, destination.state().getSelectionAnchor());
            assertFalse(destination.state().isDirty());
            assertFalse(engine.undo());

            assertTrue(engine.redo());
            assertEquals(List.of("o😀", "new", "xree", "four"), destination.model().snapshotLines());
            assertEquals(afterMetadata, destination.model().fileMetadata());
            assertEquals(new Position(2, 1), destination.state().getCursor());
            assertNull(destination.state().getSelectionAnchor());
            assertTrue(destination.state().isDirty());
            assertFalse(engine.redo());
        }
    }

    @Test
    void malformedClipboardTextCannotDeleteTheSelectionBeforeRejection() {
        EditorClipboard clipboard = new EditorClipboard();
        TabSession destination = tab(clipboard, "keep selected text");
        Position start = new Position(0, 5);
        Position end = new Position(0, 13);
        destination.engine().restoreSelection(end, start);
        long revision = destination.model().revision();
        clipboard.setText("bad\uD800");

        assertThrows(IllegalArgumentException.class, destination.engine()::pasteClipboard);

        assertEquals("keep selected text", destination.model().getLine(0));
        assertEquals(revision, destination.model().revision());
        assertEquals(end, destination.state().getCursor());
        assertEquals(start, destination.state().getSelectionAnchor());
    }

    @Test
    void repeatedSmallPastesKeepDeltaHistoryInsteadOfWholeLineSnapshots() {
        class SnapshotCountingModel extends TextModel {
            private int snapshots;

            SnapshotCountingModel() {
                super(new SimpleLineBuffer());
            }

            @Override
            public List<String> snapshotLines() {
                snapshots++;
                return super.snapshotLines();
            }
        }
        SnapshotCountingModel model = new SnapshotCountingModel();
        String original = "a".repeat(100_000);
        model.insertText(new Position(0, 0), original);
        EditorClipboard clipboard = new EditorClipboard();
        clipboard.setText("x");
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model), clipboard);
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        model.snapshots = 0;

        for (int index = 0; index < 100; index++) {
            assertTrue(engine.pasteClipboard());
        }
        assertEquals("x".repeat(100) + original, model.getLine(0));
        for (int index = 0; index < 100; index++) {
            assertTrue(engine.undo());
        }
        assertEquals(original, model.getLine(0));
        assertEquals(new Position(0, 0), state.getCursor());
        assertFalse(engine.undo());
        for (int index = 0; index < 100; index++) {
            assertTrue(engine.redo());
        }
        assertEquals("x".repeat(100) + original, model.getLine(0));
        assertEquals(new Position(0, 100), state.getCursor());
        assertFalse(engine.redo());
        assertEquals(0, model.snapshots, "Small pastes must not retain complete line snapshots");
    }

    private static TabSession tab(EditorClipboard clipboard, String text) {
        TabSession tab = TabSession.createEmpty(TestEditorTextLayout::new, clipboard);
        tab.engine().insertInput(text);
        tab.engine().moveCursorTo(new Position(0, 0));
        return tab;
    }

    private static EditorEngine standaloneEngine() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        return new EditorEngine(model, new EditorState(), new TestEditorTextLayout(model));
    }
}
