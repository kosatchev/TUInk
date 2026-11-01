package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentOpenMode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DocumentCodecOpenModeTest {
    private static final DocumentCodec.LoadLimits LIMITS = new DocumentCodec.LoadLimits(4096, 1_000_000L);

    @ParameterizedTest
    @MethodSource("formats")
    void explicitOpeningPreservesEncodingBomMixedEndingsAndFinalNewline(
        DocumentOpenMode mode, Charset charset, BomKind bom
    ) throws Exception {
        for (boolean finalNewline : new boolean[] {false, true}) {
            String text = "one\r\nПривет, Ёж!\nхвост\rlast" + (finalNewline ? "\n" : "");
            byte[] original = encoded(text, charset, bom);

            DocumentCodec.DecodedContent decoded = read(original, mode);

            assertEquals(finalNewline ? List.of("one", "Привет, Ёж!", "хвост", "last", "")
                : List.of("one", "Привет, Ёж!", "хвост", "last"), decoded.lines());
            assertEquals(charset, decoded.metadata().charset());
            assertEquals(bom, decoded.metadata().bomKind());
            assertEquals(finalNewline ? List.of("\r\n", "\n", "\r", "\n")
                : List.of("\r\n", "\n", "\r"), decoded.metadata().lineSeparators());
            assertEquals(original.length, decoded.fingerprint().byteSize());
            assertArrayEquals(original, write(decoded));
        }
    }

    @ParameterizedTest
    @MethodSource("formats")
    void anEmptyFileRetainsItsExplicitFormat(DocumentOpenMode mode, Charset charset, BomKind bom) throws Exception {
        byte[] original = encoded("", charset, bom);

        DocumentCodec.DecodedContent decoded = read(original, mode);

        assertEquals(List.of(""), decoded.lines());
        assertEquals(charset, decoded.metadata().charset());
        assertEquals(bom, decoded.metadata().bomKind());
        assertArrayEquals(original, write(decoded));
    }

    @ParameterizedTest
    @MethodSource("singleByteFormats")
    void explicitSingleByteOpeningPreservesAsciiAndOverridesValidUtf8(DocumentOpenMode mode, Charset charset)
        throws Exception {
        byte[] asciiBytes = "ASCII\r\n".getBytes(StandardCharsets.US_ASCII);
        DocumentCodec.DecodedContent ascii = read(asciiBytes, mode);
        assertEquals(List.of("ASCII", ""), ascii.lines());
        assertEquals(charset, ascii.metadata().charset());
        assertArrayEquals(asciiBytes, write(ascii));

        byte[] utf8Bytes = {(byte) 0xC2, (byte) 0xA0};
        DocumentCodec.DecodedContent forced = read(utf8Bytes, mode);
        assertEquals(List.of(new String(utf8Bytes, charset)), forced.lines());
        assertEquals(charset, forced.metadata().charset());
        assertEquals(BomKind.NONE, forced.metadata().bomKind());
        assertArrayEquals(utf8Bytes, write(forced));
        assertEquals(StandardCharsets.UTF_8, read(utf8Bytes, DocumentOpenMode.DETECT).metadata().charset());
    }

    @ParameterizedTest
    @MethodSource("singleByteFormats")
    void explicitSingleByteOpeningTreatsEveryBomLikePrefixAsText(DocumentOpenMode mode, Charset charset)
        throws Exception {
        for (byte[] bytes : new byte[][] {
            {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 65},
            {(byte) 0xFF, (byte) 0xFE, 65},
            {(byte) 0xFE, (byte) 0xFF, 65},
            {(byte) 0xFF, (byte) 0xFE, 0, 0, 65, 0, 0, 0},
            {0, 0, (byte) 0xFE, (byte) 0xFF, 0, 0, 0, 65}
        }) {
            DocumentCodec.DecodedContent decoded = read(bytes, mode);

            assertEquals(List.of(new String(bytes, charset)), decoded.lines());
            assertEquals(charset, decoded.metadata().charset());
            assertEquals(BomKind.NONE, decoded.metadata().bomKind());
            assertArrayEquals(bytes, write(decoded));
        }
    }

    @Test
    void explicitUtf8RejectsMalformedBytesInsteadOfFallingBackToWindows1251() {
        for (byte[] bytes : new byte[][] {
            {(byte) 0xC3, 0x28}, {(byte) 0x80},
            {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, (byte) 0xC0, (byte) 0xAF},
            {(byte) 0xFF, (byte) 0xFE, 65, 0}
        }) {
            IOException failure = assertThrows(IOException.class, () -> read(bytes, DocumentOpenMode.UTF_8));
            assertEquals("Invalid UTF-8 input", failure.getMessage());
            assertInstanceOf(CharacterCodingException.class, failure.getCause());
        }
    }

    @Test
    void utf8OpeningConsumesOnlyTheLeadingBomAndRetainsARealBomCharacter() throws Exception {
        byte[] bytes = encoded("\uFEFFtext", StandardCharsets.UTF_8, BomKind.UTF_8);

        DocumentCodec.DecodedContent decoded = read(bytes, DocumentOpenMode.UTF_8);

        assertEquals(List.of("\uFEFFtext"), decoded.lines());
        assertEquals(BomKind.UTF_8, decoded.metadata().bomKind());
        assertArrayEquals(bytes, write(decoded));
    }

    @Test
    void explicitWindows1251DoesNotReplaceAnUndefinedByte() {
        IOException failure = assertThrows(IOException.class,
            () -> read(new byte[] {(byte) 0x98}, DocumentOpenMode.WINDOWS_1251));
        assertEquals("Invalid windows-1251 input", failure.getMessage());
        assertInstanceOf(CharacterCodingException.class, failure.getCause());
    }

    @Test
    void explicitUtf16RequiresTheMatchingBomAndStrictlyChecksThePayload() {
        for (DocumentOpenMode mode : List.of(DocumentOpenMode.UTF_16LE, DocumentOpenMode.UTF_16BE)) {
            boolean littleEndian = mode == DocumentOpenMode.UTF_16LE;
            byte[] bom = littleEndian ? BomKind.UTF_16_LE.bytes() : BomKind.UTF_16_BE.bytes();
            byte[] wrongBom = littleEndian ? BomKind.UTF_16_BE.bytes() : BomKind.UTF_16_LE.bytes();
            for (byte[] bytes : new byte[][] {
                new byte[0], {65, 0}, {bom[0], bom[1], 65},
                {wrongBom[0], wrongBom[1], 65, 0},
                {bom[0], bom[1], (byte) (littleEndian ? 0 : 0xD8), (byte) (littleEndian ? 0xD8 : 0)}
            }) {
                assertThrows(IOException.class, () -> read(bytes, mode));
            }
            byte[] isolatedSurrogate = {bom[0], bom[1], (byte) (littleEndian ? 0 : 0xD8),
                (byte) (littleEndian ? 0xD8 : 0)};
            IOException failure = assertThrows(IOException.class, () -> read(isolatedSurrogate, mode));
            assertEquals("Invalid " + (littleEndian ? "UTF-16LE" : "UTF-16BE") + " input", failure.getMessage());
            assertInstanceOf(CharacterCodingException.class, failure.getCause());
        }
    }

    @Test
    void automaticOpeningKeepsItsExistingWindows1251Fallback() throws Exception {
        Charset charset = Charset.forName("windows-1251");
        byte[] bytes = "Привет".getBytes(charset);

        DocumentCodec.DecodedContent decoded = read(bytes, DocumentOpenMode.DETECT);

        assertEquals(List.of("Привет"), decoded.lines());
        assertEquals(charset, decoded.metadata().charset());
        assertArrayEquals(bytes, write(decoded));
    }

    private static Stream<Arguments> formats() {
        return Stream.of(
            Arguments.of(DocumentOpenMode.UTF_8, StandardCharsets.UTF_8, BomKind.NONE),
            Arguments.of(DocumentOpenMode.UTF_8, StandardCharsets.UTF_8, BomKind.UTF_8),
            Arguments.of(DocumentOpenMode.UTF_16LE, StandardCharsets.UTF_16LE, BomKind.UTF_16_LE),
            Arguments.of(DocumentOpenMode.UTF_16BE, StandardCharsets.UTF_16BE, BomKind.UTF_16_BE),
            Arguments.of(DocumentOpenMode.WINDOWS_1251, Charset.forName("windows-1251"), BomKind.NONE),
            Arguments.of(DocumentOpenMode.CP866, Charset.forName("IBM866"), BomKind.NONE),
            Arguments.of(DocumentOpenMode.KOI8_R, Charset.forName("KOI8-R"), BomKind.NONE)
        );
    }

    private static Stream<Arguments> singleByteFormats() {
        return Stream.of(
            Arguments.of(DocumentOpenMode.WINDOWS_1251, Charset.forName("windows-1251")),
            Arguments.of(DocumentOpenMode.CP866, Charset.forName("IBM866")),
            Arguments.of(DocumentOpenMode.KOI8_R, Charset.forName("KOI8-R"))
        );
    }

    private static DocumentCodec.DecodedContent read(byte[] bytes, DocumentOpenMode mode) throws IOException {
        return DocumentCodec.read(new ByteArrayInputStream(bytes), mode, LIMITS, "fixture.txt");
    }

    private static byte[] write(DocumentCodec.DecodedContent decoded) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DocumentCodec.write(output, decoded.lines(), decoded.metadata());
        return output.toByteArray();
    }

    private static byte[] encoded(String text, Charset charset, BomKind bom) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(bom.bytes());
        output.write(text.getBytes(charset));
        return output.toByteArray();
    }
}
