package dev.tuink.tui.flow;

import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.project.search.SearchPreferences;

public record AppSettings(
    boolean showTabBar,
    boolean wrapLines,
    boolean wrapWords,
    boolean readingMode,
    boolean confirmOnClose,
    boolean mouseMode,
    boolean highlightActiveGutter,
    boolean smartHomeEnd,
    boolean autoIndent,
    boolean autoPairs,
    boolean autoList,
    boolean visibleWhitespace,
    boolean syntaxHighlighting,
    boolean rainbowBrackets,
    boolean autoSelectionHighlight,
    boolean markdownOpenBothTabs,
    boolean markdownOpenInPreview,
    boolean markdownCodeHighlighting,
    GutterDiffMode gutterDiffMode,
    int tabWidth,
    TabInsertMode tabInsertMode,
    UiStyles.Theme theme,
    boolean rightClickClipboard,
    boolean markdownVisualCopy,
    boolean showFileHistoryNotifications,
    boolean showDocumentPath,
    SearchPreferences projectSearch,
    PalettePreferences palettes,
    boolean autoReloadUnmodifiedFiles
) {
    public AppSettings {
        java.util.Objects.requireNonNull(projectSearch, "projectSearch");
        java.util.Objects.requireNonNull(palettes, "palettes");
    }

    public AppSettings(boolean showTabBar, boolean wrapLines, boolean wrapWords, boolean readingMode,
        boolean confirmOnClose, boolean mouseMode, boolean highlightActiveGutter, boolean smartHomeEnd,
        boolean autoIndent, boolean autoPairs, boolean autoList, boolean visibleWhitespace,
        boolean syntaxHighlighting, boolean rainbowBrackets, boolean autoSelectionHighlight,
        boolean markdownOpenBothTabs, boolean markdownOpenInPreview, boolean markdownCodeHighlighting,
        GutterDiffMode gutterDiffMode, int tabWidth, TabInsertMode tabInsertMode, UiStyles.Theme theme,
        boolean rightClickClipboard, boolean markdownVisualCopy, boolean showFileHistoryNotifications,
        boolean showDocumentPath) {
        this(showTabBar, wrapLines, wrapWords, readingMode, confirmOnClose, mouseMode, highlightActiveGutter,
            smartHomeEnd, autoIndent, autoPairs, autoList, visibleWhitespace, syntaxHighlighting, rainbowBrackets,
            autoSelectionHighlight, markdownOpenBothTabs, markdownOpenInPreview, markdownCodeHighlighting,
            gutterDiffMode, tabWidth, tabInsertMode, theme, rightClickClipboard, markdownVisualCopy,
            showFileHistoryNotifications, showDocumentPath, SearchPreferences.defaults(), PalettePreferences.defaults(), true);
    }
    public AppSettings(boolean showTabBar, boolean wrapLines, boolean wrapWords, boolean readingMode,
        boolean confirmOnClose, boolean mouseMode, boolean highlightActiveGutter, boolean smartHomeEnd,
        boolean autoIndent, boolean autoPairs, boolean autoList, boolean visibleWhitespace,
        boolean syntaxHighlighting, boolean rainbowBrackets, boolean autoSelectionHighlight,
        boolean markdownOpenBothTabs, boolean markdownOpenInPreview, boolean markdownCodeHighlighting,
        GutterDiffMode gutterDiffMode, int tabWidth, TabInsertMode tabInsertMode, UiStyles.Theme theme,
        boolean rightClickClipboard, boolean markdownVisualCopy, boolean showFileHistoryNotifications) {
        this(showTabBar, wrapLines, wrapWords, readingMode, confirmOnClose, mouseMode,
            highlightActiveGutter, smartHomeEnd, autoIndent, autoPairs, autoList, visibleWhitespace,
            syntaxHighlighting, rainbowBrackets, autoSelectionHighlight, markdownOpenBothTabs,
            markdownOpenInPreview, markdownCodeHighlighting, gutterDiffMode, tabWidth, tabInsertMode,
            theme, rightClickClipboard, markdownVisualCopy, showFileHistoryNotifications, true);
    }

    public static AppSettings defaults() {
        return new AppSettings(
            true,
            false,
            true,
            false,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            false,
            false,
            false,
            GutterDiffMode.MYERS,
            4,
            TabInsertMode.AUTO,
            UiStyles.Theme.CYAN,
            false,
            false,
            true
        );
    }
}
