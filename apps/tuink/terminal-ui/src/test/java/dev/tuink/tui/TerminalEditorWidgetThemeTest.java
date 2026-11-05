package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tuiframe.widget.WidgetStyleRole;
import dev.tuiframe.widget.WidgetThemeKeys;
import dev.tuiframe.style.CellStyle;
import dev.tuink.tui.style.UiStyles;
import org.junit.jupiter.api.Test;

class TerminalEditorWidgetThemeTest {
    @Test
    void helpOverlayAdapterMapsColorMonoAndUnknownWidgetRoles() {
        TerminalEditorApp app = new TerminalEditorApp();
        UiStyles.Palette palette = UiStyles.forTheme(UiStyles.Theme.CYAN);

        assertEquals(palette.windows().dialog(), app.resolveOverlayWidgetStyle(WidgetThemeKeys.MODAL_BODY, true));
        assertEquals(palette.windows().dialogBorder(), app.resolveOverlayWidgetStyle(WidgetThemeKeys.MODAL_BORDER, true));
        assertEquals(palette.windows().dialogTitle(), app.resolveOverlayWidgetStyle(WidgetThemeKeys.MODAL_TITLE, true));
        assertEquals(palette.windows().dialog(), app.resolveOverlayWidgetStyle(WidgetThemeKeys.BUTTON, true));
        assertEquals(palette.windows().menuSelected(), app.resolveOverlayWidgetStyle(WidgetThemeKeys.BUTTON_FOCUSED, true));
        assertEquals(
            palette.windows().dialogSelectedMono(),
            app.resolveOverlayWidgetStyle(WidgetThemeKeys.BUTTON_FOCUSED, false)
        );
        assertEquals(palette.windows().dialog(), app.resolveOverlayWidgetStyle(WidgetThemeKeys.BUTTON_DISABLED, true));
        assertNull(app.resolveOverlayWidgetStyle(new WidgetStyleRole("test.unknown"), true));
    }

    @Test
    void confirmAdapterUsesPromptStyleAndOneFocusedVariant() {
        TerminalEditorApp app = new TerminalEditorApp();
        UiStyles.Palette palette = UiStyles.forTheme(UiStyles.Theme.CYAN);
        CellStyle prompt = palette.windows().promptAlert();

        assertEquals(prompt, app.resolveConfirmWidgetStyle(WidgetThemeKeys.BUTTON));
        assertEquals(prompt, app.resolveConfirmWidgetStyle(WidgetThemeKeys.BUTTON_DISABLED));
        assertEquals(
            new CellStyle(prompt.bg(), prompt.fg(), true, prompt.underline()),
            app.resolveConfirmWidgetStyle(WidgetThemeKeys.BUTTON_FOCUSED)
        );
        assertNull(app.resolveConfirmWidgetStyle(new WidgetStyleRole("test.unknown")));
    }

    @Test
    void saveAsAdapterUsesThePromptStyleAndHighlightsFocusedButtons() {
        TerminalEditorApp app = new TerminalEditorApp();
        CellStyle prompt = UiStyles.forTheme(UiStyles.Theme.CYAN).windows().promptAlert();

        assertEquals(prompt, app.resolveSaveAsWidgetStyle(WidgetThemeKeys.TEXT_FIELD));
        assertEquals(prompt, app.resolveSaveAsWidgetStyle(WidgetThemeKeys.TEXT_FIELD_FOCUSED));
        assertEquals(prompt, app.resolveSaveAsWidgetStyle(WidgetThemeKeys.TEXT_FIELD_DISABLED));
        assertEquals(prompt, app.resolveSaveAsWidgetStyle(WidgetThemeKeys.BUTTON));
        assertEquals(prompt, app.resolveSaveAsWidgetStyle(WidgetThemeKeys.BUTTON_DISABLED));
        assertEquals(
            new CellStyle(prompt.bg(), prompt.fg(), true, prompt.underline()),
            app.resolveSaveAsWidgetStyle(WidgetThemeKeys.BUTTON_FOCUSED)
        );
        assertNull(app.resolveSaveAsWidgetStyle(new WidgetStyleRole("test.unknown")));
    }

    @Test
    void searchAdapterUsesOneBarStyleAndFocusedButtonVariant() {
        TerminalEditorApp app = new TerminalEditorApp();
        CellStyle search = UiStyles.forTheme(UiStyles.Theme.CYAN).windows().searchBar();

        assertEquals(search, app.resolveSearchWidgetStyle(WidgetThemeKeys.BUTTON));
        assertEquals(search, app.resolveSearchWidgetStyle(WidgetThemeKeys.BUTTON_DISABLED));
        assertEquals(search, app.resolveSearchWidgetStyle(WidgetThemeKeys.TEXT_FIELD));
        assertEquals(search, app.resolveSearchWidgetStyle(WidgetThemeKeys.TEXT_FIELD_FOCUSED));
        assertEquals(search, app.resolveSearchWidgetStyle(WidgetThemeKeys.TEXT_FIELD_DISABLED));
        assertEquals(
            new CellStyle(search.bg(), search.fg(), true, search.underline()),
            app.resolveSearchWidgetStyle(WidgetThemeKeys.BUTTON_FOCUSED)
        );
        assertNull(app.resolveSearchWidgetStyle(new WidgetStyleRole("test.unknown")));
    }

