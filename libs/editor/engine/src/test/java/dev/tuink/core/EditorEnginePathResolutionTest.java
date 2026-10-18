package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorEnginePathResolutionTest {
    @TempDir Path directory;

    @BeforeEach
    void requirePosixSymlinks() throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
    }

    @Test
    void openAndSaveResolveSymlinkBeforeTheFollowingParentSegment() throws IOException {
        Path requested = pathThroughParentLink("document.txt");
        Path actual = directory.resolve("real/document.txt");
        Path unrelated = directory.resolve("document.txt");
        Files.writeString(actual, "expected");
        Files.writeString(unrelated, "unrelated");
        EditorEngine engine = emptyEngine();

        engine.open(requested);
        assertEquals("expected", engine.model().getLine(0));
        assertEquals(actual, engine.state().getCurrentFile());
        engine.insertInput("edited ");
        engine.save();

        assertEquals("edited expected", Files.readString(actual));
        assertEquals("unrelated", Files.readString(unrelated));
    }

    @Test
    void saveAsUsesTheSameTargetForNewFilesAndExplicitOverwrite() throws IOException {
        for (boolean overwrite : List.of(false, true)) {
            Path scenario = Files.createDirectory(directory.resolve(Boolean.toString(overwrite)));
            Files.createDirectories(scenario.resolve("real/deep"));
            Files.createSymbolicLink(scenario.resolve("link"), Path.of("real/deep"));
            Path requested = scenario.resolve("link/../document.txt");
            Path actual = scenario.resolve("real/document.txt");
            Path unrelated = Files.writeString(scenario.resolve("document.txt"), "unrelated");
            if (overwrite) {
                Files.writeString(actual, "replace this");
            }
            EditorEngine engine = emptyEngine();
            engine.insertInput("draft");

            engine.saveAs(requested, overwrite ? SaveDisposition.REPLACE_EXISTING : SaveDisposition.CREATE_NEW);

            assertEquals("draft", Files.readString(actual));
            assertEquals("unrelated", Files.readString(unrelated));
            assertEquals(actual, engine.state().getCurrentFile());
            assertFalse(engine.state().isDirty());
        }
    }

    @Test
    void directModelSaveAlsoResolvesTheFilesystemParentBeforeDotSegments() throws IOException {
        Path requested = pathThroughParentLink("new.txt");
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.insertText(new Position(0, 0), "draft");

        model.save(requested, model.fileMetadata(), SaveDisposition.CREATE_NEW);

        assertEquals("draft", Files.readString(directory.resolve("real/new.txt")));
        assertFalse(Files.exists(directory.resolve("new.txt")));
    }

    @Test
    void createNewThroughParentSegmentsNeverOverwritesTheResolvedExistingFile() throws IOException {
        Path requested = pathThroughParentLink("existing.txt");
        Path actual = Files.writeString(directory.resolve("real/existing.txt"), "requested original");
        Path unrelated = Files.writeString(directory.resolve("existing.txt"), "unrelated original");
        EditorEngine engine = emptyEngine();
        engine.insertInput("draft");

        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> engine.saveAs(requested));

        assertEquals("requested original", Files.readString(actual));
        assertEquals("unrelated original", Files.readString(unrelated));
        assertTrue(engine.state().isDirty());
    }

    @Test
    void sameTargetSaveAsCannotBypassExternalConflictThroughDotSegments() throws IOException {
        Path requested = pathThroughParentLink("existing.txt");
        Path actual = Files.writeString(directory.resolve("real/existing.txt"), "before");
        EditorEngine engine = emptyEngine();
        engine.open(actual);
        engine.insertInput("mine ");
        Files.writeString(actual, "external");

        assertThrows(IOException.class, () -> engine.saveAs(requested, SaveDisposition.REPLACE_EXISTING));

        assertEquals("external", Files.readString(actual));
        assertTrue(engine.state().isDirty());
    }

    @Test
    void finalSymlinkAliasRemainsTrackedAndItsRetargetingIsRejected() throws IOException {
        Path requested = pathThroughParentLink("alias.txt");
        Path actual = Files.writeString(directory.resolve("real/first.txt"), "before");
        Path other = Files.writeString(directory.resolve("real/second.txt"), "before");
        Path alias = Files.createSymbolicLink(directory.resolve("real/alias.txt"), actual.getFileName());
        EditorEngine engine = emptyEngine();
        engine.open(requested);
        assertEquals(alias, engine.state().getCurrentFile());
        assertEquals(actual, engine.model().fileBaseline().target());
        engine.insertInput("mine ");
        Files.delete(alias);
        Files.createSymbolicLink(alias, other.getFileName());

        assertThrows(IOException.class, engine::save);

        assertEquals("before", Files.readString(actual));
        assertEquals("before", Files.readString(other));
        assertTrue(Files.isSymbolicLink(alias));
    }

    @Test
    void missingIntermediateLinksAndDirectoryEndpointsAreRejectedWithoutChangingTheDocument() throws IOException {
        Path link = Files.createSymbolicLink(directory.resolve("missing-link"), Path.of("missing-directory"));
        EditorEngine engine = emptyEngine();
        engine.insertInput("keep");
        for (Path invalid : List.of(link.resolve("../new.txt"), directory.resolve("."), directory.resolve(".."))) {
            assertThrows(IOException.class, () -> engine.open(invalid), invalid.toString());
            assertThrows(IOException.class, () -> engine.saveAs(invalid), invalid.toString());
            assertEquals(List.of("keep"), engine.model().snapshotLines());
            assertTrue(engine.state().isDirty());
        }
        assertFalse(Files.exists(directory.resolve("new.txt")));
    }

    @Test
    void aBrokenFinalSymlinkCannotBecomeANewDocumentOrBeReplacedBySaveAs() throws IOException {
        Path requested = pathThroughParentLink("broken.txt");
        Path alias = Files.createSymbolicLink(directory.resolve("real/broken.txt"), Path.of("missing.txt"));
        EditorEngine engine = emptyEngine();
        engine.insertInput("keep");

        assertThrows(java.nio.file.NoSuchFileException.class, () -> engine.open(requested));
        assertThrows(java.nio.file.NoSuchFileException.class,
            () -> engine.saveAs(requested, SaveDisposition.REPLACE_EXISTING));

        assertTrue(Files.isSymbolicLink(alias));
        assertFalse(Files.exists(directory.resolve("real/missing.txt")));
        assertEquals(List.of("keep"), engine.model().snapshotLines());
        assertTrue(engine.state().isDirty());
    }

    private Path pathThroughParentLink(String name) throws IOException {
        Files.createDirectories(directory.resolve("real/deep"));
        Files.createSymbolicLink(directory.resolve("link"), Path.of("real/deep"));
        return directory.resolve("link/../" + name);
    }

    private static EditorEngine emptyEngine() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        return new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
    }
}
