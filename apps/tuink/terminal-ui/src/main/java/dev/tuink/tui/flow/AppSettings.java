package dev.tuink.tui.flow;

import dev.tuink.tui.UiStyles;

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
    int tabWidth,
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
            4,
            UiStyles.Theme.CYAN
        );
    }
}