    @Test
    void tabBarAdapterMapsColorMonoAndActiveRolesWithoutMutableWidgetStyles() {
        TerminalEditorApp app = new TerminalEditorApp();
        UiStyles.Palette palette = UiStyles.forTheme(UiStyles.Theme.CYAN);

        assertEquals(palette.windows().tabbar(), app.resolveTabBarWidgetStyle(WidgetThemeKeys.TAB_BAR, true));
        assertEquals(palette.windows().tabbar(), app.resolveTabBarWidgetStyle(WidgetThemeKeys.TAB_ITEM, true));
        assertEquals(
            palette.windows().tabbarActive(),
            app.resolveTabBarWidgetStyle(WidgetThemeKeys.TAB_ITEM_ACTIVE, true)
        );
        assertEquals(
            palette.windows().tabbarMono(),
            app.resolveTabBarWidgetStyle(WidgetThemeKeys.TAB_ITEM_DISABLED, false)
        );
        assertEquals(
            palette.windows().tabbarActiveMono(),
            app.resolveTabBarWidgetStyle(WidgetThemeKeys.TAB_ITEM_ACTIVE, false)
        );
        assertNull(app.resolveTabBarWidgetStyle(new WidgetStyleRole("test.unknown"), true));
    }

    @Test
    void topMenuAdapterMapsApplicationPaletteWithoutLeakingItIntoWidgets() {
        TerminalEditorApp app = new TerminalEditorApp();
        UiStyles.Palette palette = UiStyles.forTheme(UiStyles.Theme.CYAN);

        assertEquals(
            palette.windows().renderTopbar(),
            app.resolveTopMenuWidgetStyle(WidgetThemeKeys.MENU_BAR, true)
        );
        assertEquals(
            palette.windows().menuTopSelected(),
            app.resolveTopMenuWidgetStyle(WidgetThemeKeys.MENU_BAR_ACTIVE, true)
        );
        assertEquals(
            palette.windows().menuPopup(),
            app.resolveTopMenuWidgetStyle(WidgetThemeKeys.MENU_POPUP, true)
        );
        assertEquals(
            palette.windows().menuSelected(),
            app.resolveTopMenuWidgetStyle(WidgetThemeKeys.MENU_ITEM_SELECTED, true)
        );
        assertEquals(
            palette.windows().contextMenuDisabledItem(),
            app.resolveTopMenuWidgetStyle(WidgetThemeKeys.MENU_ITEM_DISABLED, true)
        );
        assertEquals(
            palette.windows().menuSelectedMono(),
            app.resolveTopMenuWidgetStyle(WidgetThemeKeys.MENU_ITEM_SELECTED, false)
        );
        assertEquals(
            palette.windows().menuPopup(),
            app.resolveTopMenuWidgetStyle(WidgetThemeKeys.MENU_ITEM_DISABLED, false)
        );
        assertNull(app.resolveTopMenuWidgetStyle(new WidgetStyleRole("test.unknown"), true));
    }

    @Test
    void fileTreeAdapterMapsListAndScrollbarRoles() {
        TerminalEditorApp app = new TerminalEditorApp();
        UiStyles.Palette palette = UiStyles.forTheme(UiStyles.Theme.CYAN);

        assertEquals(palette.windows().dialog(), app.resolveFileTreeWidgetStyle(WidgetThemeKeys.LIST_ITEM, true));
        assertEquals(
            palette.windows().dialog(),
            app.resolveFileTreeWidgetStyle(WidgetThemeKeys.LIST_ITEM_DISABLED, false)
        );
        assertEquals(
            palette.windows().menuSelected(),
            app.resolveFileTreeWidgetStyle(WidgetThemeKeys.LIST_ITEM_SELECTED_FOCUSED, true)
        );
        assertEquals(
            palette.windows().dialogSelectedMono(),
            app.resolveFileTreeWidgetStyle(WidgetThemeKeys.LIST_ITEM_SELECTED, false)
        );
        assertEquals(palette.windows().dialog(), app.resolveOverlayWidgetStyle(WidgetThemeKeys.LIST_ITEM, true));
        assertNull(app.resolveFileTreeWidgetStyle(new WidgetStyleRole("test.unknown"), true));
    }
}
