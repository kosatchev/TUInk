package dev.tuink.tui;

import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.style.UiStyles;

final class UiSessionState {
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
    boolean rainbowBracketsSetting;
    boolean autoSelectionHighlightSetting;
    boolean markdownOpenBothTabsSetting;
    boolean markdownOpenInPreviewSetting;
    boolean markdownCodeHighlightingSetting;
    GutterDiffMode gutterDiffModeSetting;
    int tabWidthSetting;
    TabInsertMode tabInsertModeSetting;

    AppSettings settingsSnapshot(UiStyles.Theme theme) {
        return new AppSettings(showTabBarSetting, wrapLinesSetting, wrapWordsSetting,
            readingModeSetting, confirmOnCloseSetting, mouseModeSetting, highlightActiveGutterSetting,
            smartHomeEndSetting, autoIndentSetting, autoPairsSetting, autoListSetting,
            visibleWhitespaceSetting, syntaxHighlightingSetting, rainbowBracketsSetting,
            autoSelectionHighlightSetting, markdownOpenBothTabsSetting, markdownOpenInPreviewSetting,
            markdownCodeHighlightingSetting,
            gutterDiffModeSetting, tabWidthSetting,
            tabInsertModeSetting, theme);
    }

    static UiSessionState from(AppSettings settings) {
        UiSessionState state = new UiSessionState();
        state.showTabBarSetting = settings.showTabBar();
        state.wrapLinesSetting = settings.wrapLines();
        state.wrapWordsSetting = settings.wrapWords();
        state.readingModeSetting = settings.readingMode();
        state.confirmOnCloseSetting = settings.confirmOnClose();
        state.mouseModeSetting = settings.mouseMode();
        state.highlightActiveGutterSetting = settings.highlightActiveGutter();
        state.smartHomeEndSetting = settings.smartHomeEnd();
        state.autoIndentSetting = settings.autoIndent();
        state.autoPairsSetting = settings.autoPairs();
        state.autoListSetting = settings.autoList();
        state.visibleWhitespaceSetting = settings.visibleWhitespace();
        state.syntaxHighlightingSetting = settings.syntaxHighlighting();
        state.rainbowBracketsSetting = settings.rainbowBrackets();
        state.autoSelectionHighlightSetting = settings.autoSelectionHighlight();
        state.markdownOpenBothTabsSetting = settings.markdownOpenBothTabs();
        state.markdownOpenInPreviewSetting = settings.markdownOpenInPreview();
        state.markdownCodeHighlightingSetting = settings.markdownCodeHighlighting();
        state.gutterDiffModeSetting = settings.gutterDiffMode();
        state.tabWidthSetting = settings.tabWidth();
        state.tabInsertModeSetting = settings.tabInsertMode();
        return state;
    }
}
