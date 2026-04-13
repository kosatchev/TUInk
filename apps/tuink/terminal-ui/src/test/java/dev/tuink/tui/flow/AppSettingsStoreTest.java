package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppSettingsStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void currentFormatRoundTrips() throws IOException {
        Path path = tempDir.resolve("tuink.properties");
        AppSettings expected = AppSettings.defaults();

        AppSettingsStore.save(path, expected);

        assertEquals(expected, AppSettingsStore.load(path));
        String stored = Files.readString(path);
        org.junit.jupiter.api.Assertions.assertTrue(stored.contains("format=1"));
    }

    @Test
    void missingFileUsesDefaults() throws IOException {
        assertEquals(AppSettings.defaults(), AppSettingsStore.load(tempDir.resolve("missing.properties")));
    }

    @Test
    void rejectsMarkerlessFileWithoutChangingIt() throws IOException {
        Path path = tempDir.resolve("tuink.properties");
        String original = "ui.theme=cyan\n";
        Files.writeString(path, original);

        assertThrows(IOException.class, () -> AppSettingsStore.load(path));
        assertEquals(original, Files.readString(path));
    }

    @Test
    void rejectsUnknownFormatAndHistoricalAlias() throws IOException {
        Path path = tempDir.resolve("tuink.properties");
        AppSettingsStore.save(path, AppSettings.defaults());
        String current = Files.readString(path);

        Files.writeString(path, current.replace("format=1", "format=2"));
        assertThrows(IOException.class, () -> AppSettingsStore.load(path));

        Files.writeString(path, current.replace("ui.tabInsertMode=auto", "ui.tabInsertMode=tab"));
        assertThrows(IOException.class, () -> AppSettingsStore.load(path));
    }

    @Test
    void rejectsMissingInvalidAndUnknownProperties() throws IOException {
        Path path = tempDir.resolve("tuink.properties");
        AppSettingsStore.save(path, AppSettings.defaults());
        String current = Files.readString(path);

        Files.writeString(path, current.replace("ui.wrapLines=false\n", ""));
        assertThrows(IOException.class, () -> AppSettingsStore.load(path));

        Files.writeString(path, current.replace("ui.wrapLines=false", "ui.wrapLines=maybe"));
        assertThrows(IOException.class, () -> AppSettingsStore.load(path));

        Files.writeString(path, current + "ui.unknown=true\n");
        assertThrows(IOException.class, () -> AppSettingsStore.load(path));
    }
}
