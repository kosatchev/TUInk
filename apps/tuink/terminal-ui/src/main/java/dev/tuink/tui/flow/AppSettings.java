package dev.tuink.tui.flow;

import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.tui.style.UiStyles;

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
    UiStyles.Theme theme
) {
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
            UiStyles.Theme.CYAN
        );
    }
}
