package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavePathInputTest {
    @Test
    void ordinaryLiteralPathsAndTheirDotSegmentsRemainUnchanged() {
        for (String literal : List.of("notes.txt", "file:notes.txt", "notes with spaces.txt", "../notes.txt",
            "folder/../notes.txt", "./file:/notes.txt", "данные #50% + 中😀.txt")) {
            Path path = Path.of(literal);
            assertEquals(path, SavePathInput.parse(literal));
            assertEquals(path.toString(), SavePathInput.format(path));
        }
    }

    @Test
    void rawUnixFileAndParentBytesRoundTripThroughAsciiUrisWithoutNormalizingDotSegments() {
        assumeTrue(File.separatorChar == '/');
        for (String encoded : List.of("file:///tmp/legacy-%FF.txt", "file:///tmp/parent-%FE/document.txt",
            "file:///tmp/parent-%FE/../legacy-%FF.txt")) {
            Path path = Path.of(URI.create(encoded));
            assertEquals(path, SavePathInput.parse(encoded));
            assertEquals(encoded, SavePathInput.format(path));
        }
    }

    @Test
    void anExplicitUriCanNameAnOrdinaryFileWithoutChangingItsIdentity() {
        Path absolute = Path.of("notes with spaces #50%.txt").toAbsolutePath();
        assertEquals(absolute, SavePathInput.parse(absolute.toUri().toASCIIString()));
    }

    @Test
    void controlCharactersAreEscapedBeforeAPathEntersASingleLineField() {
        assumeTrue(File.separatorChar == '/');
        for (String name : List.of("line\nbreak.txt", "line\rbreak.txt", "tab\tname.txt", "control\u0001.txt",
            "delete\u007f.txt", "next\u0085line.txt")) {
            Path path = Path.of(name);
            String formatted = SavePathInput.format(path);

            assertTrue(formatted.startsWith("file:/"));
            assertTrue(formatted.codePoints().noneMatch(Character::isISOControl));
            assertEquals(path.toAbsolutePath(), SavePathInput.parse(formatted));
        }
    }

    @Test
    void malformedExplicitUrisAreRejected() {
        for (String text : List.of("file:///bad%zz", "file:///bad%00", "file:///tmp/file?query",
            "file:///tmp/file#fragment", "file:///bad path")) {
            assertThrows(IllegalArgumentException.class, () -> SavePathInput.parse(text), text);
        }
    }

    @Test
    void copyAndNumberSuffixesPreserveRawBytesAndExistingExtensionRules() {
        assumeTrue(File.separatorChar == '/');
        for (String name : List.of("legacy-%FF.txt", ".hidden-%FF", "legacy-%FF", "legacy-%FF.tar.gz")) {
            Path raw = Path.of(URI.create("file:///tmp/" + name));
            int dot = name.lastIndexOf('.');
            int insertion = dot > 0 ? dot : name.length();
            for (String suffix : List.of("-copy", "-1")) {
                Path expected = Path.of(URI.create("file:///tmp/" + name.substring(0, insertion)
                    + suffix + name.substring(insertion)));
                assertEquals(expected, SavePathInput.withNameSuffix(raw, suffix));
                assertEquals(expected.getFileName(), SavePathInput.withNameSuffix(raw.getFileName(), suffix));
            }
        }
        Path rawParent = Path.of(URI.create("file:///tmp/parent-%FE/document.txt"));
        assertEquals(rawParent.resolveSibling("document-copy.txt"), SavePathInput.withNameSuffix(rawParent, "-copy"));
    }

    @Test
    void aLiteralFileColonDirectoryIsNotFormattedAsAnotherFilesUri() {
        assumeTrue(File.separatorChar == '/');
        Path literal = Path.of("file:/notes.txt");
        String formatted = SavePathInput.format(literal);
        assertTrue(formatted.startsWith("file:///"));
        assertEquals(literal.toAbsolutePath(), SavePathInput.parse(formatted));
    }

    @Test
    void maximalAsciiNamesReserveCopyAndNumberSuffixesBeforeTheExtension() {
        Path original = Path.of("a".repeat(251) + ".txt");

        assertEquals(Path.of("a".repeat(246) + "-copy.txt"), SavePathInput.withNameSuffix(original, "-copy"));
        assertEquals(Path.of("a".repeat(243) + "-copy-10.txt"), SavePathInput.withNameSuffix(original, "-copy-10"));
        assertEquals(Path.of("a".repeat(249) + "-1.txt"), SavePathInput.withNameSuffix(original, "-1"));
    }

    @Test
    void unicodeNamesRespectEncodedBytesAndNeverSplitMultibyteScalars() {
        for (String name : List.of("中".repeat(83) + ".txt", "😀".repeat(62) + "é.txt",
            "я".repeat(125) + ".txt")) {
            Path generated = SavePathInput.withNameSuffix(Path.of(name), "-copy");
            String result = generated.getFileName().toString();
            assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= 255, result);
            assertTrue(result.endsWith("-copy.txt"));
            assertTrue(name.startsWith(result.substring(0, result.length() - "-copy.txt".length())));
            assertEquals(result, new String(result.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        }
        assertEquals(Path.of("中".repeat(82) + "-copy.txt"),
            SavePathInput.withNameSuffix(Path.of("中".repeat(83) + ".txt"), "-copy"));
        assertEquals(Path.of("😀".repeat(61) + "-copy.txt"),
            SavePathInput.withNameSuffix(Path.of("😀".repeat(62) + "é.txt"), "-copy"));
    }

    @Test
    void nativeRawBytesAreCountedBeforeUriEscapingAndRetainedWhenTheStemIsShortened() {
        assumeTrue(File.separatorChar == '/');
        Path original = Path.of(URI.create("file:///tmp/parent-%FE/%FF" + "a".repeat(250) + ".txt"));
        Path expected = Path.of(URI.create("file:///tmp/parent-%FE/%FF" + "a".repeat(245) + "-copy.txt"));

        assertEquals(expected, SavePathInput.withNameSuffix(original, "-copy"));
        assertEquals(expected.getFileName(), SavePathInput.withNameSuffix(original.getFileName(), "-copy"));
        assertEquals(expected, SavePathInput.parse(SavePathInput.format(expected)));
    }

    @Test
    void anExtremeExtensionIsShortenedAfterReservingTheSuffixAndAStemScalar() {
        assertEquals(Path.of("a-copy." + "x".repeat(248)),
            SavePathInput.withNameSuffix(Path.of("a." + "x".repeat(253)), "-copy"));
        assertEquals(Path.of("a-copy." + "中".repeat(82)),
            SavePathInput.withNameSuffix(Path.of("a." + "中".repeat(84)), "-copy"));
        assertEquals(Path.of(".hidden-copy"), SavePathInput.withNameSuffix(Path.of(".hidden"), "-copy"));
    }

    @Test
    void aNonNativeProviderKeepsItsOwnPathsAndUsesAConservativeUnicodeBudget(@TempDir Path directory) throws Exception {
        Path archive = directory.resolve("names.zip");
        try (var filesystem = FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
            Path original = filesystem.getPath("/" + "中".repeat(83) + ".txt");
            Path generated = SavePathInput.withNameSuffix(original, "-copy");

            assertEquals(filesystem.getPath("/" + "中".repeat(82) + "-copy.txt"), generated);
            Files.writeString(generated, "saved");
            assertEquals("saved", Files.readString(generated));
        }
    }
}
