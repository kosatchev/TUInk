package dev.tuink.tui.flow;

import dev.tuink.tui.UiStyles;

public record AppSettings(
    boolean showTabBar,
    boolean wrapLines,
    boolean wrapWords,
    boolean readingMode,
    boolean confirmOnClose,
    boolean mouseMode,
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
            UiStyles.Theme.CLASSIC
        );
    }
}
