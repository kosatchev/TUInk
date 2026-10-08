package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        source.engine().open(opened);

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
