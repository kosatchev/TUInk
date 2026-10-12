package dev.tuink.tui;

import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;

final class UiSessionState {
    boolean searchMode;
    boolean searchEditorFocused;
    boolean showTabBarSetting;
    boolean wrapLinesSetting;
    boolean wrapWordsSetting;
    boolean readingModeSetting;
    boolean confirmOnCloseSetting;
    boolean mouseModeSetting;
    boolean mouseModeAvailable = true;
    boolean highlightActiveGutterSetting;
    boolean smartHomeEndSetting;
    boolean autoIndentSetting;
    boolean autoPairsSetting;
    boolean autoListSetting;
    boolean visibleWhitespaceSetting;
    boolean syntaxHighlightingSetting;
    boolean rainbowBracketsSetting;
    boolean autoSelectionHighlightSetting;
    GutterDiffMode gutterDiffModeSetting;
    int tabWidthSetting;
    TabInsertMode tabInsertModeSetting;
    boolean helpHintsMode;
    boolean helpLicensesMode;
    boolean helpAuthorMode;

    static UiSessionState from(
        boolean searchMode,
        boolean showTabBarSetting,
        boolean wrapLinesSetting,
        boolean wrapWordsSetting,
        boolean readingModeSetting,
        boolean confirmOnCloseSetting,
        boolean mouseModeSetting,
        boolean highlightActiveGutterSetting,
        boolean smartHomeEndSetting,
        boolean autoIndentSetting,
        boolean autoPairsSetting,
        boolean autoListSetting,
        boolean visibleWhitespaceSetting,
        boolean syntaxHighlightingSetting,
        boolean rainbowBracketsSetting,
        boolean autoSelectionHighlightSetting,
        GutterDiffMode gutterDiffModeSetting,
        int tabWidthSetting,
        TabInsertMode tabInsertModeSetting,
        boolean helpHintsMode,
        boolean helpLicensesMode,
        boolean helpAuthorMode
    ) {
        UiSessionState state = new UiSessionState();
        state.searchMode = searchMode;
        state.showTabBarSetting = showTabBarSetting;
        state.wrapLinesSetting = wrapLinesSetting;
        state.wrapWordsSetting = wrapWordsSetting;
        state.readingModeSetting = readingModeSetting;
        state.confirmOnCloseSetting = confirmOnCloseSetting;
        state.mouseModeSetting = mouseModeSetting;
        state.highlightActiveGutterSetting = highlightActiveGutterSetting;
        state.smartHomeEndSetting = smartHomeEndSetting;
        state.autoIndentSetting = autoIndentSetting;
        state.autoPairsSetting = autoPairsSetting;
        state.autoListSetting = autoListSetting;
        state.visibleWhitespaceSetting = visibleWhitespaceSetting;
        state.syntaxHighlightingSetting = syntaxHighlightingSetting;
        state.rainbowBracketsSetting = rainbowBracketsSetting;
        state.autoSelectionHighlightSetting = autoSelectionHighlightSetting;
        state.gutterDiffModeSetting = gutterDiffModeSetting == null ? GutterDiffMode.MYERS : gutterDiffModeSetting;
        state.tabWidthSetting = tabWidthSetting <= 2 ? 2 : 4;
        state.tabInsertModeSetting = tabInsertModeSetting == null ? TabInsertMode.AUTO : tabInsertModeSetting;
        state.helpHintsMode = helpHintsMode;
        state.helpLicensesMode = helpLicensesMode;
        state.helpAuthorMode = helpAuthorMode;
        return state;
    }
}
