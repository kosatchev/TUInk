package dev.tuink.tui.ui;

import dev.tuink.tui.flow.SearchFocus;

public final class CursorPolicy {
    private CursorPolicy() {
    }

    public static boolean shouldShowCursor(
        boolean saveAsMode,
        boolean searchBarVisible,
        SearchFocus searchFocus,
        boolean confirmBarVisible,
        boolean topMenuOpen,
        boolean openFileMode,
        boolean helpHintsMode
    ) {
        if (saveAsMode) {
            return true;
        }
        if (searchBarVisible) {
            return searchFocus == SearchFocus.FIND || searchFocus == SearchFocus.REPLACE;
        }
        if (confirmBarVisible) {
            return false;
        }
        if (topMenuOpen || openFileMode || helpHintsMode) {
            return false;
        }
        return true;
    }
}
