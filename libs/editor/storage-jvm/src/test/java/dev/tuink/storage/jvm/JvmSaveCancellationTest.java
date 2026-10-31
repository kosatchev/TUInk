package dev.tuink.storage.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.document.ImmutableText;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentSave;
import dev.tuink.editor.storage.PublicationKind;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.SaveAuthorization;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.editor.storage.StorageOperation;
import dev.tuink.editor.storage.StorageWriteException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("user.home")
class JvmSaveCancellationTest {
    @TempDir Path directory;
    private String previousHome;

    @BeforeEach void isolateSaveLocks() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", directory.resolve("home").toString());
    }

    @AfterEach void restoreHome() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    @Test
    void aLargeExistingFileCanBeInspectedAndReplacedWithASmallDocument() throws IOException {
        JvmDocumentStorage storage = storage(new JvmDocumentStorage.PublicationFiles() {});
        Path path = directory.resolve("large.bin");
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) { file.setLength((64L << 20) + 1); }
        var resource = storage.fileResource(path);
        StorageOperation inspection = operation();
        var target = storage.inspect(resource, ResourceBinding.unbound(), inspection);
        assertEquals((64L << 20) + 1, inspection.snapshot().completedBytes());
        assertEquals((64L << 20) + 1, inspection.snapshot().totalBytes());

        var saved = storage.write(request(resource, "replacement", true),
            new SaveAuthorization.Approved(target), operation());

        assertEquals("replacement", Files.readString(path));
        assertEquals(PublicationKind.ATOMIC_REPLACEMENT, saved.publicationKind());
        assertNoStagedFiles();
    }

    @Test
    void cancellingWhileEncodingLeavesTheTargetIntactAndRemovesTheStagedFile() throws IOException {
        StorageOperation operation = operation();
        AtomicInteger writes = new AtomicInteger();
        JvmDocumentStorage storage = storage(new JvmDocumentStorage.PublicationFiles() {
            @Override public OutputStream openStaged(Path staged) throws IOException {
                return new FilterOutputStream(JvmDocumentStorage.PublicationFiles.super.openStaged(staged)) {
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                        out.write(bytes, offset, length);
                        if (length > 0) {
                            writes.incrementAndGet();
                            assertTrue(operation.requestCancellation());
                        }
                    }
                };
            }
        });
        Path path = Files.writeString(directory.resolve("existing.txt"), "original");
        var resource = storage.fileResource(path);
        var target = storage.inspect(resource, ResourceBinding.unbound());

        assertThrows(InterruptedIOException.class, () -> storage.write(request(resource, "x".repeat(30_000), true),
            new SaveAuthorization.Approved(target), operation));

        assertEquals(1, writes.get(), "Cancellation must stop encoding at its next bounded chunk");
        assertEquals("original", Files.readString(path));
        assertNoStagedFiles();
    }

    @Test
    void cancellationAfterStagingIsCheckedBeforeAnyTargetPublication() throws IOException {
        StorageOperation operation = operation();
        AtomicInteger publications = new AtomicInteger();
        JvmDocumentStorage storage = storage(new JvmDocumentStorage.PublicationFiles() {
            @Override public OutputStream openStaged(Path staged) throws IOException {
                return new FilterOutputStream(JvmDocumentStorage.PublicationFiles.super.openStaged(staged)) {
                    @Override public void close() throws IOException {
                        super.close();
                        assertTrue(operation.requestCancellation());
                    }
                };
            }
            @Override public void createLink(Path target, Path staged) { publications.incrementAndGet(); }
        });
        Path path = directory.resolve("new.txt");
        var resource = storage.fileResource(path);
        var target = storage.inspect(resource, ResourceBinding.unbound());

        assertThrows(InterruptedIOException.class, () -> storage.write(request(resource, "draft", false),
            new SaveAuthorization.Approved(target), operation));

        assertEquals(0, publications.get());
        assertFalse(Files.exists(path));
        assertNoStagedFiles();
    }

    @Test
    void cancellationLosesToAnAtomicPublicationAndItsSuccessfulReceiptIsReturned() throws IOException {
        StorageOperation operation = operation();
        JvmDocumentStorage storage = storage(new JvmDocumentStorage.PublicationFiles() {
            @Override public void move(Path staged, Path target, boolean atomic) throws IOException {
                assertTrue(operation.publishing());
                assertFalse(operation.requestCancellation());
                JvmDocumentStorage.PublicationFiles.super.move(staged, target, atomic);
            }
        });
        Path path = Files.writeString(directory.resolve("existing.txt"), "original");
        var resource = storage.fileResource(path);
        var target = storage.inspect(resource, ResourceBinding.unbound());

        var saved = storage.write(request(resource, "draft", true), new SaveAuthorization.Approved(target), operation);

        assertEquals("draft", Files.readString(path));
        assertEquals(PublicationKind.ATOMIC_REPLACEMENT, saved.publicationKind());
        assertNoStagedFiles();
    }

    @Test
    void aDefinitelyUnsupportedAtomicMoveHonoursCancellationBeforeItsFallback() throws IOException {
        StorageOperation operation = operation();
        AtomicInteger portableMoves = new AtomicInteger();
        JvmDocumentStorage storage = storage(new JvmDocumentStorage.PublicationFiles() {
            @Override public void move(Path staged, Path target, boolean atomic) throws IOException {
                if (atomic) {
                    assertFalse(operation.requestCancellation());
                    throw new AtomicMoveNotSupportedException(staged.toString(), target.toString(), "fixture");
                }
                portableMoves.incrementAndGet();
            }
        });
        Path path = Files.writeString(directory.resolve("existing.txt"), "original");
        var resource = storage.fileResource(path);
        var target = storage.inspect(resource, ResourceBinding.unbound());

        assertThrows(InterruptedIOException.class, () -> storage.write(request(resource, "draft", true),
            new SaveAuthorization.Approved(target), operation));

        assertEquals(0, portableMoves.get());
        assertEquals("original", Files.readString(path));
        assertNoStagedFiles();
    }

    @Test
    void anUnsupportedLinkHonoursCancellationBeforeCreatingAFallbackDestination() throws IOException {
        StorageOperation operation = operation();
        AtomicInteger creates = new AtomicInteger();
        JvmDocumentStorage storage = storage(new JvmDocumentStorage.PublicationFiles() {
            @Override public void createLink(Path target, Path staged) {
                assertFalse(operation.requestCancellation());
                throw new UnsupportedOperationException("fixture without links");
            }
            @Override public SeekableByteChannel createNew(Path target) throws IOException {
                creates.incrementAndGet();
                return JvmDocumentStorage.PublicationFiles.super.createNew(target);
            }
        });
        Path path = directory.resolve("new.txt");
        var resource = storage.fileResource(path);
        var target = storage.inspect(resource, ResourceBinding.unbound());

        assertThrows(InterruptedIOException.class, () -> storage.write(request(resource, "draft", false),
            new SaveAuthorization.Approved(target), operation));

        assertEquals(0, creates.get());
        assertFalse(Files.exists(path));
        assertNoStagedFiles();
    }

    @Test
    void anAmbiguousLinkFailureCannotBeReclassifiedAsCancellation() throws IOException {
        StorageOperation operation = operation();
        JvmDocumentStorage storage = storage(new JvmDocumentStorage.PublicationFiles() {
            @Override public void createLink(Path target, Path staged) throws IOException {
                JvmDocumentStorage.PublicationFiles.super.createLink(target, staged);
                assertFalse(operation.requestCancellation());
                throw new IOException("link reported failure after publication");
            }
        });
        Path path = directory.resolve("published.txt");
        var resource = storage.fileResource(path);
        var target = storage.inspect(resource, ResourceBinding.unbound());

        StorageWriteException failure = assertThrows(StorageWriteException.class, () -> storage.write(
            request(resource, "draft", false), new SaveAuthorization.Approved(target), operation));

        assertEquals(StorageWriteException.Outcome.UNKNOWN, failure.outcome());
        assertEquals("draft", Files.readString(path));
        assertNoStagedFiles();
    }

    @Test
    void anExclusiveCopyFinishesAfterItsDestinationHasBeenOpened() throws IOException {
        StorageOperation operation = operation();
        JvmDocumentStorage storage = storage(new JvmDocumentStorage.PublicationFiles() {
            @Override public void createLink(Path target, Path staged) {
                throw new UnsupportedOperationException("fixture without links");
            }
            @Override public SeekableByteChannel createNew(Path target) throws IOException {
                SeekableByteChannel output = JvmDocumentStorage.PublicationFiles.super.createNew(target);
                assertFalse(operation.requestCancellation());
                return output;
            }
        });
        Path path = directory.resolve("portable.txt");
        var resource = storage.fileResource(path);
        var target = storage.inspect(resource, ResourceBinding.unbound());

        var saved = storage.write(request(resource, "draft", false), new SaveAuthorization.Approved(target), operation);

        assertEquals(PublicationKind.STREAM_WRITE, saved.publicationKind());
        assertEquals("draft", Files.readString(path));
        assertNoStagedFiles();
    }

    private static JvmDocumentStorage storage(JvmDocumentStorage.PublicationFiles files) {
        return new JvmDocumentStorage(files);
    }

    private static DocumentSave request(dev.tuink.editor.storage.DocumentResource resource, String text, boolean replace) {
        return new DocumentSave(resource, List.of(ImmutableText.of(text)), DocumentFileMetadata.newDocument(1), null,
            replace ? SaveDisposition.REPLACE_EXISTING : SaveDisposition.CREATE_NEW);
    }

    private static StorageOperation operation() { return new StorageOperation(StorageOperation.Phase.VERIFYING); }

    private void assertNoStagedFiles() throws IOException {
        try (var paths = Files.list(directory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
