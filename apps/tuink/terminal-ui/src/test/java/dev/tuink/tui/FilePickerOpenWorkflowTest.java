package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.Position;
import dev.tuink.core.SaveDisposition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePickerOpenWorkflowTest {
    @TempDir Path directory;

    @Test
    void treeAndCancelIntentsDoNotCreateDocuments() throws Exception {
        Path child = Files.createDirectory(directory.resolve("child"));
        try (OpenFileTreeController tree = new OpenFileTreeController(directory, true, false)) {
            var changed = applyWithoutDocument(new OpenFileInputRouter.ChangeRoot(child), tree);
            assertTrue(changed.openFileMode());
            assertEquals(child, tree.root());
            applyWithoutDocument(new OpenFileInputRouter.MoveToParentRoot(), tree);
            assertEquals(directory, tree.root());
            boolean hidden = tree.showHiddenFiles();
            applyWithoutDocument(new OpenFileInputRouter.ToggleHidden(), tree);
            assertEquals(!hidden, tree.showHiddenFiles());
            var closed = applyWithoutDocument(new OpenFileInputRouter.CloseDialog(), tree);
            assertFalse(closed.openFileMode());
            assertNull(closed.openedDocument());
            assertEquals(FilePickerOpenWorkflow.NoticeKind.UNCHANGED, closed.noticeKind());
        }
    }

    @Test
    void invalidSelectionsRetainThePickerWithoutPublishingAnEmptyDocument() {
        try (OpenFileTreeController tree = new OpenFileTreeController(directory, true, false)) {
            var rejected = applyWithoutDocument(
                new OpenFileInputRouter.SelectionRejected(OpenFileInputRouter.Rejection.ERROR), tree);
            assertTrue(rejected.openFileMode());
            assertNull(rejected.openedDocument());
            assertEquals(FilePickerOpenWorkflow.NoticeKind.WARNING, rejected.noticeKind());
            Path absent = directory.resolve("missing.txt");
            var missing = FilePickerOpenWorkflow.apply(new OpenFileInputRouter.OpenFile(absent), tree,
                List.of(), -1,
                () -> TuiDocumentSession.create(new EditorClipboard()));
            assertTrue(missing.openFileMode());
            assertNull(missing.openedDocument());
            assertEquals(FilePickerOpenWorkflow.NoticeKind.ERROR, missing.noticeKind());
            assertEquals("Open failed: file no longer exists: " + absent, missing.message());
            assertFalse(Files.exists(absent));
        }
    }

    @Test
    void successfulOpenReturnsTheCoreDocumentAndTheOriginalClipboard() throws Exception {
        Path file = directory.resolve("selected.java");
        Files.writeString(file, "selected\r\ncontents");
        EditorClipboard clipboard = new EditorClipboard();
        clipboard.setText("clipboard");
        try (OpenFileTreeController tree = new OpenFileTreeController(directory, true, false)) {
            var opened = FilePickerOpenWorkflow.apply(new OpenFileInputRouter.OpenFile(file), tree,
                List.of(), -1,
                () -> TuiDocumentSession.create(clipboard));
            assertFalse(opened.openFileMode());
            assertNotNull(opened.openedDocument());
            var tab = opened.openedDocument().view();
            assertEquals(List.of("selected", "contents"), tab.model().snapshotLines());
            assertEquals(SaveDisposition.REPLACE_EXISTING, tab.state().getSaveDisposition());
            assertFalse(tab.state().isDirty());
            assertEquals(file, tab.state().getCurrentFile());
            tab.engine().pasteClipboard();
            assertEquals("clipboardselected", tab.model().getLine(0));
        }
    }

    @Test
    void openingAnExistingDirtyDocumentReturnsItsIndexWithoutReloadingOrAllocating() throws Exception {
        Path file = Files.writeString(directory.resolve("selected.txt"), "saved");
        var existing = TuiDocumentSession.create(new EditorClipboard()).view();
        existing.engine().open(file);
        existing.engine().insertInput("draft ");
        existing.engine().moveCursorTo(new Position(0, 2));
        long revision = existing.model().revision();
        try (OpenFileTreeController tree = new OpenFileTreeController(directory, true, false)) {
            var result = FilePickerOpenWorkflow.apply(new OpenFileInputRouter.OpenFile(file), tree,
                List.of(existing), 0, () -> fail("an already open file must not allocate another document"));

            assertFalse(result.openFileMode());
            assertNull(result.openedDocument());
            assertEquals(0, result.activateTabIndex());
            assertEquals(List.of("draft saved"), existing.model().snapshotLines());
            assertEquals(revision, existing.model().revision());
            assertEquals(new Position(0, 2), existing.state().getCursor());
            assertTrue(existing.state().isDirty());
            existing.engine().undo();
            assertEquals(List.of("saved"), existing.model().snapshotLines());
            assertFalse(existing.state().isDirty());
        }
    }

    @Test
    void initialRootUsesTheActiveFileParentOrWorkingDirectory() {
        assertEquals(directory, FilePickerOpenWorkflow.initialRoot(directory.resolve("document.txt")));
        assertEquals(Path.of(".").toAbsolutePath().normalize(), FilePickerOpenWorkflow.initialRoot(null));
        assertEquals(Path.of(".").toAbsolutePath().normalize(), FilePickerOpenWorkflow.initialRoot(Path.of("relative.txt")));
    }

    private static FilePickerOpenWorkflow.Result applyWithoutDocument(OpenFileInputRouter.Intent intent,
                                                                      OpenFileTreeController tree) {
        return FilePickerOpenWorkflow.apply(intent, tree, List.of(), -1,
            () -> fail("navigation must not allocate a document"));
    }
}
