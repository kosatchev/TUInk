package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandLinePathsTest {
    @Test
    void rawUnixBytesAndLiteralReplacementCharactersKeepSeparateFileIdentities(@TempDir Path directory) throws Exception {
        assumeTrue(File.separatorChar == '/');
        String parent = directory.toUri().toASCIIString();
        Path raw = Path.of(URI.create(parent + "legacy-%FF.txt"));
        Path unicode = directory.resolve("legacy-�.txt");
        Files.writeString(raw, "raw");
        Files.writeString(unicode, "unicode");

        assertEquals(raw, CommandLinePaths.fileUri(parent + "legacy-%FF.txt"));
        assertEquals(unicode, CommandLinePaths.fileUri(unicode.toUri().toASCIIString()));
        assertNotEquals(raw, unicode);
        assertEquals("raw", Files.readString(CommandLinePaths.fileUri(parent + "legacy-%ff.txt")));
        assertEquals("unicode", Files.readString(CommandLinePaths.fileUri(unicode.toUri().toASCIIString())));
    }

    @Test
    void escapedAsciiAndDirectoryTrailingSlashesDoNotChangeTheNativePath(@TempDir Path directory) {
        String parent = directory.toUri().toASCIIString();
        assertEquals(directory.resolve("a.txt"), CommandLinePaths.fileUri(parent + "%61.txt"));
        assertEquals(directory, CommandLinePaths.fileUri(parent));
        assertEquals(directory, CommandLinePaths.fileUri(parent.substring(0, parent.length() - 1)));
    }
}
