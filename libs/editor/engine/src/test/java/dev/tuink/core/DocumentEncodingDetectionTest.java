package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentEncodingDetectionTest {
    @TempDir Path directory;

    @Test
    void utf16LeadingNulRoundTripsWhenUtf32StructureIsInvalid() throws Exception {
        for (String text : new String[] {"\0A", "\0AB", "\0😀"}) {
            Path file = writeUtf16(text);
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.load(file);
            assertEquals(text, model.getLine(0));
            assertEquals(StandardCharsets.UTF_16LE, model.fileMetadata().charset());
            byte[] original = Files.readAllBytes(file);
            model.save(file);
            assertArrayEquals(original, Files.readAllBytes(file));
        }
    }

    @Test
    void ambiguousBytesRequireAnExplicitChoiceOnEachOpen() throws Exception {
        Path file = writeUtf16("\0A\0"); // Also BOM + U+0041 in UTF-32LE.
        TextModel model = new TextModel(new SimpleLineBuffer());
        assertThrows(AmbiguousDocumentEncodingException.class, () -> model.load(file));
        model.load(file, DocumentOpenMode.UTF_16LE);
        assertEquals("\0A\0", model.getLine(0));
        byte[] original = Files.readAllBytes(file);
        model.save(file);
        assertArrayEquals(original, Files.readAllBytes(file));
        assertThrows(AmbiguousDocumentEncodingException.class, () -> model.load(file));
        assertEquals("\0A\0", model.getLine(0));
    }

    @Test
    void explicitChoiceStillValidatesBomAndSurrogatesWithoutChangingTheDocument() throws Exception {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(writeUtf16("safe"));
        for (byte[] bytes : new byte[][] {{65, 0}, {(byte) 0xFF, (byte) 0xFE, 0, (byte) 0xD8}}) {
            Path bad = directory.resolve("bad.txt");
            Files.write(bad, bytes);
            assertThrows(IOException.class, () -> model.load(bad, DocumentOpenMode.UTF_16LE));
            assertEquals("safe", model.getLine(0));
        }
    }

    @Test
    void validUtf32ThatIsNotValidUtf16IsRejectedAsUnsupported() throws Exception {
        Path file = directory.resolve("utf32.txt");
        // U+1D800 is a scalar in UTF-32; its low word alone is a UTF-16 high surrogate.
        Files.write(file, new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 0, 0, (byte) 0xD8, 1, 0});
        IOException error = assertThrows(IOException.class,
            () -> new TextModel(new SimpleLineBuffer()).load(file));
        assertFalse(error instanceof AmbiguousDocumentEncodingException);
        assertTrue(error.getMessage().contains("Unsupported document encoding: UTF-32LE"));
    }

    private Path writeUtf16(String text) throws IOException {
        byte[] content = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[content.length + 2];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xFE;
        System.arraycopy(content, 0, bytes, 2, content.length);
        return Files.write(directory.resolve("utf16.txt"), bytes);
    }
}
