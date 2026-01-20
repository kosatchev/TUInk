package dev.tuink.tui;

import java.util.function.IntSupplier;

final class ConfirmFlowController {
    private ConfirmFlowController() {
    }

    static QuitResult handleQuitConfirm(boolean quitConfirmMode, boolean running, KeyEvent key) {
        if (!quitConfirmMode) {
            return QuitResult.notConsumed(quitConfirmMode, running);
        }

        boolean nextRunning = running;
        boolean nextQuitConfirmMode = quitConfirmMode;
        if (key instanceof KeyEvent.CharInput charInput) {
            char ch = charInput.ch();
            if (ch == 'y' || ch == 'Y') {
                nextRunning = false;
            } else if (ch == 'n' || ch == 'N') {
                nextQuitConfirmMode = false;
            }
            return new QuitResult(true, nextQuitConfirmMode, nextRunning);
        }

        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
        switch (action) {
            case CTRL_Q -> nextRunning = false;
            case ESCAPE, ENTER -> nextQuitConfirmMode = false;
            default -> {
                // Ignore unsupported keys in quit confirmation.
            }
        }
        return new QuitResult(true, nextQuitConfirmMode, nextRunning);
    }

    static CloseTabResult handleCloseTabConfirm(
        boolean closeTabConfirmMode,
        int pendingCloseTabIndex,
        int tabsSize,
        KeyEvent key,
        IntSupplier closePendingTab
    ) {
        if (!closeTabConfirmMode) {
            return CloseTabResult.notConsumed(closeTabConfirmMode, pendingCloseTabIndex);
        }
        if (pendingCloseTabIndex < 0 || pendingCloseTabIndex >= tabsSize) {
            return new CloseTabResult(true, false, -1, false, -1);
        }

        boolean nextCloseTabConfirmMode = closeTabConfirmMode;
        int nextPendingCloseTabIndex = pendingCloseTabIndex;
        boolean activeTabChanged = false;
        int activeTabIndex = -1;
        if (key instanceof KeyEvent.CharInput charInput) {
            char ch = charInput.ch();
            if (ch == 'y' || ch == 'Y') {
                activeTabIndex = closePendingTab.getAsInt();
                activeTabChanged = true;
                nextCloseTabConfirmMode = false;
                nextPendingCloseTabIndex = -1;
            } else if (ch == 'n' || ch == 'N') {
                nextCloseTabConfirmMode = false;
                nextPendingCloseTabIndex = -1;
            }
            return new CloseTabResult(
                true,
                nextCloseTabConfirmMode,
                nextPendingCloseTabIndex,
                activeTabChanged,
                activeTabIndex
            );
        }

        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
        switch (action) {
            case TAB_CLOSE -> {
                activeTabIndex = closePendingTab.getAsInt();
                activeTabChanged = true;
                nextCloseTabConfirmMode = false;
                nextPendingCloseTabIndex = -1;
            }
            case ESCAPE, ENTER -> {
                nextCloseTabConfirmMode = false;
                nextPendingCloseTabIndex = -1;
            }
            default -> {
                // Ignore unsupported keys in close-tab confirmation.
            }
        }
        return new CloseTabResult(
            true,
            nextCloseTabConfirmMode,
            nextPendingCloseTabIndex,
            activeTabChanged,
            activeTabIndex
        );
    }

    record QuitResult(boolean consumed, boolean quitConfirmMode, boolean running) {
        static QuitResult notConsumed(boolean quitConfirmMode, boolean running) {
            return new QuitResult(false, quitConfirmMode, running);
        }
    }

    record CloseTabResult(
        boolean consumed,
        boolean closeTabConfirmMode,
        int pendingCloseTabIndex,
        boolean activeTabChanged,
        int activeTabIndex
    ) {
        static CloseTabResult notConsumed(boolean closeTabConfirmMode, int pendingCloseTabIndex) {
            return new CloseTabResult(false, closeTabConfirmMode, pendingCloseTabIndex, false, -1);
        }
    }
}
