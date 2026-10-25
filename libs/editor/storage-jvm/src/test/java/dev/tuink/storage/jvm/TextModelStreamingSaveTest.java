package dev.tuink.storage.jvm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.core.*;
import dev.tuink.editor.document.ImmutableText;
import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentSave;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextModelStreamingSaveTest {
    @TempDir
    Path directory;

    @Test
    void preservesSupplementaryCharactersBomAndMixedEolsAcrossEncoderBuffers() throws IOException {
        String first = "a".repeat(8191) + "😀" + "Ā".repeat(8191);
        List<String> lines = List.of(first, "last😀", "middle", "");
        DocumentFileMetadata metadata = new DocumentFileMetadata(StandardCharsets.UTF_8, BomKind.UTF_8,
            "\r\n", List.of("\r\n", "\r", "\n"));
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines, metadata);
        Path target = directory.resolve("utf8.txt");

        model.save(JvmDocumentStorage.resource(target), metadata, SaveDisposition.CREATE_NEW);

        byte[] expected = ("\uFEFF" + first + "\r\nlast😀\rmiddle\n").getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, Files.readAllBytes(target));
        assertEquals(FileBaseline.fromBytes(target.toRealPath(), expected), JvmDocumentStorage.baselineOf(model.fileBaseline()));
        assertEquals(lines, model.snapshotLines());
        assertEquals(metadata, model.fileMetadata());
    }

    @Test
    void retainsStatefulEncodingAcrossLinesAndFlushesTheFinalShiftState() throws IOException {
        assumeTrue(Charset.isSupported("ISO-2022-JP"));
        Charset charset = Charset.forName("ISO-2022-JP");
        String first = "あ".repeat(9000);
        String second = "日本語".repeat(3000);
        DocumentFileMetadata metadata = new DocumentFileMetadata(charset, BomKind.NONE,
            "\r\n", List.of("\r\n"));
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(first, second), metadata);
        Path target = directory.resolve("stateful.txt");

        model.save(JvmDocumentStorage.resource(target));

        byte[] expected = (first + "\r\n" + second).getBytes(charset);
        assertArrayEquals(expected, Files.readAllBytes(target));
        assertEquals(FileBaseline.fromBytes(target.toRealPath(), expected), JvmDocumentStorage.baselineOf(model.fileBaseline()));
    }

    @ParameterizedTest
    @ValueSource(ints = {8190, 8191, 8192, 16383})
    void reportsTheWholeUnmappableCodePointAcrossInputBoundariesWithoutPublishing(int prefixLength)
        throws IOException {
        Path target = Files.writeString(directory.resolve("existing.txt"), "original");
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(JvmDocumentStorage.resource(target));
        model.replaceLines(List.of("x".repeat(prefixLength) + "😀"));
        DocumentFileMetadata before = model.fileMetadata();
        FileBaseline baseline = JvmDocumentStorage.baselineOf(model.fileBaseline());
        DocumentFileMetadata ascii = DocumentFileMetadata.canonical(StandardCharsets.US_ASCII,
            BomKind.NONE, "\n", 1);

        DocumentEncodingException failure = assertThrows(DocumentEncodingException.class,
            () -> model.save(JvmDocumentStorage.resource(target), ascii));

        assertEquals(0x1F600, failure.codePoint());
        assertEquals(StandardCharsets.US_ASCII.name(), failure.charsetName());
        assertInstanceOf(UnmappableCharacterException.class, failure.getCause());
        assertEquals("original", Files.readString(target));
        assertSame(before, model.fileMetadata());
        assertSame(baseline, JvmDocumentStorage.baselineOf(model.fileBaseline()));
        assertNoStagedFiles();
    }

    @Test
    void malformedFinalSurrogatesAreRejectedByTheCodecAndBeforeTheStoragePayloadCanBePublished() throws IOException {
        Path target = Files.writeString(directory.resolve("malformed.txt"), "original");
        String malformed = "x".repeat(8191) + '\uD83D';
        DocumentFileMetadata metadata = DocumentFileMetadata.newDocument(1);

        assertThrows(MalformedInputException.class,
            () -> DocumentCodec.write(new ByteArrayOutputStream(), List.of(malformed), metadata));
        assertThrows(IllegalArgumentException.class, () -> new DocumentSave(JvmDocumentStorage.resource(target),
            List.of(ImmutableText.of(malformed)), metadata, null, SaveDisposition.REPLACE_EXISTING));

        assertEquals("original", Files.readString(target));
        assertNoStagedFiles();
    }

    @Test
    void reportsAPrecommitAllocationFailureWithoutChangingTheTargetOrSaveState() throws IOException {
        Path target = Files.writeString(directory.resolve("allocation.txt"), "original");
        JvmDocumentStorage storage = new JvmDocumentStorage(new JvmDocumentStorage.PublicationFiles() {
            @Override public OutputStream openStaged(Path staged) throws IOException {
                return new FilterOutputStream(JvmDocumentStorage.PublicationFiles.super.openStaged(staged)) {
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                        if (length > 0) throw new OutOfMemoryError("Injected staging allocation failure");
                        out.write(bytes, offset, length);
                    }
                };
            }
        });
        var resource = storage.fileResource(target);
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(resource);
        model.insertText(new Position(0, 0), "edited");
        DocumentFileMetadata metadata = model.fileMetadata();
        FileBaseline baseline = JvmDocumentStorage.baselineOf(model.fileBaseline());
        IOException failure = assertThrows(IOException.class, () -> model.save(resource));

        assertInstanceOf(OutOfMemoryError.class, failure.getCause());
        assertEquals("original", Files.readString(target));
        assertSame(metadata, model.fileMetadata());
        assertSame(baseline, JvmDocumentStorage.baselineOf(model.fileBaseline()));
        assertNoStagedFiles();
    }

    private void assertNoStagedFiles() throws IOException {
        try (var paths = Files.list(directory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

}
