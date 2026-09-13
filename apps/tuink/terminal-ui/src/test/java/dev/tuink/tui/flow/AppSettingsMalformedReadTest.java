package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppSettingsMalformedReadTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsInvalidUnicodeEscapeAsAnIoErrorAndPreservesTheCurrentFormatFile() throws IOException {
        assertMalformedSettingsPreserved("\\u12xz");
    }

    @Test
    void rejectsTruncatedUnicodeEscapeAsAnIoErrorAndPreservesTheCurrentFormatFile() throws IOException {
        assertMalformedSettingsPreserved("\\u123");
    }

    private void assertMalformedSettingsPreserved(String malformedValue) throws IOException {
        Path path = tempDir.resolve("tuink.properties");
        AppSettingsStore.save(path, AppSettings.defaults());
        String current = Files.readString(path, StandardCharsets.ISO_8859_1);
        assertTrue(current.contains("ui.theme=cyan"));
        byte[] malformedBytes = current.replace("ui.theme=cyan", "ui.theme=" + malformedValue)
            .getBytes(StandardCharsets.ISO_8859_1);
        Files.write(path, malformedBytes);

        IOException failure = assertThrows(IOException.class, () -> AppSettingsStore.load(path));

        assertTrue(failure.getMessage().contains(path.toString()));
        assertTrue(failure.getMessage().contains("Malformed settings file"));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertArrayEquals(malformedBytes, Files.readAllBytes(path));
        assertThrows(IOException.class, () -> AppSettingsStore.load(path));
        assertArrayEquals(malformedBytes, Files.readAllBytes(path));
    }
}
