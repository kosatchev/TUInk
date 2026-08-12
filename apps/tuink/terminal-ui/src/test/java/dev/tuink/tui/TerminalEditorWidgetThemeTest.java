package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tuink.terminal.widget.WidgetStyleRole;
import dev.tuink.terminal.widget.WidgetThemeKeys;
import dev.tuink.tui.style.UiStyles;
import org.junit.jupiter.api.Test;

class TerminalEditorWidgetThemeTest {
    @Test
    void helpOverlayAdapterMapsColorMonoAndUnknownWidgetRoles() {
        TerminalEditorApp app = new TerminalEditorApp();
        UiStyles.Palette palette = UiStyles.forTheme(UiStyles.Theme.CYAN);

        assertEquals(palette.dialog(), app.resolveOverlayWidgetStyle(WidgetThemeKeys.MODAL_BODY, true));
        assertEquals(palette.dialogBorder(), app.resolveOverlayWidgetStyle(WidgetThemeKeys.MODAL_BORDER, true));
        assertEquals(palette.dialogTitle(), app.resolveOverlayWidgetStyle(WidgetThemeKeys.MODAL_TITLE, true));
        assertEquals(palette.dialog(), app.resolveOverlayWidgetStyle(WidgetThemeKeys.BUTTON, true));
        assertEquals(palette.menuSelected(), app.resolveOverlayWidgetStyle(WidgetThemeKeys.BUTTON_FOCUSED, true));
        assertEquals(
            palette.dialogSelectedMono(),
            app.resolveOverlayWidgetStyle(WidgetThemeKeys.BUTTON_FOCUSED, false)
        );
        assertEquals(palette.dialog(), app.resolveOverlayWidgetStyle(WidgetThemeKeys.BUTTON_DISABLED, true));
        assertNull(app.resolveOverlayWidgetStyle(new WidgetStyleRole("test.unknown"), true));
    }
}
