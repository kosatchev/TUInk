package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.tui.style.UiStyles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileHistoryNotificationsSettingsStoreTest {
    private static final String KEY = "ui.fileHistoryNotifications";
    @TempDir Path directory;

    @Test
    void existingProfilesWithoutTheOptionalKeyKeepNotificationsEnabled() throws Exception {
        assertTrue(AppSettings.defaults().showFileHistoryNotifications());
        Path file = directory.resolve("settings.properties");
        var expected = settings(true);
        AppSettingsStore.save(file, expected);
        String existing = Files.readString(file).replace(KEY + "=true\n", "");
        assertFalse(existing.contains(KEY));
        Files.writeString(file, existing);

        var loaded = AppSettingsStore.loadSnapshot(file);
        assertEquals(expected, loaded.settings());
        assertEquals(existing, Files.readString(file), "Reading an older profile must not rewrite it");
    }

    @Test
    void disablingAndReenablingRoundTripWithoutChangingOtherPreferences() throws Exception {
        Path file = directory.resolve("settings.properties");
        AppSettingsStore.save(file, settings(true));
        AppSettingsStore.saveIfUnchanged(AppSettingsStore.loadSnapshot(file), settings(false));
        assertTrue(Files.readString(file).contains(KEY + "=false"));
        assertEquals(settings(false), AppSettingsStore.load(file));

        AppSettingsStore.saveIfUnchanged(AppSettingsStore.loadSnapshot(file), settings(true));
        assertEquals(settings(true), AppSettingsStore.load(file));
    }

    @Test
    void invalidOptionalBooleanIsRejectedWithoutRewritingTheProfile() throws Exception {
        Path file = directory.resolve("settings.properties");
        AppSettingsStore.save(file, settings(true));
        String existing = Files.readString(file).replace(KEY + "=true\n", "");
        for (String value : new String[] {"", "yes", "True", "1", "false "}) {
            String invalid = existing + KEY + "=" + value + "\n";
            Files.writeString(file, invalid);
            assertThrows(IOException.class, () -> AppSettingsStore.loadSnapshot(file), value);
            assertEquals(invalid, Files.readString(file));
        }
    }

    private static AppSettings settings(boolean notifications) {
        return new AppSettings(false, true, false, true, false, false, true, false,
            true, false, true, false, true, false, true, true, false, true,
            GutterDiffMode.STABLE, 2, TabInsertMode.ALWAYS_SPACES_2, UiStyles.Theme.MONOCHROME,
            true, true, notifications);
    }
}
