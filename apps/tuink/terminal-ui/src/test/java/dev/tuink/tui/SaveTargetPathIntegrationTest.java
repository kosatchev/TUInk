package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.input.TextInput;
import dev.tuink.core.EditorClipboard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveTargetPathIntegrationTest {
    @TempDir Path directory;

    @BeforeEach
    void requirePosixSymlinks() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
    }

    @Test
    void overwriteConfirmationAndSaveAddressTheSameFilesystemTarget() throws Exception {
        Files.createDirectories(directory.resolve("real/deep"));
        Files.createSymbolicLink(directory.resolve("link"), Path.of("real/deep"));
        Path actual = Files.writeString(directory.resolve("real/target.txt"), "requested original");
        Path unrelated = Files.writeString(directory.resolve("target.txt"), "unrelated original");
        Path requested = directory.resolve("link/../target.txt");
        try (EditorWindowFixture window = new EditorWindowFixture(directory.resolve("window"), false, List.of("draft"))) {
            SaveWorkflowIntegrationTest.submitFilename(window, requested);
            assertEquals("requested original", Files.readString(actual));
            assertEquals("unrelated original", Files.readString(unrelated));
            assertTrue(window.screenRow(window.height - 2).contains("Overwrite"));

            window.event(new TextInput("y"));

            assertEquals("draft", Files.readString(actual));
            assertEquals("unrelated original", Files.readString(unrelated));
            assertEquals(actual, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void sameTargetDetectionUsesFilesystemIdentityBeforeDotNormalization() throws Exception {
        Files.createDirectories(directory.resolve("real/deep"));
        Files.createSymbolicLink(directory.resolve("link"), Path.of("real/deep"));
        Path actual = Files.writeString(directory.resolve("real/target.txt"), "requested");
        Path unrelated = Files.writeString(directory.resolve("target.txt"), "unrelated");
        Path requested = directory.resolve("link/../target.txt");
        var tab = TuiDocumentSession.create(new EditorClipboard()).view();
        tab.engine().openExisting(JvmDocumentStorage.resource(actual));

        assertTrue(tab.engine().isCurrentSaveTarget(JvmDocumentStorage.resource(requested)));
        assertFalse(tab.engine().isCurrentSaveTarget(JvmDocumentStorage.resource(unrelated)));
        assertNull(SaveTargetPolicy.validateSaveTarget(requested));
    }

    @Test
    void validationRejectsDirectoryEndpointsAndBrokenParentLinks() throws Exception {
        Path broken = Files.createSymbolicLink(directory.resolve("broken"), Path.of("missing"));
        for (Path invalid : List.of(directory.resolve("."), directory.resolve(".."), broken.resolve("../new.txt"))) {
            assertNotNull(SaveTargetPolicy.validateSaveTarget(invalid), invalid.toString());
        }
        assertFalse(Files.exists(directory.resolve("new.txt")));
    }

    @Test
    void readOnlyCheckUsesTheActualTargetBehindSymlinkAndParentSegments() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Files.createDirectories(directory.resolve("real/deep"));
        Files.createSymbolicLink(directory.resolve("link"), Path.of("real/deep"));
        Path actual = Files.writeString(directory.resolve("real/target.txt"), "read only");
        Files.writeString(directory.resolve("target.txt"), "writable unrelated");
        Files.setPosixFilePermissions(actual, PosixFilePermissions.fromString("r--r--r--"));
        assumeTrue(!Files.isWritable(actual), "The test requires an unprivileged user");

        assertTrue(SaveTargetPolicy.isReadOnlyFile(directory.resolve("link/../target.txt")));
        assertFalse(SaveTargetPolicy.isReadOnlyFile(directory.resolve("target.txt")));
    }
}
