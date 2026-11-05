package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.style.UiStyles;
import org.junit.jupiter.api.Test;

class RightClickClipboardSettingsSessionTest {
    @Test void theToggleIsIndependentOfMouseAvailabilityReadingAndPresentationKind() {
        for (var kind : ActiveSurface.Kind.values()) {
            var ui = UiSessionState.from(AppSettings.defaults());
            var before = ui.settingsSnapshot(UiStyles.Theme.CYAN);
            assertFalse(ui.rightClickClipboardSetting);
            var surface = TestSurfaces.source(ui).withKind(kind).withRuntime(false, true);
            var enabled = InputActionDispatcher.dispatchUi(TuinkCommand.SETTINGS_TOGGLE_RIGHT_CLICK_CLIPBOARD,
                ui, surface, null, ignored -> fail("This preference must not change terminal tracking"), () -> fail());
            assertTrue(enabled.handled());
            assertTrue(enabled.statusMessage().contains("enabled"));
            assertTrue(ui.rightClickClipboardSetting);
            assertEquals(before.mouseMode(), ui.mouseModeSetting);
            assertEquals(before.readingMode(), ui.readingModeSetting);
            var disabled = InputActionDispatcher.dispatchUi(TuinkCommand.SETTINGS_TOGGLE_RIGHT_CLICK_CLIPBOARD,
                ui, surface, null, ignored -> fail(), () -> fail());
            assertTrue(disabled.handled());
            assertTrue(disabled.statusMessage().contains("disabled"));
            assertEquals(before, ui.settingsSnapshot(UiStyles.Theme.CYAN));
        }
    }

    @Test void sessionSnapshotsAndTemporaryCliOverridesPreserveTheSavedRightClickChoice() {
        var ui = UiSessionState.from(AppSettings.defaults());
        ui.rightClickClipboardSetting = true;
        var saved = ui.settingsSnapshot(UiStyles.Theme.CYAN);
        var session = new AppSettingsSession(saved,
            TuiCliOptions.parse(new String[] {"--read", "--no-mouse", "--theme=monochrome"}));
        assertTrue(session.initialSettings().rightClickClipboard());
        var runtime = UiSessionState.from(session.initialSettings());
        assertTrue(runtime.rightClickClipboardSetting);
        assertEquals(saved, session.preferences(runtime.settingsSnapshot(UiStyles.Theme.MONOCHROME)));
        runtime.rightClickClipboardSetting = false;
        var changed = session.preferences(runtime.settingsSnapshot(UiStyles.Theme.MONOCHROME));
        assertFalse(changed.rightClickClipboard());
        assertEquals(saved.mouseMode(), changed.mouseMode());
        assertEquals(saved.readingMode(), changed.readingMode());
        assertEquals(saved.theme(), changed.theme());
        runtime.rightClickClipboardSetting = true;
        assertEquals(saved, session.preferences(runtime.settingsSnapshot(UiStyles.Theme.MONOCHROME)));
    }
}
