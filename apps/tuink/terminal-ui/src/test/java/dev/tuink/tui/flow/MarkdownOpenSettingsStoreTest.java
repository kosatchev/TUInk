package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownOpenSettingsStoreTest {
    @TempDir Path directory;

    @Test void roundTripsAllIndependentCombinationsAndRejectsIncompleteCurrentSchema() throws Exception {
        Path file = directory.resolve("settings.properties");
        var d = AppSettings.defaults();
        for (boolean both : new boolean[] {false, true}) for (boolean preview : new boolean[] {false, true}) {
            var settings = new AppSettings(d.showTabBar(), d.wrapLines(), d.wrapWords(), d.readingMode(),
                d.confirmOnClose(), d.mouseMode(), d.highlightActiveGutter(), d.smartHomeEnd(), d.autoIndent(),
                d.autoPairs(), d.autoList(), d.visibleWhitespace(), d.syntaxHighlighting(), d.rainbowBrackets(),
                d.autoSelectionHighlight(), both, preview, d.gutterDiffMode(), d.tabWidth(), d.tabInsertMode(), d.theme());
            AppSettingsStore.save(file, settings);
            assertEquals(settings, AppSettingsStore.load(file));
            String saved = Files.readString(file);
            assertTrue(saved.contains("format=2"));
            assertTrue(saved.contains("ui.markdownOpenBothTabs=" + both));
            assertTrue(saved.contains("ui.markdownOpenInPreview=" + preview));
            for (String property : new String[] {"ui.markdownOpenBothTabs=" + both, "ui.markdownOpenInPreview=" + preview}) {
                Files.writeString(file, saved.replace(property, ""));
                assertThrows(IOException.class, () -> AppSettingsStore.load(file));
            }
        }
    }

    @Test void formatOneIsPreservedAsIncompatibleInsteadOfSilentlyMigrated() throws Exception {
        Path file = directory.resolve("settings.properties");
        AppSettingsStore.save(file, AppSettings.defaults());
        String old = Files.readString(file).replace("format=2", "format=1");
        Files.writeString(file, old);
        assertThrows(IOException.class, () -> AppSettingsStore.load(file));
        assertEquals(old, Files.readString(file));
    }
}
