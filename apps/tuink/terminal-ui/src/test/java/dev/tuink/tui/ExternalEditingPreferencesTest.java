package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalEditingPreferencesTest {
    @TempDir Path directory;

    @Test void menuTogglesPersistAcrossSessionsAndCliOverridesPreserveBothPreferences() throws Exception {
        try (var window = new EditorWindowFixture(directory, false)) {
            assertTrue(window.ui.autoReloadUnmodifiedFilesSetting); assertFalse(window.ui.dragSelectedTextSetting);
            window.command(TuinkCommand.SETTINGS_TOGGLE_AUTO_RELOAD_UNMODIFIED);
            window.command(TuinkCommand.SETTINGS_TOGGLE_DRAG_SELECTED_TEXT);
            var expected = window.ui.settingsSnapshot(window.settingsSession.initialSettings().theme());
            var loaded = expected;
            var session = new AppSettingsSession(loaded, TuiCliOptions.parse(new String[] {"--read"}));
            assertFalse(session.initialSettings().autoReloadUnmodifiedFiles()); assertTrue(session.initialSettings().dragSelectedText());
            var ui = UiSessionState.from(session.initialSettings());
            assertFalse(ui.autoReloadUnmodifiedFilesSetting); assertTrue(ui.dragSelectedTextSetting);
            assertFalse(session.preferences(ui.settingsSnapshot(loaded.theme())).autoReloadUnmodifiedFiles());
            assertTrue(session.preferences(ui.settingsSnapshot(loaded.theme())).dragSelectedText());
        }
    }

}
