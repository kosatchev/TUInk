package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.TabSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceDocumentsTest {
    @TempDir Path directory;

    @Test
    void physicalAliasesFindTheLoadedDocumentWithoutComparingTheirContents() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path file = Files.writeString(directory.resolve("original.txt"), "same contents");
        Path symlink = Files.createSymbolicLink(directory.resolve("symbolic.txt"), file.getFileName());
        Path hardlink = Files.createLink(directory.resolve("hard.txt"), file);
        Path different = Files.writeString(directory.resolve("different.txt"), "same contents");
        TabSession tab = open(file);

        assertEquals(0, WorkspaceDocuments.findOpenFile(List.of(tab), 0, symlink));
        assertEquals(0, WorkspaceDocuments.findOpenFile(List.of(tab), 0, hardlink));
        assertEquals(-1, WorkspaceDocuments.findOpenFile(List.of(tab), 0, different));
    }

    @Test
    void retargetingAnOpenedSymlinkDoesNotReassignItsLoadedDocument() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path original = Files.writeString(directory.resolve("original.txt"), "original");
        Path replacement = Files.writeString(directory.resolve("replacement.txt"), "replacement");
        Path link = Files.createSymbolicLink(directory.resolve("alias.txt"), original.getFileName());
        TabSession tab = open(link);
        Files.delete(link);
        Files.createSymbolicLink(link, replacement.getFileName());

        assertEquals(-1, WorkspaceDocuments.findOpenFile(List.of(tab), 0, link));
        assertEquals(-1, WorkspaceDocuments.findOpenFile(List.of(tab), 0, replacement));
        assertEquals(0, WorkspaceDocuments.findOpenFile(List.of(tab), 0, original));
        assertEquals(List.of("original"), tab.model().snapshotLines());
    }

    @Test
    void parentSymlinksAreResolvedBeforeDotSegments() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path nested = Files.createDirectories(directory.resolve("nested/deeper"));
        Path original = Files.writeString(nested.getParent().resolve("document.txt"), "original");
        Path link = Files.createSymbolicLink(directory.resolve("linked-directory"), directory.relativize(nested));
        TabSession tab = open(original);

        assertEquals(0, WorkspaceDocuments.findOpenFile(List.of(tab), 0, link.resolve("../document.txt")));
    }

    @Test
    void aNewNamedDocumentIsReusedEvenBeforeItsFileExists() throws Exception {
        Path absent = directory.resolve("not-created.txt");
        TabSession tab = open(absent);
        tab.engine().insertInput("unsaved");

        assertEquals(0, WorkspaceDocuments.findOpenFile(List.of(tab), 0, absent));
        assertFalse(Files.exists(absent));
        assertEquals(List.of("unsaved"), tab.model().snapshotLines());
    }

    @Test
    void explicitRecoveryCopiesRemainSeparateAndTheActiveCopyWinsLookup() throws Exception {
        Path file = Files.writeString(directory.resolve("document.txt"), "disk");
        TabSession first = open(file);
        first.engine().insertInput("first draft ");
        TabSession second = open(file);
        second.engine().insertInput("second draft ");

        assertEquals(1, WorkspaceDocuments.findOpenFile(List.of(first, second), 1, file));
        assertEquals(0, WorkspaceDocuments.findOpenFile(List.of(first, second), 0, file));
        assertEquals(0, WorkspaceDocuments.findOtherOpenFile(List.of(first, second), 1, file));
        assertEquals(1, WorkspaceDocuments.findOtherOpenFile(List.of(first, second), 0, file));
        assertEquals(-1, WorkspaceDocuments.findOtherOpenFile(List.of(first), 0, file));
        assertEquals(List.of("first draft disk"), first.model().snapshotLines());
        assertEquals(List.of("second draft disk"), second.model().snapshotLines());
    }

    @Test
    void onlyAnUnmodifiedUnnamedNewDocumentIsAReplaceablePlaceholder() throws Exception {
        TabSession pristine = TuiDocumentSession.create(new EditorClipboard()).view();
        assertTrue(WorkspaceDocuments.isPristinePlaceholder(pristine));
        pristine.engine().insertInput("draft");
        pristine.engine().undo();
        assertFalse(pristine.state().isDirty());
        assertFalse(WorkspaceDocuments.isPristinePlaceholder(pristine), "the undo and redo history is still useful");
        TabSession named = open(directory.resolve("new-file.txt"));
        assertFalse(WorkspaceDocuments.isPristinePlaceholder(named));
    }

    private static TabSession open(Path path) throws Exception {
        TabSession tab = TuiDocumentSession.create(new EditorClipboard()).view();
        tab.engine().open(path);
        return tab;
    }
}
