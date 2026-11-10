package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PalettePreferencesStoreTest {
    @TempDir Path root;
    private Properties defaults(Path path) throws Exception {
        AppSettingsStore.save(path, AppSettings.defaults());
        Properties values = new Properties();
        try (var input = Files.newInputStream(path)) { values.load(input); }
        return values;
    }
    private void write(Path path, Properties values) throws Exception {
        try (var output = Files.newOutputStream(path)) { values.store(output, "test preferences"); }
    }
    @Test void optionalLayoutUsesDefaultsAndAllFieldsRoundTrip() throws Exception {
        Path path = root.resolve("settings.properties"); var values = defaults(path);
        values.keySet().removeIf(key -> key.toString().startsWith("palettes.")); write(path, values);
        assertEquals(PalettePreferences.defaults(), AppSettingsStore.load(path).palettes());
        values.setProperty("palettes.width", "40"); values.setProperty("palettes.filesHeight", "12");
        values.setProperty("palettes.searchHeight", "24"); values.setProperty("palettes.filesExpanded", "false");
        values.setProperty("palettes.searchExpanded", "true"); values.setProperty("palettes.searchFirst", "true");
        values.setProperty("palettes.maximized", "search"); values.setProperty("palettes.visible", "true");
        values.setProperty("palettes.filesHints", "false"); values.setProperty("palettes.searchHints", "false"); write(path, values);
        var settings = AppSettingsStore.load(path);
        assertEquals(new PalettePreferences(40, 12, 24, false, true, true, PalettePreferences.Maximized.SEARCH, true, false, false), settings.palettes());
        AppSettingsStore.save(path, settings); assertEquals(settings, AppSettingsStore.load(path));
    }
    @Test void malformedAndUnknownLayoutValuesPreserveTheSettingsFile() throws Exception {
        for (var entry : Map.of("palettes.width", "15", "palettes.filesHeight", "0", "palettes.searchHeight", "01",
            "palettes.searchFirst", "yes", "palettes.maximized", "broken", "palettes.unknown", "true",
            "palettes.filesHints", "1", "palettes.searchHints", "TRUE").entrySet()) {
            Path path = root.resolve("settings.properties"); var values = defaults(path);
            values.setProperty(entry.getKey(), entry.getValue()); write(path, values); byte[] before = Files.readAllBytes(path);
            assertThrows(AppSettingsStore.UnsupportedSettingsException.class, () -> AppSettingsStore.load(path), entry.getKey());
            assertArrayEquals(before, Files.readAllBytes(path));
        }
    }
}
