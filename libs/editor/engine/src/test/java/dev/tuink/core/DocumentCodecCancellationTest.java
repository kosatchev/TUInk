package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.StorageOperation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DocumentCodecCancellationTest {
    @Test
    void cancellationStopsALongLineWithoutMaterializingOrDrainingIt() {
        StorageOperation operation = new StorageOperation(StorageOperation.Phase.STAGING);
        AtomicInteger characters = new AtomicInteger();
        AtomicInteger bytes = new AtomicInteger();
        CharSequence line = new CharSequence() {
            @Override public int length() { return 1_000_000; }
            @Override public char charAt(int index) { characters.incrementAndGet(); return 'x'; }
            @Override public CharSequence subSequence(int start, int end) { return fail("Read text by bounded range"); }
            @Override public String toString() { return fail("Do not flatten the complete line before cancellation"); }
        };
        OutputStream output = new OutputStream() {
            @Override public void write(int value) { fail("Use bounded byte buffers"); }
            @Override public void write(byte[] buffer, int offset, int length) {
                bytes.addAndGet(length);
                if (length > 0) operation.requestCancellation();
            }
        };

        assertThrows(InterruptedIOException.class,
            () -> DocumentCodec.write(output, List.of(line), DocumentFileMetadata.newDocument(1), operation));

        assertTrue(bytes.get() > 0);
        assertTrue(bytes.get() <= 8192);
        assertTrue(characters.get() <= 8192, "The rest of the line must remain unread after cancellation");
    }

    @Test
    void cancellationDuringLineEndingPreparationDoesNotStartEncoding() {
        int count = 100_000;
        StorageOperation operation = new StorageOperation(StorageOperation.Phase.STAGING);
        List<CharSequence> lines = new AbstractList<>() {
            @Override public int size() { return count; }
            @Override public CharSequence get(int index) {
                operation.requestCancellation();
                return "x";
            }
        };
        DocumentFileMetadata metadata = DocumentFileMetadata.newDocument(count).withLineSeparator(0, "\r");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThrows(InterruptedIOException.class, () -> DocumentCodec.write(output, lines, metadata, operation));

        assertEquals(0, output.size());
    }

    @Test
    void savingManyLinesPreservesAnEmptyLineBetweenBareCrAndLf() throws Exception {
        List<String> lines = new ArrayList<>(Collections.nCopies(9000, "x"));
        lines.set(8193, "");
        DocumentFileMetadata metadata = DocumentFileMetadata.newDocument(lines.size())
            .withLineSeparator(8192, "\r");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        var encoded = DocumentCodec.write(output, lines, metadata);
        var decoded = DocumentCodec.read(new ByteArrayInputStream(output.toByteArray()), DocumentOpenMode.DETECT,
            new DocumentCodec.LoadLimits(1 << 20, 16L << 20), "many-lines.txt");

        assertEquals("\r\n", encoded.metadata().lineSeparators().get(8193));
        assertEquals(lines, decoded.lines());
    }
}
