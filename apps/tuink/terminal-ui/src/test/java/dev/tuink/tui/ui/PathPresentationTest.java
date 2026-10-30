package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.cell.CellText;
import dev.tuink.tui.flow.SavePathInput;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathPresentationTest {
    @TempDir Path directory;

    @Test
    void ordinaryUnicodeAndPercentNamesRemainReadable() {
        for (String name : List.of("report.txt", "a%FF.txt", "a�.txt", "e\u0301👩‍💻界.txt")) {
            Path path = directory.resolve(name);
            assertEquals(name, PathPresentation.name(path));
            assertEquals(path.toString(), PathPresentation.full(path));
        }
    }

    @Test
    void nativeBytesControlsReservedMarkerAndSanitizedUnicodeHaveDistinctLabels() throws Exception {
        assumeTrue(File.separatorChar == '/');
        List<Path> paths = List.of(raw("a%FF.txt"), raw("a%FE.txt"), directory.resolve("a�.txt"),
            directory.resolve("a%FF.txt"), directory.resolve("file:a%FF.txt"), directory.resolve("a\n.txt"),
            directory.resolve("a\u202Eb.txt"), directory.resolve("a\u200Bb.txt"), directory.resolve("a�b.txt"));
        HashSet<String> names = new HashSet<>();
        HashSet<String> full = new HashSet<>();
        for (Path path : paths) {
            String label = PathPresentation.name(path);
            String input = PathPresentation.full(path);
            assertTrue(names.add(label), label);
            assertTrue(full.add(input), input);
            assertEquals(path, SavePathInput.parse(input));
            assertEquals(label, CellText.clip(label, 0, CellText.width(label)));
            assertEquals(input, CellText.clip(input, 0, CellText.width(input)));
        }
        assertEquals("file:a%FF.txt", PathPresentation.name(paths.get(0)));
        assertEquals("file:file%3Aa%25FF.txt", PathPresentation.name(paths.get(4)));
        assertEquals("file:a%0A.txt", PathPresentation.name(paths.get(5)));
    }

    @Test
    void rawDirectoryAndRelativeReservedNamesDoNotAcquireABasenameWorkingDirectory() throws Exception {
        assumeTrue(File.separatorChar == '/');
        Path rawDirectory = Files.createDirectory(raw("folder%FF"));
        assertEquals("file:folder%FF", PathPresentation.name(rawDirectory));
        assertEquals("file:file%3Aname.txt", PathPresentation.name(Path.of("file:name.txt")));
        assertTrue(PathPresentation.full(Path.of("file:name.txt")).startsWith("file:/"));
    }

    @Test
    void middleAndViewportCutsKeepWholePercentTokensAndGraphemes() {
        String label = "file:prefix%FF%FE%EF%BF%BD-e\u0301👩‍💻.txt";
        for (int width = 0; width <= CellText.width(label); width++) {
            String fitted = PathPresentation.fit(label, width, 4);
            assertTrue(CellText.width(fitted) <= width, fitted);
            assertTokens(fitted);
            if (fitted.contains("…") && width > 5) assertTrue(fitted.startsWith("file:"), fitted);
            for (int left = 0; left <= CellText.width(label); left++) {
                String clipped = PathPresentation.clip(label, left, width);
                assertTrue(CellText.width(clipped) <= width, clipped);
                assertTokens(clipped);
            }
        }
        assertEquals("%AB\u0301…suffix", PathPresentation.fit("%AB\u0301---suffix", 10, 6));
        assertEquals(CellText.fitMiddle("a\t%FF-long.txt", 9, 4, 3),
            PathPresentation.LABEL_POLICY.fitMiddle("a\t%FF-long.txt", 9, 4, 3));
    }

    @Test
    void encodedConfirmationRequiresTheCompleteBasenameButOrdinaryLongNamesStillFit() {
        assumeTrue(File.separatorChar == '/');
        Path path = raw("long-name-%FF-%FE-target.txt");
        String label = PathPresentation.name(path);
        int minimum = CellText.width("Overwrite: " + label + " ? (y/N)");
        String shortQuestion = ConfirmPromptText.fileQuestion("Overwrite: ", path, " ? (y/N)", minimum - 1);
        assertTrue(CellText.width(shortQuestion) > minimum - 1);
        String question = ConfirmPromptText.fileQuestion("Overwrite: ", path, " ? (y/N)", minimum);
        assertTrue(question.contains(label));
        assertEquals(minimum, CellText.width(question));
        String longNormal = ConfirmPromptText.fileQuestion("Overwrite: ", directory.resolve("a".repeat(180) + ".txt"),
            " ? (y/N)", 54);
        assertTrue(longNormal.contains("…"));
        assertTrue(CellText.width(longNormal) <= 54);
        assertFalse(longNormal.contains("file:"));
    }

    private Path raw(String name) {
        return Path.of(URI.create(directory.toUri().toASCIIString() + name));
    }

    private static void assertTokens(String text) {
        for (int index = text.indexOf('%'); index >= 0; index = text.indexOf('%', index + 3)) {
            assertTrue(index + 2 < text.length(), text);
            assertTrue(Character.digit(text.charAt(index + 1), 16) >= 0, text);
            assertTrue(Character.digit(text.charAt(index + 2), 16) >= 0, text);
        }
    }
}
