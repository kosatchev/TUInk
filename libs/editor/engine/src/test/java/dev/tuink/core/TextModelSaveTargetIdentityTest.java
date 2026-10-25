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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
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
        model.duringNextEncoding(() -> redirect(alias, second.getFileName()));

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
        model.duringNextEncoding(() -> redirect(alias, second.getFileName()));

        assertThrows(IOException.class, () -> model.save(alias.resolve("target.txt"), metadata,
            overwrite ? SaveDisposition.REPLACE_EXISTING : SaveDisposition.CREATE_NEW));

        assertSame(metadata, model.fileMetadata());
        assertNull(model.fileBaseline());
        assertEquals(second.getFileName(), Files.readSymbolicLink(alias));
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
        model.duringNextEncoding(() -> redirect(alias, secondAlias.getFileName()));

        model.save(alias, model.fileMetadata(), SaveDisposition.REPLACE_EXISTING);

        assertEquals("draft", Files.readString(target));
        assertEquals("draft", Files.readString(alias));
        assertEquals(target, model.fileBaseline().target());
        assertEquals(secondAlias.getFileName(), Files.readSymbolicLink(alias));
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
        model.duringNextEncoding(() -> redirect(alias, second.getFileName()));

        engine.saveAs(alias.resolve("target.txt"), SaveDisposition.REPLACE_EXISTING);

        assertEquals("draft", Files.readString(firstFile));
        assertEquals("before second", Files.readString(secondFile));
        assertEquals(firstFile, state.getCurrentFile());
        assertEquals(firstFile, model.fileBaseline().target());
        assertEquals(second.getFileName(), Files.readSymbolicLink(alias));
        assertFalse(state.isDirty());
        assertNoStagedFiles();
    }

    @Test
    void createNewStillRefusesAnAppearingRegularFileAtTheUnchangedResolvedName() throws Exception {
        Path target = directory.resolve("new.txt");
        var model = new RedirectingModel();
        model.insertText(new Position(0, 0), "draft");
        model.duringNextEncoding(() -> Files.writeString(target, "another writer"));

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

    /** A real charset encoder controls the change after the detached writer resolves its target. */
    private static final class RedirectingModel extends TextModel {
        private FileChange change;

        RedirectingModel() { super(new SimpleLineBuffer()); }

        void duringNextEncoding(FileChange next) { change = next; }

        @Override public SaveRequest prepareSave(Path path, DocumentFileMetadata metadata,
                                                SaveDisposition disposition, boolean checkBaseline) {
            return super.prepareSave(path, atEncodingBoundary(metadata), disposition, checkBaseline);
        }

        @Override public void save(Path path, DocumentFileMetadata metadata, SaveDisposition disposition)
            throws IOException {
            super.save(path, atEncodingBoundary(metadata), disposition);
        }

        private DocumentFileMetadata atEncodingBoundary(DocumentFileMetadata metadata) {
            Charset charset = new EncodingCheckpointCharset(metadata.charset(), this::applyPendingChange);
            return new DocumentFileMetadata(charset, metadata.bomKind(), metadata.defaultLineSeparator(),
                metadata.lineSeparators());
        }

        private void applyPendingChange() {
            FileChange next = change;
            change = null;
            if (next != null) {
                try { next.apply(); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
        }
    }

    private static final class EncodingCheckpointCharset extends Charset {
        private final Charset delegate;
        private final Runnable boundary;

        private EncodingCheckpointCharset(Charset delegate, Runnable boundary) {
            // Charset equality and BOM compatibility use this canonical name.
            super(delegate.name(), new String[0]);
            this.delegate = delegate;
            this.boundary = boundary;
        }

        @Override public boolean contains(Charset charset) {
            return equals(charset) || delegate.contains(charset);
        }

        @Override public CharsetDecoder newDecoder() { return delegate.newDecoder(); }

        @Override public CharsetEncoder newEncoder() {
            return new EncodingCheckpointEncoder(this, delegate.newEncoder(), boundary);
        }
    }

    private static final class EncodingCheckpointEncoder extends CharsetEncoder {
        private final CharsetEncoder delegate;
        private final Runnable boundary;
        private final CharBuffer empty = CharBuffer.allocate(0);
        private boolean inputEnded;
        private boolean boundaryReached;

        private EncodingCheckpointEncoder(Charset charset, CharsetEncoder delegate, Runnable boundary) {
            super(charset, delegate.averageBytesPerChar(), delegate.maxBytesPerChar());
            this.delegate = delegate;
            this.boundary = boundary;
        }

        @Override protected CoderResult encodeLoop(CharBuffer input, ByteBuffer output) {
            // Metadata construction probes the charset with "A". Every test's
            // staged document starts with "draft", so only real document bytes
            // trigger the race, independently of preparation or snapshot reads.
            int at = input.position();
            if (!boundaryReached && input.remaining() >= 5 && input.get(at) == 'd'
                && input.get(at + 1) == 'r' && input.get(at + 2) == 'a'
                && input.get(at + 3) == 'f' && input.get(at + 4) == 't') {
                boundaryReached = true;
                boundary.run();
            }
            return delegate.encode(input, output, false);
        }

        @Override protected CoderResult implFlush(ByteBuffer output) {
            if (!inputEnded) {
                CoderResult end = delegate.encode(empty, output, true);
                if (!end.isUnderflow()) return end;
                inputEnded = true;
            }
            return delegate.flush(output);
        }

        @Override protected void implReset() {
            delegate.reset();
            inputEnded = false;
            boundaryReached = false;
        }

        @Override protected void implOnMalformedInput(CodingErrorAction action) {
            if (delegate != null) delegate.onMalformedInput(action);
        }

        @Override protected void implOnUnmappableCharacter(CodingErrorAction action) {
            if (delegate != null) delegate.onUnmappableCharacter(action);
        }

        @Override protected void implReplaceWith(byte[] replacement) {
            if (delegate != null) delegate.replaceWith(replacement);
        }
    }
}
