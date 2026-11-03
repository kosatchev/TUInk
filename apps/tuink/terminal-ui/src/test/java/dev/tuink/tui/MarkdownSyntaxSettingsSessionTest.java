package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.style.UiStyles;
import org.junit.jupiter.api.Test;

class MarkdownSyntaxSettingsSessionTest {
    @Test void markdownCodeSyntaxDefaultsToDisabledAndTogglesIndependentlyOfGlobalSyntaxAndOpeningPreferences() {
        var ui = UiSessionState.from(AppSettings.defaults());
        assertFalse(ui.markdownCodeHighlightingSetting);
        var before = ui.settingsSnapshot(UiStyles.Theme.CYAN);

        var on = InputActionDispatcher.dispatchUi(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX,
            ui, TestSurfaces.source(ui), null, ignored -> {}, () -> {});
        assertTrue(on.handled());
        assertTrue(on.statusMessage().contains("enabled"));
        assertTrue(ui.markdownCodeHighlightingSetting);
        assertEquals(before.syntaxHighlighting(), ui.syntaxHighlightingSetting);
        assertEquals(before.markdownOpenBothTabs(), ui.markdownOpenBothTabsSetting);
        assertEquals(before.markdownOpenInPreview(), ui.markdownOpenInPreviewSetting);

        var off = InputActionDispatcher.dispatchUi(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_CODE_SYNTAX,
            ui, TestSurfaces.source(ui), null, ignored -> {}, () -> {});
        assertTrue(off.handled());
        assertTrue(off.statusMessage().contains("disabled"));
        assertEquals(before, ui.settingsSnapshot(UiStyles.Theme.CYAN));
    }

    @Test void runtimeSnapshotsAndCliOverridesPreserveTheExplicitMarkdownChoice() {
        var ui = UiSessionState.from(AppSettings.defaults());
        ui.markdownCodeHighlightingSetting = true;
        var saved = ui.settingsSnapshot(UiStyles.Theme.CYAN);
        var session = new AppSettingsSession(saved,
            TuiCliOptions.parse(new String[] {"--read", "--no-mouse", "--theme=light"}));
        assertTrue(session.initialSettings().markdownCodeHighlighting());
        var runtime = UiSessionState.from(session.initialSettings());
        assertTrue(runtime.markdownCodeHighlightingSetting);
        var preferences = session.preferences(runtime.settingsSnapshot(UiStyles.Theme.LIGHT));
        assertEquals(saved, preferences, "Launch overrides must not reset the Markdown preference");
    }
}
