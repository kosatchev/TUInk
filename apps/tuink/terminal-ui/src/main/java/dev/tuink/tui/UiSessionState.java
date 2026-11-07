package dev.tuink.tui;

import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.style.UiStyles;

final class UiSessionState {
    final ExplorerState explorer = new ExplorerState();
    boolean showTabBarSetting;
    boolean wrapLinesSetting;
    boolean wrapWordsSetting;
    boolean readingModeSetting;
    boolean confirmOnCloseSetting;
    boolean mouseModeSetting;
    boolean rightClickClipboardSetting;
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
    boolean markdownVisualCopySetting;
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
            tabInsertModeSetting, theme, rightClickClipboardSetting, markdownVisualCopySetting);
    }

    static UiSessionState from(AppSettings settings) {
        UiSessionState state = new UiSessionState();
        state.apply(settings);
        return state;
    }

    void apply(AppSettings settings) {
        showTabBarSetting = settings.showTabBar();
        wrapLinesSetting = settings.wrapLines();
        wrapWordsSetting = settings.wrapWords();
        readingModeSetting = settings.readingMode();
        confirmOnCloseSetting = settings.confirmOnClose();
        mouseModeSetting = settings.mouseMode();
        rightClickClipboardSetting = settings.rightClickClipboard();
        highlightActiveGutterSetting = settings.highlightActiveGutter();
        smartHomeEndSetting = settings.smartHomeEnd();
        autoIndentSetting = settings.autoIndent();
        autoPairsSetting = settings.autoPairs();
        autoListSetting = settings.autoList();
        visibleWhitespaceSetting = settings.visibleWhitespace();
        syntaxHighlightingSetting = settings.syntaxHighlighting();
        rainbowBracketsSetting = settings.rainbowBrackets();
        autoSelectionHighlightSetting = settings.autoSelectionHighlight();
        markdownOpenBothTabsSetting = settings.markdownOpenBothTabs();
        markdownOpenInPreviewSetting = settings.markdownOpenInPreview();
        markdownCodeHighlightingSetting = settings.markdownCodeHighlighting();
        markdownVisualCopySetting = settings.markdownVisualCopy();
        gutterDiffModeSetting = settings.gutterDiffMode();
        tabWidthSetting = settings.tabWidth();
        tabInsertModeSetting = settings.tabInsertMode();
    }
}
