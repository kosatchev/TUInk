package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextModelSaveTargetIdentityTest {
    @TempDir Path directory;

    @BeforeEach
    void requireSymlinks() throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRedirectedSaveAsAliasPreservesBothFilesAndTheUnsavedDocumentEvenForHardlinkedTargets(boolean hardlink)
        throws Exception {
        Path source = Files.writeString(directory.resolve("source.txt"), "original\r\nsecond\nthird");
        Path first = Files.writeString(directory.resolve("first.txt"), "before first");
        Path second = hardlink ? Files.createLink(directory.resolve("second.txt"), first)
            : Files.writeString(directory.resolve("second.txt"), "before second");
        Path alias = Files.createSymbolicLink(directory.resolve("alias.txt"), first.getFileName());
        var model = new RedirectingModel();
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.openExisting(source);
        engine.insertInput("draft ");
        engine.moveCursorTo(new Position(1, 2));
        state.setSelectionAnchor(new Position(0, 1));
        state.setViewport(new ViewportState(1, 0, 2, 2));
        List<String> edited = model.snapshotLines();
        DocumentFileMetadata metadata = model.fileMetadata();
        FileBaseline baseline = model.fileBaseline();
        long revision = model.revision();
        Position cursor = state.getCursor();
        Position selection = state.getSelectionAnchor();
        ViewportState viewport = state.viewport();
        model.afterNextLineRead(() -> redirect(alias, second.getFileName()));

        IOException failure = assertThrows(IOException.class,
            () -> engine.saveAs(alias, SaveDisposition.REPLACE_EXISTING));

        assertTrue(failure.getMessage().contains("Save target changed"));
        assertEquals(second.getFileName(), Files.readSymbolicLink(alias));
        assertEquals("before first", Files.readString(first));
        assertEquals(hardlink ? "before first" : "before second", Files.readString(second));
        assertEquals("original\r\nsecond\nthird", Files.readString(source));
        assertEquals(edited, model.snapshotLines());
        assertEquals(revision, model.revision());
        assertSame(metadata, model.fileMetadata());
        assertSame(baseline, model.fileBaseline());
        assertEquals(source, state.getCurrentFile());
        assertEquals(SaveDisposition.REPLACE_EXISTING, state.getSaveDisposition());
        assertTrue(state.isDirty());
        assertEquals(cursor, state.getCursor());
        assertEquals(selection, state.getSelectionAnchor());
        assertEquals(viewport, state.viewport());
        assertNoStagedFiles();

        assertTrue(engine.undo());
        assertEquals(List.of("original", "second", "third"), model.snapshotLines());
        assertFalse(state.isDirty());
        assertTrue(engine.redo());
        assertEquals(edited, model.snapshotLines());
        assertTrue(state.isDirty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRedirectedParentAliasCannotPublishIntoItsPreviouslyResolvedDirectory(boolean overwrite) throws Exception {
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        Path alias = Files.createSymbolicLink(directory.resolve("parent"), first.getFileName());
        Path firstFile = first.resolve("target.txt");
        Path secondFile = second.resolve("target.txt");
        if (overwrite) {
            Files.writeString(firstFile, "before first");
            Files.writeString(secondFile, "before second");
        }
        var model = new RedirectingModel();
        model.insertText(new Position(0, 0), "draft");
        DocumentFileMetadata metadata = model.fileMetadata();
        model.afterNextLineRead(() -> redirect(alias, second.getFileName()));

        assertThrows(IOException.class, () -> model.save(alias.resolve("target.txt"), metadata,
            overwrite ? SaveDisposition.REPLACE_EXISTING : SaveDisposition.CREATE_NEW));

        assertSame(metadata, model.fileMetadata());
        assertNull(model.fileBaseline());
        if (overwrite) {
            assertEquals("before first", Files.readString(firstFile));
            assertEquals("before second", Files.readString(secondFile));
        } else {
            assertFalse(Files.exists(firstFile));
            assertFalse(Files.exists(secondFile));
        }
        assertNoStagedFiles();
    }

    @Test
    void changingOnlyTheAliasChainKeepsTheSameResolvedTargetAndCanCommit() throws Exception {
        Path target = Files.writeString(directory.resolve("target.txt"), "before");
        Path secondAlias = Files.createSymbolicLink(directory.resolve("second-link.txt"), target.getFileName());
        Path alias = Files.createSymbolicLink(directory.resolve("alias.txt"), target.getFileName());
        var model = new RedirectingModel();
        model.insertText(new Position(0, 0), "draft");
        model.afterNextLineRead(() -> redirect(alias, secondAlias.getFileName()));

        model.save(alias, model.fileMetadata(), SaveDisposition.REPLACE_EXISTING);

        assertEquals("draft", Files.readString(target));
        assertEquals("draft", Files.readString(alias));
        assertEquals(target, model.fileBaseline().target());
        assertTrue(Files.isSymbolicLink(alias));
        assertNoStagedFiles();
    }

    @Test
    void theEngineKeepsItsCanonicalParentWhenTheOriginalParentAliasChanges() throws Exception {
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        Path firstFile = Files.writeString(first.resolve("target.txt"), "before first");
        Path secondFile = Files.writeString(second.resolve("target.txt"), "before second");
        Path alias = Files.createSymbolicLink(directory.resolve("parent"), first.getFileName());
        var model = new RedirectingModel();
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.insertInput("draft");
        model.afterNextLineRead(() -> redirect(alias, second.getFileName()));

        engine.saveAs(alias.resolve("target.txt"), SaveDisposition.REPLACE_EXISTING);

        assertEquals("draft", Files.readString(firstFile));
        assertEquals("before second", Files.readString(secondFile));
        assertEquals(firstFile, state.getCurrentFile());
        assertEquals(firstFile, model.fileBaseline().target());
        assertFalse(state.isDirty());
        assertNoStagedFiles();
    }

    @Test
    void createNewStillRefusesAnAppearingRegularFileAtTheUnchangedResolvedName() throws Exception {
        Path target = directory.resolve("new.txt");
        var model = new RedirectingModel();
        model.insertText(new Position(0, 0), "draft");
        model.afterNextLineRead(() -> Files.writeString(target, "another writer"));

        assertThrows(FileAlreadyExistsException.class,
            () -> model.save(target, model.fileMetadata(), SaveDisposition.CREATE_NEW));

        assertEquals("another writer", Files.readString(target));
        assertNull(model.fileBaseline());
        assertNoStagedFiles();
    }

    private static void redirect(Path alias, Path target) throws IOException {
        Files.delete(alias);
        Files.createSymbolicLink(alias, target);
    }

    private void assertNoStagedFiles() throws IOException {
        try (var files = Files.walk(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @FunctionalInterface
    private interface FileChange { void apply() throws IOException; }

    /** Saving reads its first line after target resolution; that read controls the external change. */
    private static final class RedirectingModel extends TextModel {
        private FileChange change;

        RedirectingModel() { super(new SimpleLineBuffer()); }

        void afterNextLineRead(FileChange next) { change = next; }

        @Override public String getLine(int index) {
            String line = super.getLine(index);
            FileChange next = change;
            change = null;
            if (next != null) {
                try { next.apply(); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
            return line;
        }
    }
}
