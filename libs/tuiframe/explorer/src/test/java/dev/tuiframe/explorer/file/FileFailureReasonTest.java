package dev.tuiframe.explorer.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class FileFailureReasonTest {
    @Test
    void typedFailuresTakePriorityOverWordsInTheirFilenames() {
        var denied = FileFailureReason.describe(new AccessDeniedException("no space left on device.txt"));
        assertEquals(FileFailureReason.Kind.ACCESS_DENIED, denied.kind());
        assertEquals("permission denied", denied.detail());
        assertTrue(denied.canAttachPath());
        for (String filename : List.of("permission denied.txt", "no space.txt", "is a directory.txt")) {
            var missing = FileFailureReason.describe(new NoSuchFileException(filename));
            assertEquals(FileFailureReason.Kind.MISSING, missing.kind());
            assertEquals("file no longer exists", missing.detail());
            assertTrue(missing.canAttachPath());
            var unspecified = FileFailureReason.describe(new FileSystemException(filename));
            assertEquals(FileFailureReason.Kind.OTHER, unspecified.kind());
            assertEquals(filename, unspecified.detail());
            assertFalse(unspecified.canAttachPath());
        }
        assertEquals(FileFailureReason.Kind.OTHER,
            FileFailureReason.describe(new NotDirectoryException("is a directory.txt")).kind());
    }

    @Test
    void filesystemReasonsStaySeparateFromTheirFileAddresses() {
        var described = FileFailureReason.describe(
            new FileSystemException("permission denied.txt", "is a directory.txt", "No space left on device"));
        assertEquals(FileFailureReason.Kind.NO_SPACE, described.kind());
        assertEquals("No space left on device", described.detail());
        assertTrue(described.canAttachPath());
        var unknown = FileFailureReason.describe(new FileSystemException("selected.txt", null, "device unavailable"));
        assertEquals(FileFailureReason.Kind.OTHER, unknown.kind());
        assertEquals("device unavailable", unknown.detail());
        assertTrue(unknown.canAttachPath());
        var blank = FileFailureReason.describe(new FileSystemException("permission denied.txt", null, " "));
        assertEquals(FileFailureReason.Kind.OTHER, blank.kind());
        assertFalse(blank.canAttachPath());
    }

    @Test
    void legacyGenericReasonsUseExactLocaleIndependentMatchesWithoutUnwrappingCauses() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(FileFailureReason.Kind.ACCESS_DENIED,
                FileFailureReason.describe(new IOException(" PERMISSION DENIED ")).kind());
            assertEquals(FileFailureReason.Kind.NO_SPACE,
                FileFailureReason.describe(new IOException("Not enough space")).kind());
            assertEquals(FileFailureReason.Kind.DIRECTORY,
                FileFailureReason.describe(new IOException("IS A DIRECTORY")).kind());
            for (String message : List.of("permission denied.txt", "no space/notes.txt", "read failed")) {
                var described = FileFailureReason.describe(new IOException(message));
                assertEquals(FileFailureReason.Kind.OTHER, described.kind());
                assertEquals(message, described.detail());
                assertFalse(described.canAttachPath());
            }
            var wrapped = FileFailureReason.describe(new IOException("outer failure", new AccessDeniedException("file")));
            assertEquals(FileFailureReason.Kind.OTHER, wrapped.kind());
            assertEquals("outer failure", wrapped.detail());
            assertNull(FileFailureReason.describe(new IOException()).detail());
            assertNull(FileFailureReason.describe(new IllegalStateException()).detail());
        } finally {
            Locale.setDefault(previous);
        }
    }
}
