package dev.tuink.tui.ui;

public final class CursorPolicy {
    private CursorPolicy() {
    }

    public static boolean shouldShowCursor(
        boolean saveAsMode,
        boolean searchBarVisible,
        boolean searchCursorAvailable,
        boolean confirmBarVisible,
        boolean topMenuOpen,
        boolean openFileMode,
        boolean helpHintsMode
    ) {
        if (saveAsMode) {
            return true;
        }
        if (confirmBarVisible || topMenuOpen || openFileMode || helpHintsMode) {
            return false;
        }
        if (searchBarVisible) {
            return searchCursorAvailable;
        }
        return true;
    }
}
