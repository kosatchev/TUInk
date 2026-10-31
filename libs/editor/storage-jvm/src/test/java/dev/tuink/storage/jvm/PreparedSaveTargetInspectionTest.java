package dev.tuink.storage.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tuink.editor.storage.StorageOperation;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedSaveTargetInspectionTest {
    @TempDir Path directory;

    @Test
    void largeTargetsStartStreamingWithTheirLongByteCountAndCanBeCancelled() throws IOException {
        Path path = directory.resolve("large-target.bin");
        long size = 3L << 30;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(size);
        }
        StorageOperation operation = operation();
        AtomicBoolean opened = new AtomicBoolean();

        assertThrows(InterruptedIOException.class, () -> PreparedSaveTarget.inspect(path, operation, ignored -> {
            opened.set(true);
            assertEquals(size, operation.snapshot().totalBytes());
            operation.requestCancellation();
            return new ByteArrayInputStream(new byte[0]);
        }));

        assertTrue(opened.get(), "An arbitrary size must not prevent target inspection");
        assertTrue(operation.cancelled());
        assertEquals(size, Files.size(path));
    }

    @Test
    void ordinaryEmptyAndMissingTargetsRetainTheirExactObservation() throws IOException {
        Path path = Files.writeString(directory.resolve("exact.txt"), "12345678");
        StorageOperation operation = operation();
        PreparedSaveTarget exact = PreparedSaveTarget.inspect(path, operation);
        assertTrue(exact.exists());
        assertEquals(8L, operation.snapshot().completedBytes());
        assertEquals(8L, operation.snapshot().totalBytes());
        exact.beginWrite(operation());
        exact.requireUnchanged(operation());

        Path empty = Files.createFile(directory.resolve("empty.txt"));
        assertTrue(PreparedSaveTarget.inspect(empty, operation()).exists());
        Path missing = directory.resolve("new.txt");
        assertFalse(PreparedSaveTarget.inspect(missing, operation(),
            ignored -> fail("A missing target has no content to inspect")).exists());
    }

    @Test
    void growthAfterApprovalIsRejectedBeforeOpeningTheTargetAgain() throws IOException {
        Path path = Files.writeString(directory.resolve("growing.txt"), "12345678");
        PreparedSaveTarget inspected = PreparedSaveTarget.inspect(path, operation());
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) { file.setLength(3L << 30); }

        assertThrows(IOException.class, () -> inspected.requireUnchanged(operation(),
            ignored -> fail("An enlarged target must not trigger another content scan")));
        assertEquals(3L << 30, Files.size(path));
    }

    @Test
    void growthDuringReadingConsumesOnlyTheObservedLengthPlusOneByte() throws IOException {
        Path path = Files.writeString(directory.resolve("growth-during-read.txt"), "12345678");
        ByteArrayInputStream stream = new ByteArrayInputStream("12345678more".getBytes(StandardCharsets.UTF_8));
        int before = stream.available();

        assertThrows(IOException.class, () -> PreparedSaveTarget.inspect(path, operation(), ignored -> stream));

        assertEquals(9, before - stream.available());
    }

    @Test
    void anAlreadyInterruptedInspectionDoesNotOpenTheFileOrClearTheFlag() throws IOException {
        Path path = Files.writeString(directory.resolve("interrupted.txt"), "12345678");
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> PreparedSaveTarget.inspect(path, operation(),
                ignored -> fail("A cancelled inspection must not open the target")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancellationBetweenReadsClosesTheStreamWithoutDrainingTheRemainder() throws IOException {
        Path path = Files.writeString(directory.resolve("cancel-during-read.txt"), "12345678");
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream stream = new ByteArrayInputStream("12345678".getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                int count = super.read(buffer, offset, Math.min(length, 3));
                Thread.currentThread().interrupt();
                return count;
            }
            @Override public void close() { closed.set(true); }
        };
        try {
            assertThrows(InterruptedIOException.class, () -> PreparedSaveTarget.inspect(path, operation(), ignored -> stream));
            assertEquals(5, stream.available());
            assertTrue(closed.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancellationOnTheFinalReadDoesNotStartTheEofProbe() throws IOException {
        Path path = Files.writeString(directory.resolve("cancel-before-eof.txt"), "12345678");
        ByteArrayInputStream stream = new ByteArrayInputStream("12345678".getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                int count = super.read(buffer, offset, length);
                Thread.currentThread().interrupt();
                return count;
            }
            @Override public synchronized int read() { return fail("Cancellation must precede the EOF read"); }
        };
        try {
            assertThrows(InterruptedIOException.class, () -> PreparedSaveTarget.inspect(path, operation(), ignored -> stream));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aFileAppearingAfterInspectionDoesNotGetScannedOrOverwritten() throws IOException {
        Path path = directory.resolve("appeared.bin");
        PreparedSaveTarget inspected = PreparedSaveTarget.inspect(path, operation());
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) { file.setLength(3L << 30); }
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> inspected.requireUnchanged(operation(),
            ignored -> fail("An unapproved new file must not be read")));
    }

    private static StorageOperation operation() { return new StorageOperation(StorageOperation.Phase.INSPECTING); }
}
