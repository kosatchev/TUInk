package dev.tuink.tui.ui;

import dev.tuink.tui.flow.SearchFocus;

public final class CursorPolicy {
    private CursorPolicy() {
    }

    public static boolean shouldShowCursor(
        boolean saveAsMode,
        boolean searchBarVisible,
        SearchFocus searchFocus,
        boolean saveOverwriteConfirmMode,
        boolean quitConfirmMode,
        boolean closeTabConfirmMode,
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
        if (saveOverwriteConfirmMode || quitConfirmMode || closeTabConfirmMode) {
            return false;
        }
        if (topMenuOpen || openFileMode || helpHintsMode) {
            return false;
        }
        return true;
    }
}
