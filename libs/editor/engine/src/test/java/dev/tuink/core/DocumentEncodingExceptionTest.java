package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.Charset;
import java.nio.charset.UnmappableCharacterException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DocumentEncodingExceptionTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"中, 20013", "😀, 128512", "é, 769"})
    void failureIdentifiesTheFirstUnrepresentableCodePointWithoutChangingFilesOrMetadata(
        String inserted, int expectedCodePoint
    ) throws Exception {
        Charset charset = Charset.forName("windows-1251");
        byte[] original = "private-prefix\r\nПривет\r\n".getBytes(charset);
        Path source = directory.resolve("source.txt");
        Path copy = directory.resolve("copy.txt");
        Files.write(source, original);
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(source);
        model.insertText(new Position(1, model.getLine(1).length()), inserted + "private-tail");
        DocumentFileMetadata metadata = model.fileMetadata();
        FileBaseline baseline = model.fileBaseline();
        long revision = model.revision();

        for (Path target : List.of(source, copy)) {
            DocumentEncodingException failure = assertThrows(DocumentEncodingException.class,
                () -> model.save(target, metadata, target.equals(source)
                    ? SaveDisposition.REPLACE_EXISTING : SaveDisposition.CREATE_NEW));

            assertEquals(charset.name(), failure.charsetName());
            assertEquals(expectedCodePoint, failure.codePoint());
            assertInstanceOf(UnmappableCharacterException.class, failure.getCause());
            assertFalse(failure.getMessage().contains("private-prefix"));
            assertFalse(failure.getMessage().contains("private-tail"));
            assertArrayEquals(original, Files.readAllBytes(source));
            assertFalse(Files.exists(copy));
            assertEquals(metadata, model.fileMetadata());
            assertEquals(baseline, model.fileBaseline());
            assertEquals(revision, model.revision());
        }
    }
}
