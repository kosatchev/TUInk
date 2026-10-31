package dev.tuink.storage.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tuink.core.*;
import dev.tuink.editor.storage.StorageOperation;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBaselineTest {
    @TempDir Path directory;

    @Test
    void externalGrowthIsRejectedBeforeOpeningTheContent() throws IOException {
        Path path = directory.resolve("growing.txt");
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        Files.write(path, original);
        FileBaseline baseline = FileBaseline.fromBytes(path, original);
        assertEquals(original.length, baseline.byteSize());
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(1L << 30);
        }

        assertThrows(IOException.class, () -> baseline.requireUnchanged(path,
            ignored -> fail("A changed byte count must not trigger a content scan")));
        assertEquals(1L << 30, Files.size(path));
    }

    @Test
    void growthAfterTheSizeCheckReadsAtMostTheOriginalLengthPlusOneByte() throws IOException {
        Path path = directory.resolve("growing-during-read.txt");
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        Files.write(path, original);
        FileBaseline baseline = FileBaseline.fromBytes(path, original);
        ByteArrayInputStream stream = new ByteArrayInputStream("original plus appended bytes".getBytes(StandardCharsets.UTF_8));
        int initial = stream.available();

        assertThrows(IOException.class, () -> baseline.requireUnchanged(path, ignored -> stream));

        assertEquals(original.length + 1, initial - stream.available());
    }

    @Test
    void aShortReadCannotPassTheDigestCheck() throws IOException {
        Path path = directory.resolve("shrinking.txt");
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        Files.write(path, original);
        FileBaseline baseline = FileBaseline.fromBytes(path, original);

        assertThrows(IOException.class, () -> baseline.requireUnchanged(path,
            ignored -> new ByteArrayInputStream(new byte[] {'o'})));
    }

    @Test
    void unchangedEmptyAndNonemptyFilesStillPass() throws IOException {
        Path path = directory.resolve("unchanged.txt");
        for (String text : new String[] {"", "original"}) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
            FileBaseline.fromBytes(path, bytes).requireUnchanged(path);
        }
    }

    @Test
    void anUnknownSizeUsesTheObservedLengthAndCanBeCancelledWithoutASizeCap() throws IOException {
        Path path = directory.resolve("legacy.txt");
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        Files.write(path, original);
        FileBaseline known = FileBaseline.fromBytes(path, original);
        FileBaseline legacy = new FileBaseline(path, known.sha256());
        legacy.requireUnchanged(path);
        long size = 3L << 30;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(size);
        }
        StorageOperation operation = new StorageOperation(StorageOperation.Phase.VERIFYING);

        assertThrows(InterruptedIOException.class, () -> legacy.requireUnchanged(path, ignored -> {
            assertEquals(size, operation.snapshot().totalBytes());
            return new ByteArrayInputStream(original) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    int count = super.read(bytes, offset, length);
                    operation.requestCancellation();
                    return count;
                }
            };
        }, operation));
        assertTrue(operation.cancelled());
        assertEquals(size, Files.size(path));
    }
}
