package dev.tuink.storage.jvm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.core.ViewportState;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.PublicationKind;
import dev.tuink.editor.storage.ResourceVersion;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.editor.storage.SavedDocument;
import dev.tuink.editor.storage.StorageWriteException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/** Real filesystem commits followed by injected failures must never adopt a false savepoint. */
@ResourceLock("user.home")
class JvmPublicationOutcomeTest {
    @TempDir Path directory;
    private String previousHome;

    @BeforeEach void useIsolatedSaveLocks() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", directory.resolve("home").toString());
    }

    @AfterEach void restoreHome() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    @Test void ioFailureAfterRealAtomicReplacementRetainsTheOwnersDraftAndHistory() throws Exception {
        IOException injected = new IOException("reported after atomic rename");
        JvmDocumentStorage storage = new JvmDocumentStorage(new JvmDocumentStorage.PublicationFiles() {
            @Override public void move(Path staged, Path target, boolean atomic) throws IOException {
                assertTrue(atomic);
                JvmDocumentStorage.PublicationFiles.super.move(staged, target, true);
                throw injected;
            }
        });
        assertReplacementFailurePreservesOwner(storage, injected);
    }

    @Test void allocationFailureAfterRealAtomicReplacementRetainsTheOwnersDraftAndHistory() throws Exception {
        OutOfMemoryError injected = new OutOfMemoryError("reported after atomic rename");
        JvmDocumentStorage storage = new JvmDocumentStorage(new JvmDocumentStorage.PublicationFiles() {
            @Override public void move(Path staged, Path target, boolean atomic) throws IOException {
                assertTrue(atomic);
                JvmDocumentStorage.PublicationFiles.super.move(staged, target, true);
                throw injected;
            }
        });
        assertReplacementFailurePreservesOwner(storage, injected);
    }

    @Test void ioFailureAfterRealPortableReplacementIsAlsoAnUnknownOutcome() throws Exception {
        IOException injected = new IOException("reported after portable rename");
        AtomicInteger atomicAttempts = new AtomicInteger();
        AtomicInteger portableAttempts = new AtomicInteger();
        JvmDocumentStorage storage = new JvmDocumentStorage(new JvmDocumentStorage.PublicationFiles() {
            @Override public void move(Path staged, Path target, boolean atomic) throws IOException {
                if (atomic) {
                    atomicAttempts.incrementAndGet();
                    throw new AtomicMoveNotSupportedException(staged.toString(), target.toString(), "portable fixture");
                }
                portableAttempts.incrementAndGet();
                JvmDocumentStorage.PublicationFiles.super.move(staged, target, false);
                throw injected;
            }
        });
        assertReplacementFailurePreservesOwner(storage, injected);
        assertEquals(1, atomicAttempts.get());
        assertEquals(1, portableAttempts.get());
    }

    @Test void failedLinkReturnCannotTurnAnAlreadyPublishedNewFileIntoADefiniteConflict() throws Exception {
        IOException injected = new IOException("reported after real link publication");
        JvmDocumentStorage storage = new JvmDocumentStorage(new JvmDocumentStorage.PublicationFiles() {
            @Override public void createLink(Path target, Path staged) throws IOException {
                JvmDocumentStorage.PublicationFiles.super.createLink(target, staged);
                throw injected;
            }
        });
        Fixture fixture = editedOwner(storage);
        Path target = directory.resolve("linked-copy.txt");
        EditorEngine.PreparedSave prepared = fixture.engine.prepareSaveAs(storage.fileResource(target),
            SaveDisposition.CREATE_NEW, true);
        Observed before = observe(fixture.engine);

        StorageWriteException failure = assertInstanceOf(StorageWriteException.class, writeFailure(prepared));

        assertEquals(StorageWriteException.Outcome.UNKNOWN, failure.outcome());
        assertEquals(target, JvmDocumentStorage.pathOf(failure.resource()));
        FileAlreadyExistsException fallbackConflict = assertInstanceOf(FileAlreadyExistsException.class, failure.getCause());
        assertTrue(List.of(fallbackConflict.getSuppressed()).contains(injected));
        assertEquals("mine before", Files.readString(target), "The real link published complete bytes before failing");
        assertArrayEquals(fixture.originalBytes, Files.readAllBytes(fixture.source));
        assertEquals(before, observe(fixture.engine));
        assertHistoryAndRecoveryRetained(fixture.engine, before);
    }

    @Test void aDefiniteConcurrentCreateKeepsItsFileAlreadyExistsTypeAndForeignBytes() throws Exception {
        JvmDocumentStorage storage = new JvmDocumentStorage(new JvmDocumentStorage.PublicationFiles() {
            @Override public void createLink(Path target, Path staged) throws IOException {
                Files.writeString(target, "concurrent writer", java.nio.file.StandardOpenOption.CREATE_NEW);
                JvmDocumentStorage.PublicationFiles.super.createLink(target, staged);
            }
        });
        Fixture fixture = editedOwner(storage);
        Path target = directory.resolve("occupied-copy.txt");
        EditorEngine.PreparedSave prepared = fixture.engine.prepareSaveAs(storage.fileResource(target),
            SaveDisposition.CREATE_NEW, true);
        Observed before = observe(fixture.engine);

        Throwable failure = writeFailure(prepared);

        assertInstanceOf(FileAlreadyExistsException.class, failure);
        assertFalse(failure instanceof StorageWriteException);
        assertEquals("concurrent writer", Files.readString(target));
        assertArrayEquals(fixture.originalBytes, Files.readAllBytes(fixture.source));
        assertEquals(before, observe(fixture.engine));
        assertHistoryAndRecoveryRetained(fixture.engine, before);
    }

    @Test void portableExclusiveCopyReportsItsActualPublicationAndWaitsForOwnerCompletion() throws Exception {
        AtomicInteger exclusiveOpens = new AtomicInteger();
        JvmDocumentStorage storage = new JvmDocumentStorage(new JvmDocumentStorage.PublicationFiles() {
            @Override public void createLink(Path target, Path staged) {
                throw new UnsupportedOperationException("fixture without hard links");
            }
            @Override public java.nio.channels.SeekableByteChannel createNew(Path target) throws IOException {
                exclusiveOpens.incrementAndGet();
                return JvmDocumentStorage.PublicationFiles.super.createNew(target);
            }
        });
        Fixture fixture = editedOwner(storage);
        Path target = directory.resolve("portable-copy.txt");
        EditorEngine.PreparedSave prepared = fixture.engine.prepareSaveAs(storage.fileResource(target),
            SaveDisposition.CREATE_NEW, true);
        Observed before = observe(fixture.engine);
        SavedDocument saved;
        try (var worker = Executors.newSingleThreadExecutor()) {
            saved = worker.submit(prepared::write).get();
        }

        assertEquals(1, exclusiveOpens.get());
        assertEquals(PublicationKind.STREAM_WRITE, saved.publicationKind());
        assertEquals("mine before", Files.readString(target));
        assertEquals(before, observe(fixture.engine), "Publication alone cannot mutate owner state");
        assertArrayEquals(fixture.originalBytes, Files.readAllBytes(fixture.source));

        fixture.engine.completeSaved(prepared, saved);

        assertFalse(fixture.engine.state().isDirty());
        assertEquals(target, JvmDocumentStorage.pathOf(fixture.engine.state().getCurrentResource()));
        assertSame(saved.baseline(), fixture.engine.model().fileBaseline());
        assertEquals(StandardCharsets.UTF_8, fixture.engine.model().fileMetadata().charset());
        assertThrows(IllegalStateException.class, () -> fixture.engine.completeSaved(prepared, saved));
        assertTrue(fixture.engine.undo(), "Successful conversion publishes its prepared format history");
        assertEquals(before.recovery.metadata, fixture.engine.model().fileMetadata());
        assertTrue(fixture.engine.state().isDirty());
    }

    @Test void failedPortableCopyPreservesItsPartialDestinationAndTheOwnersDraft() throws Exception {
        IOException injected = new IOException("copy channel failed after writing bytes");
        JvmDocumentStorage storage = new JvmDocumentStorage(new JvmDocumentStorage.PublicationFiles() {
            @Override public void createLink(Path target, Path staged) {
                throw new UnsupportedOperationException("fixture without hard links");
            }
            @Override public java.nio.channels.SeekableByteChannel createNew(Path target) throws IOException {
                var delegate = JvmDocumentStorage.PublicationFiles.super.createNew(target);
                return new java.nio.channels.SeekableByteChannel() {
                    @Override public int write(java.nio.ByteBuffer source) throws IOException {
                        delegate.write(source);
                        throw injected;
                    }
                    @Override public int read(java.nio.ByteBuffer destination) throws IOException { return delegate.read(destination); }
                    @Override public long position() throws IOException { return delegate.position(); }
                    @Override public java.nio.channels.SeekableByteChannel position(long value) throws IOException {
                        delegate.position(value); return this;
                    }
                    @Override public long size() throws IOException { return delegate.size(); }
                    @Override public java.nio.channels.SeekableByteChannel truncate(long size) throws IOException {
                        delegate.truncate(size); return this;
                    }
                    @Override public boolean isOpen() { return delegate.isOpen(); }
                    @Override public void close() throws IOException { delegate.close(); }
                };
            }
        });
        Fixture fixture = editedOwner(storage);
        Path target = directory.resolve("incomplete-copy.txt");
        EditorEngine.PreparedSave prepared = fixture.engine.prepareSaveAs(storage.fileResource(target),
            SaveDisposition.CREATE_NEW, true);
        Observed before = observe(fixture.engine);

        StorageWriteException failure = assertInstanceOf(StorageWriteException.class, writeFailure(prepared));

        assertEquals(StorageWriteException.Outcome.UNKNOWN, failure.outcome());
        assertSame(injected, failure.getCause());
        assertTrue(Files.size(target) > 0, "Real bytes were written and must not be deleted by name");
        assertEquals(before, observe(fixture.engine));
        assertHistoryAndRecoveryRetained(fixture.engine, before);
    }

    @Test void currentSaveRetainsTheRequestedHandleWhileItsBaselineNamesThePhysicalTarget() throws Exception {
        JvmDocumentStorage storage = new JvmDocumentStorage(new JvmDocumentStorage.PublicationFiles() {});
        Fixture fixture = editedOwner(storage);
        DocumentResource originalSpelling = storage.fileResource(directory.resolve(".").resolve("original.txt"));
        fixture.engine.state().setCurrentResource(originalSpelling);
        EditorEngine.PreparedSave prepared = fixture.engine.prepareSave();
        SavedDocument saved;
        try (var worker = Executors.newSingleThreadExecutor()) {
            saved = worker.submit(prepared::write).get();
        }

        assertSame(originalSpelling, saved.resource(), "Current receipts retain the caller's handle without canonicalizing it");
        assertEquals(fixture.source.toRealPath(), JvmDocumentStorage.baselineOf(saved.baseline()).target());
        assertFalse(saved.resource().id().equals(saved.baseline().resourceId()),
            "A lexical handle and its physical baseline deliberately have different identities");
        fixture.engine.completeSaved(prepared, saved);
        assertSame(originalSpelling, fixture.engine.state().getCurrentResource());
        assertFalse(fixture.engine.state().isDirty());
        assertEquals("\uFEFFmine before", Files.readString(fixture.source, StandardCharsets.UTF_16LE));
    }

    private void assertReplacementFailurePreservesOwner(JvmDocumentStorage storage, Throwable injected) throws Exception {
        Fixture fixture = editedOwner(storage);
        EditorEngine.PreparedSave prepared = fixture.engine.prepareSaveAs(storage.fileResource(fixture.source),
            SaveDisposition.REPLACE_EXISTING, true);
        Observed before = observe(fixture.engine);

        StorageWriteException failure = assertInstanceOf(StorageWriteException.class, writeFailure(prepared));

        assertEquals(StorageWriteException.Outcome.UNKNOWN, failure.outcome());
        assertSame(injected, failure.getCause());
        assertEquals(fixture.source, JvmDocumentStorage.pathOf(failure.resource()));
        assertEquals("mine before", Files.readString(fixture.source), "The real rename committed UTF-8 before failing");
        assertEquals(before, observe(fixture.engine));
        assertThrows(IllegalStateException.class, prepared::write, "A write with an unknown outcome cannot be replayed");
        IOException retry = assertThrows(IOException.class, () -> fixture.engine.prepareSave().write());
        assertFalse(retry instanceof StorageWriteException, "The old baseline must reject retry before another publication");
        assertTrue(retry.getMessage().contains("File changed outside TUInk"));
        assertHistoryAndRecoveryRetained(fixture.engine, before);
    }

    private Fixture editedOwner(JvmDocumentStorage storage) throws IOException {
        Path source = directory.resolve("original.txt");
        byte[] original = "\uFEFFbefore".getBytes(StandardCharsets.UTF_16LE);
        Files.write(source, original);
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        engine.setAutoPairsEnabled(false);
        engine.setAutoIndentEnabled(false);
        engine.setAutoListEnabled(false);
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        engine.openExisting(storage.fileResource(source));
        engine.insertInput("mine ");
        engine.insertNewline();
        assertTrue(engine.undo(), "Fixture retains a redo branch before saving");
        return new Fixture(source, original, engine);
    }

    private static Throwable writeFailure(EditorEngine.PreparedSave prepared) throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> worker.submit(prepared::write).get());
            return failure.getCause();
        }
    }

    private static void assertHistoryAndRecoveryRetained(EditorEngine engine, Observed before) {
        assertEquals(before, observe(engine));
        assertTrue(engine.state().isDirty());
        assertTrue(engine.redo(), "Failure must retain the preexisting redo branch");
        assertEquals(List.of("mine ", "before"), engine.model().snapshotLines());
        assertTrue(engine.undo());
        assertEquals(before.recovery.lines, engine.model().snapshotLines());
        assertTrue(engine.undo(), "The original edit remains undoable");
        assertEquals(List.of("before"), engine.model().snapshotLines());
        assertFalse(engine.state().isDirty(), "Failure must retain the original savepoint");
        assertTrue(engine.redo());
        assertEquals(before.recovery.lines, engine.model().snapshotLines());
        assertTrue(engine.state().isDirty());
        assertEquals(before.recovery.metadata, engine.model().fileMetadata());
        assertEquals(before.recovery.resource, engine.state().getCurrentResource());
        assertEquals(before.recovery.baseline, engine.model().fileBaseline());
    }

    private static Observed observe(EditorEngine engine) {
        RecoverySnapshot recovery = engine.captureRecoverySnapshot();
        return new Observed(new RecoveryState(recovery.lines(), recovery.cursor(), recovery.viewport(), recovery.selectionAnchor(),
            recovery.dirty(), recovery.currentResource(), recovery.saveDisposition(), recovery.fileMetadata(), recovery.fileBaseline()),
            engine.model().revision(), engine.model().contentEpoch(), engine.recoveryRevision(), engine.navigationRevision(),
            engine.state().getFileEncoding(), engine.state().getLineSeparator());
    }

    private record Fixture(Path source, byte[] originalBytes, EditorEngine engine) {}
    private record RecoveryState(List<String> lines, Position cursor, ViewportState viewport, Position selection,
                                 boolean dirty, DocumentResource resource, SaveDisposition disposition,
                                 DocumentFileMetadata metadata, ResourceVersion baseline) {}
    private record Observed(RecoveryState recovery, long revision, long epoch, long recoveryRevision,
                            long navigationRevision, Charset reflectedEncoding, String reflectedSeparator) {}
}
