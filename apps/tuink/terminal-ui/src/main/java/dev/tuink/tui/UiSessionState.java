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
        state.helpHintsMode = helpHintsMode;
        return state;
    }
}
