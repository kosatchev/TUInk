package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileLogTest {
    @TempDir
    Path directory;

    @Test
    void preservesDocumentWhenUsedAsProfileLog() throws IOException {
        Path document = directory.resolve("notes.txt");
        Files.writeString(document, "Keep these notes.\n");

        assertThrows(IOException.class, () -> ProfileLog.open(document, document));

        assertEquals("Keep these notes.\n", Files.readString(document));
    }

    @Test
    void refusesToCreateLogAtNewDocumentPath() {
        Path document = directory.resolve("new.txt");

        assertThrows(IOException.class, () -> ProfileLog.open(document, document));

        assertFalse(Files.exists(document));
    }

    @Test
    void preservesExistingLog() throws IOException {
        Path log = directory.resolve("profile.log");
        Files.writeString(log, "previous profile\n");

        assertThrows(FileAlreadyExistsException.class, () -> ProfileLog.open(log, null));

        assertEquals("previous profile\n", Files.readString(log));
    }

    @Test
    void writesUtf8ToNewLog() throws IOException {
        Path log = directory.resolve("profile.log");
        Path document = directory.resolve("notes.txt");
        Files.writeString(document, "notes\n");

        try (var writer = ProfileLog.open(log, document)) {
            writer.print("профиль\n");
        }

        assertEquals("профиль\n", Files.readString(log));
        assertEquals("notes\n", Files.readString(document));
    }

    @Test
    void refusesSymlinkToExistingDocument() throws IOException {
        assumePosix();
        Path document = directory.resolve("notes.txt");
        Files.writeString(document, "notes\n");
        Path log = directory.resolve("profile.log");
        Files.createSymbolicLink(log, document.getFileName());

        assertThrows(FileAlreadyExistsException.class, () -> ProfileLog.open(log, document));

        assertTrue(Files.isSymbolicLink(log));
        assertEquals(document.getFileName(), Files.readSymbolicLink(log));
        assertEquals("notes\n", Files.readString(document));
    }

    @Test
    void refusesHardLinkToExistingDocument() throws IOException {
        assumePosix();
        Path document = directory.resolve("notes.txt");
        Files.writeString(document, "notes\n");
        Path log = directory.resolve("profile.log");
        Files.createLink(log, document);

        assertThrows(FileAlreadyExistsException.class, () -> ProfileLog.open(log, document));

        assertTrue(Files.isSameFile(log, document));
        assertEquals("notes\n", Files.readString(document));
    }

    @Test
    void refusesNewDocumentAliasThroughSymlinkedDirectory() throws IOException {
        assumePosix();
        Path alias = directory.resolve("alias");
        Files.createSymbolicLink(alias, directory);
        Path document = directory.resolve("new.txt");
        Path log = alias.resolve("new.txt");

        assertThrows(IOException.class, () -> ProfileLog.open(log, document));

        assertFalse(Files.exists(document));
    }

    private void assumePosix() throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
    }
}
