package dev.tuink.tui;

import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.tui.flow.AppSettings;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.project.search.SearchPreferences;

final class UiSessionState {
    final SidebarState sidebar = new SidebarState();
    SearchPreferences searchPreferences = SearchPreferences.defaults();
    ProjectSearchSettingsDialog.State searchSettingsDialog;
    boolean showTabBarSetting;
    boolean showDocumentPathSetting = true;
    boolean wrapLinesSetting;
    boolean wrapWordsSetting;
    boolean readingModeSetting;
    boolean confirmOnCloseSetting;
    boolean mouseModeSetting;
    boolean autoReloadUnmodifiedFilesSetting = true;
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
    boolean showFileHistoryNotificationsSetting = true;
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
            tabInsertModeSetting, theme, rightClickClipboardSetting, markdownVisualCopySetting,
            showFileHistoryNotificationsSetting, showDocumentPathSetting, searchPreferences, sidebar.preferences(), autoReloadUnmodifiedFilesSetting);
    }

    static UiSessionState from(AppSettings settings) {
        UiSessionState state = new UiSessionState();
        state.apply(settings);
        return state;
    }

    void apply(AppSettings settings) {
        sidebar.defaults(settings.palettes());
        searchPreferences = settings.projectSearch();
        sidebar.search.defaults(searchPreferences);
        showTabBarSetting = settings.showTabBar();
        showDocumentPathSetting = settings.showDocumentPath();
        wrapLinesSetting = settings.wrapLines();
        wrapWordsSetting = settings.wrapWords();
        readingModeSetting = settings.readingMode();
        confirmOnCloseSetting = settings.confirmOnClose();
        mouseModeSetting = settings.mouseMode();
        autoReloadUnmodifiedFilesSetting = settings.autoReloadUnmodifiedFiles();
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
        showFileHistoryNotificationsSetting = settings.showFileHistoryNotifications();
        gutterDiffModeSetting = settings.gutterDiffMode();
        tabWidthSetting = settings.tabWidth();
        tabInsertModeSetting = settings.tabInsertMode();
    }
}
