package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentPathSettingsStoreTest {
    @TempDir Path directory;

    @Test void oldProfilesEnableThePathWithoutRewritingAndInvalidValuesAreRejected() throws Exception {
        Path path = directory.resolve("settings.properties");
        AppSettingsStore.save(path, AppSettings.defaults());
        String old = Files.readString(path).replace("ui.showDocumentPath=true\n", "");
        Files.writeString(path, old);
        assertTrue(AppSettingsStore.load(path).showDocumentPath());
        assertEquals(old, Files.readString(path));
        for (String value : new String[] {"", "True", "yes", "1", "false "}) {
            Files.writeString(path, old + "ui.showDocumentPath=" + value + "\n");
            assertThrows(IOException.class, () -> AppSettingsStore.load(path));
        }
        Files.writeString(path, old + "ui.showDocumentPath=false\n");
        var disabled = AppSettingsStore.load(path);
        assertFalse(disabled.showDocumentPath());
        AppSettingsStore.saveIfUnchanged(AppSettingsStore.loadSnapshot(path), disabled);
        assertEquals(disabled, AppSettingsStore.load(path));
    }
}
