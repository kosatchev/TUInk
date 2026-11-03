package dev.tuink.tui;

import dev.tuink.editor.storage.DocumentEncodingException;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import dev.tuink.editor.storage.DocumentLoad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.editor.storage.AmbiguousDocumentEncodingException;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.Position;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.core.TabSession;
import dev.tuink.core.TextModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePickerOpenWorkflowTest {
    @TempDir Path directory;

    @Test
    void invalidLocationDoesNotStartIoOrAllocateADocument() {
        try (OpenFileTreeController tree = new OpenFileTreeController(directory, false, false)) {
            for (String value : List.of("", "invalid\u0000path")) {
                var result = applyWithoutDocument(new OpenFileInputRouter.OpenLocation(value), tree);
                assertTrue(result.openFileMode());
                assertEquals(FilePickerOpenWorkflow.NoticeKind.WARNING, result.noticeKind());
                assertNull(result.pendingOpen());
                assertNull(result.openedDocument());
                assertEquals(directory, tree.root());
            }
        }
    }

    @Test
    void relativeLocationRetainsTheSelectedEncodingAndDirectoryCompletionIsOnlyANavigationIntent() throws Exception {
        Path target = Files.createDirectory(directory.resolve("folder with spaces"));
        try (OpenFileTreeController tree = new OpenFileTreeController(directory, false, false)) {
            var result = FilePickerOpenWorkflow.apply(new OpenFileInputRouter.OpenLocation("folder with spaces"), tree,
                List.of(), -1, () -> fail("location submission must not create a document"), DocumentOpenMode.WINDOWS_1251);
            var request = result.pendingOpen();
            assertEquals(target, request.path());
            assertEquals(DocumentOpenMode.WINDOWS_1251, request.mode());
            var inspected = request.inspect();
            assertEquals(target, inspected.directory());
            assertNull(inspected.document());
            var completed = FilePickerOpenWorkflow.completeInspected(request, inspected, null, List.of(), -1,
                () -> fail("directory completion must not create a document"));
            assertTrue(completed.openFileMode());
            assertEquals(target, completed.navigateDirectory());
            assertEquals(directory, tree.root(), "the owner applies the navigation intent");
        }
    }

    @Test
    void cancelledLocationCannotPublishDirectoryNavigation() throws Exception {
        try (OpenFileTreeController tree = new OpenFileTreeController(directory, false, false)) {
            var result = applyWithoutDocument(new OpenFileInputRouter.OpenLocation(directory.toString()), tree);
            var target = result.pendingOpen().inspect();
            result.pendingOpen().cancel();
            var completed = FilePickerOpenWorkflow.completeInspected(result.pendingOpen(), target, null, List.of(), -1,
                () -> fail("cancelled location must not create a document"));
            assertFalse(completed.openFileMode());
            assertNull(completed.navigateDirectory());
        }
    }

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
            var requested = FilePickerOpenWorkflow.apply(new OpenFileInputRouter.OpenFile(absent), tree,
                List.of(), -1,
                () -> fail("requesting a read must not allocate a document"));
            assertNotNull(requested.pendingOpen());
            assertNull(requested.openedDocument());
            var missing = complete(requested, List.of(), -1,
                () -> fail("a failed read must not allocate a document"));
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
            var requested = FilePickerOpenWorkflow.apply(new OpenFileInputRouter.OpenFile(file), tree,
                List.of(), -1,
                () -> fail("requesting a read must not allocate a document"));
            assertNotNull(requested.pendingOpen());
            assertEquals(DocumentOpenMode.DETECT, requested.pendingOpen().mode());
            var opened = complete(requested, List.of(), -1, () -> TuiDocumentSession.create(clipboard));
            assertFalse(opened.openFileMode());
            assertNotNull(opened.openedDocument());
            var tab = opened.openedDocument().view();
            assertEquals(List.of("selected", "contents"), tab.model().snapshotLines());
            assertEquals(SaveDisposition.REPLACE_EXISTING, tab.state().getSaveDisposition());
            assertFalse(tab.state().isDirty());
            assertEquals(file, JvmDocumentStorage.pathOf(tab.state().getCurrentResource()));
            tab.engine().pasteClipboard();
            assertEquals("clipboardselected", tab.model().getLine(0));
        }
    }

    @Test
    void openingAnExistingDirtyDocumentReturnsItsIndexWithoutReloadingOrAllocating() throws Exception {
        Path file = Files.writeString(directory.resolve("selected.txt"), "saved");
        var existing = TuiDocumentSession.create(new EditorClipboard()).view();
        existing.engine().open(JvmDocumentStorage.resource(file));
        existing.engine().insertInput("draft ");
        existing.engine().moveCursorTo(new Position(0, 2));
        long revision = existing.model().revision();
        try (OpenFileTreeController tree = new OpenFileTreeController(directory, true, false)) {
            var result = FilePickerOpenWorkflow.apply(new OpenFileInputRouter.OpenFile(file), tree,
                List.of(existing), 0, () -> fail("an already open file must not allocate another document"));

            assertFalse(result.openFileMode());
            assertNull(result.openedDocument());
            assertEquals(0, result.activateTabIndex());
            assertNull(result.pendingOpen());
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
    void preparedReadIsPublishedWithoutReopeningTheFile() throws Exception {
        Path file = Files.writeString(directory.resolve("read-once.txt"), "decoded contents");
        var request = new FilePickerOpenWorkflow.OpenRequest(file, DocumentOpenMode.DETECT);
        DocumentLoad loaded = request.read();
        Files.delete(file);

        var result = FilePickerOpenWorkflow.complete(request, loaded, null, List.of(), -1,
            () -> TuiDocumentSession.create(new EditorClipboard()));

        assertNotNull(result.openedDocument());
        assertEquals(List.of("decoded contents"), result.openedDocument().view().model().snapshotLines());
        assertEquals(file, JvmDocumentStorage.pathOf(result.openedDocument().view().state().getCurrentResource()));
        assertFalse(Files.exists(file));
    }

    @Test
    void completionRechecksAliasesAndPreservesADocumentOpenedDuringTheRead() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path file = Files.writeString(directory.resolve("original.txt"), "disk");
        Path symlink = Files.createSymbolicLink(directory.resolve("symbolic.txt"), file.getFileName());
        Path hardlink = Files.createLink(directory.resolve("hard.txt"), file);
        for (Path alias : List.of(file, symlink, hardlink)) {
            var request = new FilePickerOpenWorkflow.OpenRequest(file, DocumentOpenMode.DETECT);
            DocumentLoad loaded = request.read();
            var existing = TuiDocumentSession.create(new EditorClipboard()).view();
            existing.engine().open(JvmDocumentStorage.resource(alias));
            existing.engine().insertInput("draft ");
            existing.engine().moveCursorTo(new Position(0, 2));
            long revision = existing.model().revision();

            var result = FilePickerOpenWorkflow.complete(request, loaded, null, List.of(existing), 0,
                () -> fail("completion must reuse the existing physical file"));

            assertEquals(0, result.activateTabIndex());
            assertNull(result.openedDocument());
            assertEquals(revision, existing.model().revision());
            assertEquals(List.of("draft disk"), existing.model().snapshotLines());
            assertEquals(new Position(0, 2), existing.state().getCursor());
            assertTrue(existing.state().isDirty());
        }
    }

    @Test
    void retargetedSelectionCannotSubstituteAnotherDocumentForThePreparedBytes() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path first = Files.writeString(directory.resolve("first.txt"), "first");
        Path second = Files.writeString(directory.resolve("second.txt"), "second");
        Path selected = Files.createSymbolicLink(directory.resolve("selected.txt"), first.getFileName());
        var request = new FilePickerOpenWorkflow.OpenRequest(selected, DocumentOpenMode.DETECT);
        DocumentLoad loaded = request.read();
        Files.delete(selected);
        Files.createSymbolicLink(selected, second.getFileName());
        var other = TuiDocumentSession.create(new EditorClipboard()).view();
        other.engine().open(JvmDocumentStorage.resource(second));

        var result = FilePickerOpenWorkflow.complete(request, loaded, null, List.of(other), 0,
            () -> TuiDocumentSession.create(new EditorClipboard()));

        assertEquals(-1, result.activateTabIndex());
        assertNotNull(result.openedDocument());
        assertEquals(List.of("first"), result.openedDocument().view().model().snapshotLines());
        assertEquals(first, JvmDocumentStorage.baselineOf(result.openedDocument().view().model().fileBaseline()).target());
        assertEquals(List.of("second"), other.model().snapshotLines());
    }

    @Test
    void ambiguityAndExplicitUtf16ChoiceRemainSeparateReadRequests() throws Exception {
        Path file = Files.write(directory.resolve("ambiguous.txt"),
            new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 0, 65, 0, 0, 0});
        var request = new FilePickerOpenWorkflow.OpenRequest(file, DocumentOpenMode.DETECT);
        var result = FilePickerOpenWorkflow.complete(request, null, new AmbiguousDocumentEncodingException(),
            List.of(), -1, () -> fail("an ambiguous read must not allocate a document"));
        assertEquals(file, result.pendingExplicitOpenPath());
        assertNull(result.pendingOpen());
        assertFalse(result.openFileMode());

        var explicit = FilePickerOpenWorkflow.openAsUtf16(file, List.of(), -1,
            () -> fail("the explicit choice must still decode on the worker"));
        assertNotNull(explicit.pendingOpen());
        assertEquals(DocumentOpenMode.UTF_16LE, explicit.pendingOpen().mode());
        var opened = complete(explicit, List.of(), -1,
            () -> TuiDocumentSession.create(new EditorClipboard()));
        assertEquals(List.of("\0A\0"), opened.openedDocument().view().model().snapshotLines());
        assertEquals(StandardCharsets.UTF_16LE, opened.openedDocument().view().state().getFileEncoding());
    }

    @Test
    void completionPreservesPathSpecificAndGenericFailureMessages() {
        Path file = directory.resolve("selected.txt");
        List<IOException> failures = List.of(new NoSuchFileException(file.toString()),
            new AccessDeniedException(file.toString()), new FileSystemException(file.toString(), null, "device unavailable"),
            new IOException("read failed"), new IOException());
        List<String> reasons = List.of("file no longer exists", "permission denied", "device unavailable", "read failed", "I/O error");
        for (int index = 0; index < failures.size(); index++) {
            var request = new FilePickerOpenWorkflow.OpenRequest(file, DocumentOpenMode.DETECT);
            var result = FilePickerOpenWorkflow.complete(request, null, failures.get(index), List.of(), -1,
                () -> fail("failed reads must not allocate documents"));
            assertTrue(result.openFileMode());
            assertEquals(FilePickerOpenWorkflow.NoticeKind.ERROR, result.noticeKind());
            assertEquals(reasons.get(index), result.notice().summary());
            assertEquals("Open failed: " + reasons.get(index) + (index < 3 ? ": " + file : ""), result.message());
            assertEquals(index < 3 ? file : null, result.notice().path());
        }
    }

    @Test
    void filenameWordsCannotTurnAnUnspecifiedFilesystemFailureIntoAnotherReason() {
        for (String name : List.of("permission denied.txt", "no space.txt", "is a directory.txt")) {
            Path file = directory.resolve(name);
            var exception = new FileSystemException(file.toString());
            var request = new FilePickerOpenWorkflow.OpenRequest(file, DocumentOpenMode.DETECT);
            var result = FilePickerOpenWorkflow.complete(request, null, exception, List.of(), -1,
                () -> fail("a failed read must not allocate a document"));

            assertTrue(result.openFileMode());
            assertEquals(FilePickerOpenWorkflow.NoticeKind.ERROR, result.noticeKind());
            assertEquals("Open failed: " + exception.getMessage(), result.message());
            assertEquals(exception.getMessage(), result.notice().summary());
            assertNull(result.notice().path());
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

    private static FilePickerOpenWorkflow.Result complete(FilePickerOpenWorkflow.Result requested,
                                                          List<TabSession> tabs, int active,
                                                          Supplier<TuiDocumentSession> newDocument) {
        var request = requested.pendingOpen();
        DocumentLoad loaded;
        try {
            loaded = request.read();
        } catch (IOException failure) {
            return FilePickerOpenWorkflow.complete(request, null, failure, tabs, active, newDocument);
        }
        return FilePickerOpenWorkflow.complete(request, loaded, null, tabs, active, newDocument);
    }
}
