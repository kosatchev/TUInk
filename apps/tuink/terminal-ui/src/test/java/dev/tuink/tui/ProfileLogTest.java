package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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

    @Test
    void refusesDanglingDocumentSymlinkBeforeCreatingItsTargetAsTheLog() throws IOException {
        assumePosix();
        Path document = directory.resolve("notes.txt");
        Path log = directory.resolve("future.log");
        Files.createSymbolicLink(document, log.getFileName());

        assertThrows(IOException.class, () -> ProfileLog.open(log, document));

        assertFalse(Files.exists(log, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isSymbolicLink(document));
        assertEquals(log.getFileName(), Files.readSymbolicLink(document));
    }

    @Test
    void refusesDanglingDocumentAliasThroughRelativeSymlinkChain() throws IOException {
        assumePosix();
        Path document = directory.resolve("docs/notes.txt");
        Files.createDirectories(document.getParent());
        Path hop = directory.resolve("hop");
        Path log = directory.resolve("future.log");
        Files.createSymbolicLink(document, Path.of("../hop"));
        Files.createSymbolicLink(hop, log);

        assertThrows(IOException.class, () -> ProfileLog.open(log, document));

        assertFalse(Files.exists(log, LinkOption.NOFOLLOW_LINKS));
        assertEquals(Path.of("../hop"), Files.readSymbolicLink(document));
        assertEquals(log, Files.readSymbolicLink(hop));
    }

    @Test
    void resolvesAParentSymlinkBeforeDotDotInsideTheDocumentLinkTarget() throws IOException {
        assumePosix();
        Path actualLog = pathThroughParentLink("future.log");
        Path document = directory.resolve("notes.txt");
        Path relativeTarget = Path.of("loglink/../future.log");
        Files.createSymbolicLink(document, relativeTarget);

        assertThrows(IOException.class,
            () -> ProfileLog.open(directory.resolve("real/future.log"), document));
        assertThrows(IOException.class, () -> ProfileLog.open(actualLog, document));

        assertFalse(Files.exists(directory.resolve("real/future.log"), LinkOption.NOFOLLOW_LINKS));
        assertEquals(relativeTarget, Files.readSymbolicLink(document));
        // Lexical normalization would point at this distinct file and reject it incorrectly.
        Path distinctLog = directory.resolve("future.log");
        try (var writer = ProfileLog.open(distinctLog, document)) {
            writer.println("profile");
        }
        assertEquals("profile" + System.lineSeparator(), Files.readString(distinctLog));
        assertFalse(Files.exists(document));
    }

    @Test
    void refusesDanglingDocumentCollisionWhenItsOwnParentUsesSymlinkAndDotDot() throws IOException {
        assumePosix();
        Path documentAlias = pathThroughParentLink("notes.txt");
        Path document = directory.resolve("real/notes.txt");
        Path log = directory.resolve("real/future.log");
        Files.createSymbolicLink(document, Path.of("future.log"));

        assertThrows(IOException.class, () -> ProfileLog.open(log, documentAlias));

        assertFalse(Files.exists(log, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isSymbolicLink(document));
    }

    @Test
    void createsDistinctLogWithoutMaterializingTheDanglingDocumentTarget() throws IOException {
        assumePosix();
        Path document = directory.resolve("notes.txt");
        Path documentTarget = directory.resolve("future-notes.txt");
        Files.createSymbolicLink(document, documentTarget.getFileName());
        Path log = directory.resolve("profile.log");

        try (var writer = ProfileLog.open(log, document)) {
            writer.println("profile");
        }

        assertEquals("profile" + System.lineSeparator(), Files.readString(log));
        assertFalse(Files.exists(documentTarget, LinkOption.NOFOLLOW_LINKS));
        assertEquals(documentTarget.getFileName(), Files.readSymbolicLink(document));
    }

    @Test
    void refusesDocumentSymlinkCycleBeforeCreatingTheLog() throws IOException {
        assumePosix();
        Path document = directory.resolve("notes.txt");
        Path hop = directory.resolve("hop");
        Path log = directory.resolve("profile.log");
        Files.createSymbolicLink(document, hop.getFileName());
        Files.createSymbolicLink(hop, document.getFileName());

        assertThrows(IOException.class, () -> ProfileLog.open(log, document));

        assertFalse(Files.exists(log, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isSymbolicLink(document));
        assertTrue(Files.isSymbolicLink(hop));
    }

    @Test
    void createsLogAfterResolvingTheSymlinkBeforeTheParentSegment() throws IOException {
        assumePosix();
        Path requested = pathThroughParentLink("fresh.log");

        try (var writer = ProfileLog.open(requested, null)) {
            writer.println("profile data");
        }

        assertEquals("profile data" + System.lineSeparator(),
            Files.readString(directory.resolve("real/fresh.log")));
        assertFalse(Files.exists(directory.resolve("fresh.log")));
    }

    @Test
    void refusesNewDocumentCollisionThroughSymlinkAndParentSegmentsInEitherArgument() throws IOException {
        assumePosix();
        Path alias = pathThroughParentLink("fresh.log");
        Path actual = directory.resolve("real/fresh.log");

        assertThrows(IOException.class, () -> ProfileLog.open(alias, actual));
        assertThrows(IOException.class, () -> ProfileLog.open(actual, alias));

        assertFalse(Files.exists(actual));
        assertFalse(Files.exists(directory.resolve("fresh.log")));
    }

    @Test
    void preservesExistingResolvedLogWhenTheRequestedPathUsesSymlinkAndParentSegments() throws IOException {
        assumePosix();
        Path requested = pathThroughParentLink("fresh.log");
        Path actual = directory.resolve("real/fresh.log");
        Files.writeString(actual, "previous profile\n");

        assertThrows(FileAlreadyExistsException.class, () -> ProfileLog.open(requested, null));

        assertEquals("previous profile\n", Files.readString(actual));
        assertFalse(Files.exists(directory.resolve("fresh.log")));
    }

    private Path pathThroughParentLink(String fileName) throws IOException {
        Files.createDirectories(directory.resolve("real/deep"));
        Path link = directory.resolve("loglink");
        Files.createSymbolicLink(link, Path.of("real/deep"));
        return link.resolve("../" + fileName);
    }

    private void assumePosix() throws IOException {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
    }
}
