package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.project.search.SearchPreferences;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchPreferencesStoreTest {
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
    @Test void searchPreferencesRoundTripThroughTheExistingSettingsWriter() throws Exception {
        Path source = root.resolve("source.properties"); var values = defaults(source);
        values.setProperty("search.automatic", "false"); values.setProperty("search.respectIgnore", "false");
        values.setProperty("search.hidden", "false"); values.setProperty("search.exclusions", "build/**, *.log");
        values.setProperty("search.encoding", "cp866"); values.setProperty("search.maximumFileMiB", "4");
        values.setProperty("search.maximumMatches", "250"); write(source, values);
        var settings = AppSettingsStore.load(source);
        assertEquals(new SearchPreferences(false, false, false, "build/**, *.log", DocumentOpenMode.CP866, 4, 250), settings.projectSearch());
        Path target = root.resolve("target.properties"); AppSettingsStore.save(target, settings);
        assertEquals(settings, AppSettingsStore.load(target));
    }
    @Test void omittedOptionalSearchPreferencesUseDocumentedDefaults() throws Exception {
        Path path = root.resolve("settings.properties"); var values = defaults(path);
        values.keySet().removeIf(key -> key.toString().startsWith("search.")); write(path, values);
        assertEquals(SearchPreferences.defaults(), AppSettingsStore.load(path).projectSearch());
    }
    @Test void invalidLimitsAndUnknownSearchKeysPreserveTheSettingsFile() throws Exception {
        for (var entry : java.util.Map.of("search.maximumFileMiB", "65", "search.maximumMatches", "0",
            "search.encoding", "automatic", "search.unknown", "true", "search.maximumFileMiB ", "4").entrySet()) {
            Path path = root.resolve("settings.properties"); var values = defaults(path);
            values.setProperty(entry.getKey(), entry.getValue()); write(path, values);
            byte[] before = Files.readAllBytes(path);
            assertThrows(AppSettingsStore.UnsupportedSettingsException.class, () -> AppSettingsStore.load(path), entry.getKey());
            assertArrayEquals(before, Files.readAllBytes(path));
        }
    }
}
