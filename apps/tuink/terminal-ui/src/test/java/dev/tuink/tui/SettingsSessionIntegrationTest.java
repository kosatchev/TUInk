package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.style.UiStyles;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsSessionIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void launchOverridesChangeTheSessionWithoutChangingSavedPreferences() throws Exception {
        try (EditorWindowFixture window = overriddenWindow()) {
            assertTrue(window.ui.readingModeSetting);
            assertFalse(window.ui.mouseModeSetting);
            assertEquals(UiStyles.Theme.LIGHT, window.host.theme);
            assertEquals(AppSettings.defaults(), preferences(window));
        }
    }

    @Test
    void anUnrelatedMenuChangeDoesNotPersistLaunchOverrides() throws Exception {
        try (EditorWindowFixture window = overriddenWindow()) {
            window.command(TuinkCommand.VIEW_TOGGLE_WHITESPACE);

            AppSettings saved = preferences(window);
            assertFalse(saved.visibleWhitespace());
            assertFalse(saved.readingMode());
            assertTrue(saved.mouseMode());
            assertEquals(UiStyles.Theme.CYAN, saved.theme());
            assertTrue(window.ui.readingModeSetting);
            assertFalse(window.ui.mouseModeSetting);
            assertEquals(UiStyles.Theme.LIGHT, window.host.theme);
        }
    }

    @Test
    void twoReadingModeChangesPersistAnExplicitChoiceMatchingTheLaunchOverride() throws Exception {
        try (EditorWindowFixture window = overriddenWindow()) {
            window.command(TuinkCommand.SETTINGS_TOGGLE_READING_MODE);
            window.command(TuinkCommand.SETTINGS_TOGGLE_READING_MODE);

            AppSettings saved = preferences(window);
            assertTrue(saved.readingMode());
            assertTrue(saved.mouseMode());
            assertEquals(UiStyles.Theme.CYAN, saved.theme());
        }
    }

    @Test
    void twoMouseChangesPersistAnExplicitChoiceMatchingTheLaunchOverride() throws Exception {
        try (EditorWindowFixture window = overriddenWindow()) {
            window.command(TuinkCommand.SETTINGS_TOGGLE_MOUSE);
            window.command(TuinkCommand.SETTINGS_TOGGLE_MOUSE);

            AppSettings saved = preferences(window);
            assertFalse(saved.mouseMode());
            assertFalse(saved.readingMode());
            assertEquals(UiStyles.Theme.CYAN, saved.theme());
        }
    }

    @Test
    void anUnavailableMouseActionDoesNotBecomeASavedChoice() throws Exception {
        try (EditorWindowFixture window = overriddenWindow()) {
            window.ui.mouseModeAvailable = false;
            window.command(TuinkCommand.SETTINGS_TOGGLE_MOUSE);

            assertFalse(window.ui.mouseModeSetting);
            assertTrue(window.notification.message.contains("unavailable"));
            assertEquals(AppSettings.defaults(), preferences(window));
        }
    }

    @Test
    void aFullThemeCyclePersistsTheExplicitThemeEvenWhenItMatchesTheLaunchOverride() throws Exception {
        try (EditorWindowFixture window = overriddenWindow()) {
            for (int step = 0; step < 3; step++) {
                window.command(TuinkCommand.VIEW_CYCLE_THEME);
            }

            AppSettings saved = preferences(window);
            assertEquals(UiStyles.Theme.LIGHT, saved.theme());
            assertFalse(saved.readingMode());
            assertTrue(saved.mouseMode());
        }
    }

    @Test
    void ordinaryChangesWithoutOverridesRemainPersistent() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("keep"))) {
            window.command(TuinkCommand.SETTINGS_TOGGLE_READING_MODE);
            window.command(TuinkCommand.SETTINGS_TOGGLE_MOUSE);
            window.command(TuinkCommand.VIEW_CYCLE_THEME);

            AppSettings saved = preferences(window);
            assertTrue(saved.readingMode());
            assertFalse(saved.mouseMode());
            assertEquals(UiStyles.Theme.BLUE, saved.theme());
        }
    }

    private EditorWindowFixture overriddenWindow() throws Exception {
        AppSettingsSession session = new AppSettingsSession(AppSettings.defaults(),
            TuiCliOptions.parse(new String[]{"--read", "--no-mouse", "--theme=light"}));
        return new EditorWindowFixture(directory, List.of("keep"), session);
    }

    private static AppSettings preferences(EditorWindowFixture window) {
        return window.settingsSession.preferences(window.ui.settingsSnapshot(window.host.theme));
    }
}
