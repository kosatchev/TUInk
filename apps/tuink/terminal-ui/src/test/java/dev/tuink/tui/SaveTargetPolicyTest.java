package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.editor.storage.DocumentEncodingException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SaveTargetPolicyTest {
    @Test
    void saveFailuresUseTypedCausesAndNeverTreatFilenamesAsReasons() {
        assertEquals("Save failed: permission denied",
            SaveTargetPolicy.saveErrorMessage(new AccessDeniedException("no space.txt")));
        for (String name : List.of("permission denied.txt", "no space.txt", "is a directory.txt")) {
            assertEquals("Save failed: file no longer exists",
                SaveTargetPolicy.saveErrorMessage(new NoSuchFileException(name)));
            var unspecified = new FileSystemException(name);
            assertEquals("Save failed: " + unspecified.getMessage(), SaveTargetPolicy.saveErrorMessage(unspecified));
        }
        assertEquals("Save failed: is a directory.txt",
            SaveTargetPolicy.saveErrorMessage(new NotDirectoryException("is a directory.txt")));
        assertEquals("Save failed: no space left on device",
            SaveTargetPolicy.saveErrorMessage(new FileSystemException("permission denied.txt", null, "No space left on device")));
        assertEquals("Save failed: target is a directory",
            SaveTargetPolicy.saveErrorMessage(new FileSystemException("no space.txt", null, "Is a directory")));
        assertEquals("Save failed: device unavailable",
            SaveTargetPolicy.saveErrorMessage(new FileSystemException("selected.txt", null, "device unavailable")));
    }

    @Test
    void genericFailuresAndExplicitEncodingAdviceKeepTheirSaveWording() {
        assertEquals("Save failed: Unknown I/O error", SaveTargetPolicy.saveErrorMessage(new IOException()));
        assertEquals("Save failed: write failed", SaveTargetPolicy.saveErrorMessage(new IOException("write failed")));
        assertEquals("Save failed: permission denied", SaveTargetPolicy.saveErrorMessage(new IOException("ACCESS DENIED")));
        assertEquals("Save failed: US-ASCII cannot encode U+1F600. Choose Save as UTF-8 to keep this text.",
            SaveTargetPolicy.saveErrorMessage(new DocumentEncodingException(StandardCharsets.US_ASCII,
                0x1F600, new UnmappableCharacterException(2))));
    }
}
