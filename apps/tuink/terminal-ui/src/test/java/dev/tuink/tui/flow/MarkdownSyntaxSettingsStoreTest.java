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

class MarkdownSyntaxSettingsStoreTest {
    private static final String KEY = "ui.markdownCodeHighlighting";
    @TempDir Path directory;

    @Test void completeFormatTwoProfilesWithoutTheOptionalPreferenceKeepAllValuesAndRemainWritable() throws Exception {
        Path file = directory.resolve("settings.properties");
        var expected = settings(false);
        AppSettingsStore.save(file, expected);
        String original = Files.readString(file);
        assertTrue(original.contains("format=2"));
        assertFalse(original.contains(KEY));

        var loaded = AppSettingsStore.loadSnapshot(file);
        assertEquals(expected, loaded.settings());
        assertFalse(loaded.settings().markdownCodeHighlighting());
        AppSettingsStore.saveIfUnchanged(loaded, loaded.settings());

        assertEquals(expected, AppSettingsStore.load(file));
        assertFalse(Files.readString(file).contains(KEY));
    }

    @Test void enablingPersistsAndDisablingAgainOmitsTheDefaultWithoutChangingOtherPreferences() throws Exception {
        Path file = directory.resolve("settings.properties");
        AppSettingsStore.save(file, settings(false));
        var disabled = AppSettingsStore.loadSnapshot(file);
        AppSettingsStore.saveIfUnchanged(disabled, settings(true));
        assertTrue(Files.readString(file).contains(KEY + "=true"));
        assertEquals(settings(true), AppSettingsStore.load(file));

        var enabled = AppSettingsStore.loadSnapshot(file);
        assertTrue(enabled.settings().markdownCodeHighlighting());
        AppSettingsStore.saveIfUnchanged(enabled, settings(false));
        assertEquals(settings(false), AppSettingsStore.load(file));
        assertFalse(Files.readString(file).contains(KEY));
    }

    @Test void anExplicitFalseValueIsAcceptedAndCanonicalSavingOmitsIt() throws Exception {
        Path file = directory.resolve("settings.properties");
        AppSettingsStore.save(file, settings(false));
        Files.writeString(file, Files.readString(file) + KEY + "=false\n");
        var loaded = AppSettingsStore.loadSnapshot(file);
        assertFalse(loaded.settings().markdownCodeHighlighting());
        AppSettingsStore.saveIfUnchanged(loaded, loaded.settings());
        assertFalse(Files.readString(file).contains(KEY));
    }

    @Test void malformedOptionalValuesAndUnknownKeysRemainStrictAndLeaveTheProfileUntouched() throws Exception {
        Path file = directory.resolve("settings.properties");
        AppSettingsStore.save(file, settings(false));
        String original = Files.readString(file);
        for (String property : new String[] {KEY + "=", KEY + "=yes", KEY + "=True", "ui.unknown=true"}) {
            String invalid = original + property + "\n";
            Files.writeString(file, invalid);
            assertThrows(IOException.class, () -> AppSettingsStore.loadSnapshot(file), property);
            assertEquals(invalid, Files.readString(file));
        }
        String missingRequired = original.replace("ui.wrapLines=true\n", "");
        Files.writeString(file, missingRequired + KEY + "=false\n");
        assertThrows(IOException.class, () -> AppSettingsStore.loadSnapshot(file));
    }

    private static AppSettings settings(boolean mdSyntax) {
        return new AppSettings(false, true, false, true, false, false, false, false,
            false, false, false, false, false, false, false, true, true, mdSyntax,
            GutterDiffMode.STABLE, 2, TabInsertMode.ALWAYS_SPACES_2, UiStyles.Theme.LIGHT);
    }
}
