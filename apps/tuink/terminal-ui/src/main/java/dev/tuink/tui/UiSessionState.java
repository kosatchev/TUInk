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
    boolean visibleWhitespaceSetting;
    int tabWidthSetting;
    boolean helpHintsMode;

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
        boolean visibleWhitespaceSetting,
        int tabWidthSetting,
        boolean helpHintsMode
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
        state.visibleWhitespaceSetting = visibleWhitespaceSetting;
        state.tabWidthSetting = tabWidthSetting <= 2 ? 2 : 4;
        state.helpHintsMode = helpHintsMode;
        return state;
    }
}
