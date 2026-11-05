package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.style.UiStyles;
import org.junit.jupiter.api.Test;

class MarkdownVisualCopySettingsSessionTest {
    @Test void theToggleIsIndependentOfMouseAvailabilityReadingAndPresentationKind() {
        for (var kind : ActiveSurface.Kind.values()) {
            var ui = UiSessionState.from(AppSettings.defaults());
            ui.rightClickClipboardSetting = true;
            var before = ui.settingsSnapshot(UiStyles.Theme.CYAN);
            assertFalse(ui.markdownVisualCopySetting);
            var surface = TestSurfaces.source(ui).withKind(kind).withRuntime(false, true);
            var enabled = InputActionDispatcher.dispatchUi(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_VISUAL_COPY,
                ui, surface, null, ignored -> fail("This preference must not change terminal tracking"), () -> fail());
            assertTrue(enabled.handled());
            assertTrue(enabled.statusMessage().contains("enabled"));
            assertTrue(ui.markdownVisualCopySetting);
            assertEquals(before.mouseMode(), ui.mouseModeSetting);
            assertEquals(before.rightClickClipboard(), ui.rightClickClipboardSetting);
            assertEquals(before.readingMode(), ui.readingModeSetting);
            var disabled = InputActionDispatcher.dispatchUi(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_VISUAL_COPY,
                ui, surface, null, ignored -> fail(), () -> fail());
            assertTrue(disabled.handled());
            assertTrue(disabled.statusMessage().contains("disabled"));
            assertEquals(before, ui.settingsSnapshot(UiStyles.Theme.CYAN));
        }
    }

    @Test void sessionSnapshotsAndTemporaryCliOverridesPreserveTheSavedVisualCopyChoice() {
        var ui = UiSessionState.from(AppSettings.defaults());
        ui.markdownVisualCopySetting = true;
        var saved = ui.settingsSnapshot(UiStyles.Theme.CYAN);
        var session = new AppSettingsSession(saved,
            TuiCliOptions.parse(new String[] {"--read", "--no-mouse", "--theme=light"}));
        assertTrue(session.initialSettings().markdownVisualCopy());
        var runtime = UiSessionState.from(session.initialSettings());
        assertTrue(runtime.markdownVisualCopySetting);
        assertEquals(saved, session.preferences(runtime.settingsSnapshot(UiStyles.Theme.LIGHT)));
        runtime.markdownVisualCopySetting = false;
        var changed = session.preferences(runtime.settingsSnapshot(UiStyles.Theme.LIGHT));
        assertFalse(changed.markdownVisualCopy());
        assertEquals(saved.mouseMode(), changed.mouseMode());
        assertEquals(saved.rightClickClipboard(), changed.rightClickClipboard());
        assertEquals(saved.readingMode(), changed.readingMode());
        assertEquals(saved.theme(), changed.theme());
        runtime.markdownVisualCopySetting = true;
        assertEquals(saved, session.preferences(runtime.settingsSnapshot(UiStyles.Theme.LIGHT)));
    }
}
