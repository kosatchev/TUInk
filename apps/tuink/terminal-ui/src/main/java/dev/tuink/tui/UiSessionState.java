package dev.tuink.tui;

import dev.tuink.tui.flow.SearchFocus;

final class UiSessionState {
    boolean searchMode;
    SearchFocus searchFocus;
    boolean showTabBarSetting;
    boolean wrapLinesSetting;
    boolean wrapWordsSetting;
    boolean readingModeSetting;
    boolean confirmOnCloseSetting;
    boolean mouseModeSetting;
    boolean highlightActiveGutterSetting;
    boolean smartHomeEndSetting;
    boolean autoIndentSetting;
    boolean autoPairsSetting;
    boolean autoListSetting;
    boolean visibleWhitespaceSetting;
    boolean syntaxHighlightingSetting;
    boolean autoSelectionHighlightSetting;
    int tabWidthSetting;
    boolean helpHintsMode;
    boolean helpLicensesMode;
    boolean helpAuthorMode;

    static UiSessionState from(
        boolean searchMode,
        SearchFocus searchFocus,
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
        boolean autoSelectionHighlightSetting,
        int tabWidthSetting,
        boolean helpHintsMode,
        boolean helpLicensesMode,
        boolean helpAuthorMode
    ) {
        UiSessionState state = new UiSessionState();
        state.searchMode = searchMode;
        state.searchFocus = searchFocus;
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
        state.autoSelectionHighlightSetting = autoSelectionHighlightSetting;
        state.tabWidthSetting = tabWidthSetting <= 2 ? 2 : 4;
        state.helpHintsMode = helpHintsMode;
        state.helpLicensesMode = helpLicensesMode;
        state.helpAuthorMode = helpAuthorMode;
        return state;
    }
}
