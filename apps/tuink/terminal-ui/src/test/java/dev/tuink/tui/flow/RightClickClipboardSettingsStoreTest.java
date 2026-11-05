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

class RightClickClipboardSettingsStoreTest {
    private static final String KEY = "ui.rightClickClipboard";
    @TempDir Path directory;

    @Test void existingFormatTwoProfilesDefaultToDisabledAndKeepEveryOtherPreference() throws Exception {
        assertFalse(AppSettings.defaults().rightClickClipboard());
        Path file = directory.resolve("settings.properties");
        var expected = settings(false);
        AppSettingsStore.save(file, expected);
        String original = Files.readString(file);
        assertTrue(original.contains("format=2"));
        assertFalse(original.contains(KEY));
        var loaded = AppSettingsStore.loadSnapshot(file);
        assertEquals(expected, loaded.settings());
        AppSettingsStore.saveIfUnchanged(loaded, loaded.settings());
        assertEquals(expected, AppSettingsStore.load(file));
        assertFalse(Files.readString(file).contains(KEY));
    }

    @Test void enablingAndDisablingRoundTripIndependentlyOfMouseAndMarkdownOptions() throws Exception {
        Path file = directory.resolve("settings.properties");
        AppSettingsStore.save(file, settings(false));
        AppSettingsStore.saveIfUnchanged(AppSettingsStore.loadSnapshot(file), settings(true));
        assertTrue(Files.readString(file).contains(KEY + "=true"));
        assertEquals(settings(true), AppSettingsStore.load(file));
        assertFalse(AppSettingsStore.load(file).mouseMode());
        assertTrue(AppSettingsStore.load(file).markdownCodeHighlighting());
        AppSettingsStore.saveIfUnchanged(AppSettingsStore.loadSnapshot(file), settings(false));
        assertEquals(settings(false), AppSettingsStore.load(file));
        assertFalse(Files.readString(file).contains(KEY));
    }

    @Test void explicitFalseIsAcceptedAndCanonicalSaveOmitsTheDefault() throws Exception {
        Path file = directory.resolve("settings.properties");
        AppSettingsStore.save(file, settings(false));
        Files.writeString(file, Files.readString(file) + KEY + "=false\n");
        var loaded = AppSettingsStore.loadSnapshot(file);
        assertEquals(settings(false), loaded.settings());
        AppSettingsStore.saveIfUnchanged(loaded, loaded.settings());
        assertFalse(Files.readString(file).contains(KEY));
    }

    @Test void optionalValueValidationRequiredFieldsAndUnknownKeysStayStrictWithoutRewriting() throws Exception {
        Path file = directory.resolve("settings.properties");
        AppSettingsStore.save(file, settings(false));
        String original = Files.readString(file);
        for (String property : new String[] {KEY + "=", KEY + "=yes", KEY + "=True", KEY + "=1",
            KEY + "=true ", "ui.rightclickClipboard=true"}) {
            String invalid = original + property + "\n";
            Files.writeString(file, invalid);
            assertThrows(IOException.class, () -> AppSettingsStore.loadSnapshot(file), property);
            assertEquals(invalid, Files.readString(file));
        }
        String missing = original.replace("ui.mouseMode=false\n", "") + KEY + "=true\n";
        Files.writeString(file, missing);
        assertThrows(IOException.class, () -> AppSettingsStore.loadSnapshot(file));
        assertEquals(missing, Files.readString(file));
    }

    private static AppSettings settings(boolean rightClickClipboard) {
        return new AppSettings(false, true, false, true, false, false, true, false,
            true, false, true, false, true, false, true, true, false, true,
            GutterDiffMode.STABLE, 2, TabInsertMode.ALWAYS_SPACES_2, UiStyles.Theme.LIGHT, rightClickClipboard, false);
    }
}
