package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalEditingSettingsStoreTest {
    @TempDir Path directory;

    @Test void bothPreferencesRoundTripWithNondefaultValues() throws Exception {
        Path path = directory.resolve("settings.properties"); AppSettingsStore.save(path, AppSettings.defaults());
        String stored = Files.readString(path).replace("ui.autoReloadUnmodifiedFiles=true", "ui.autoReloadUnmodifiedFiles=false")
            .replace("ui.dragSelectedText=false", "ui.dragSelectedText=true");
        Files.writeString(path, stored); var settings = AppSettingsStore.load(path);
        assertFalse(settings.autoReloadUnmodifiedFiles()); assertTrue(settings.dragSelectedText());
        AppSettingsStore.save(path, settings); assertEquals(settings, AppSettingsStore.load(path));
    }

    @Test void absentOptionalKeysUseSafeDefaultsAndMalformedValuesDoNotRewritePreferences() throws Exception {
        Path path = directory.resolve("settings.properties"); AppSettingsStore.save(path, AppSettings.defaults());
        String previous = Files.readString(path).replaceAll("(?m)^ui\\.(?:autoReloadUnmodifiedFiles|dragSelectedText)=.*\\R", "");
        Files.writeString(path, previous); var loaded = AppSettingsStore.load(path);
        assertTrue(loaded.autoReloadUnmodifiedFiles()); assertFalse(loaded.dragSelectedText());
        for (String key : new String[] {"ui.autoReloadUnmodifiedFiles", "ui.dragSelectedText"}) {
            String malformed = previous + key + "=maybe\n"; Files.writeString(path, malformed);
            assertThrows(IOException.class, () -> AppSettingsStore.load(path)); assertEquals(malformed, Files.readString(path));
        }
    }
}
