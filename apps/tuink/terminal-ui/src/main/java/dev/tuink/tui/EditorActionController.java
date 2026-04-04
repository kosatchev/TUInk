package dev.tuink.tui;

import dev.tuink.tui.input.KeyEvent;
import java.nio.file.Path;

final class EditorActionController {
    private EditorActionController() {
    }

    static Result decide(
        KeyEvent.Action action,
        boolean running,
        boolean quitConfirmMode,
        boolean closeTabConfirmMode,
        int pendingCloseTabIndex,
        boolean saveAsMode,
        boolean confirmOnCloseSetting,
        boolean hasDirtyTabs,
        boolean activeTabDirty,
        int activeTabIndex,
        Path currentFile,
        boolean currentFileReadOnly
    ) {
        boolean nextRunning = running;
        boolean nextQuitConfirmMode = quitConfirmMode;
        boolean nextCloseTabConfirmMode = closeTabConfirmMode;
        int nextPendingCloseTabIndex = pendingCloseTabIndex;
        boolean nextSaveAsMode = saveAsMode;
        SaveAsPreset saveAsPreset = SaveAsPreset.NONE;
        TabCommand tabCommand = TabCommand.NONE;
        boolean clearNotification = false;
        String notificationError = null;
        boolean requestSaveCurrent = false;
        boolean requestOpenFile = false;
        boolean requestToggleTheme = false;

        switch (action) {
            case CTRL_Q -> {
                if (confirmOnCloseSetting && hasDirtyTabs) {
                    nextQuitConfirmMode = true;
                } else {
                    nextRunning = false;
                }
            }
            case CTRL_S -> {
                if (currentFile == null) {
                    nextSaveAsMode = true;
                    saveAsPreset = SaveAsPreset.DEFAULT_FILE_NAME;
                    clearNotification = true;
                } else if (currentFileReadOnly) {
                    nextSaveAsMode = true;
                    saveAsPreset = SaveAsPreset.SUGGESTED_FROM_CURRENT;
                    notificationError = "File is read-only. Use Save As. " + currentFile;
                } else {
                    requestSaveCurrent = true;
                }
            }
            case CTRL_O -> {
                requestOpenFile = true;
            }
            case TOGGLE_THEME -> {
                requestToggleTheme = true;
            }
            case CTRL_N, TAB_NEW -> {
                tabCommand = TabCommand.NEW_TAB;
            }
            case TAB_NEXT -> tabCommand = TabCommand.NEXT_TAB;
            case TAB_PREVIOUS -> tabCommand = TabCommand.PREV_TAB;
            case TAB_CLOSE -> {
                if (activeTabDirty && confirmOnCloseSetting) {
                    nextCloseTabConfirmMode = true;
                    nextPendingCloseTabIndex = activeTabIndex;
                } else {
                    tabCommand = TabCommand.CLOSE_TAB;
                }
            }
            case CTRL_K, CTRL_W, CTRL_SHIFT_S -> {
                nextSaveAsMode = true;
                saveAsPreset = SaveAsPreset.FROM_CURRENT_OR_DEFAULT;
                clearNotification = true;
            }
            case CTRL_E, CTRL_T, OPEN_VIEW_MENU, OPEN_HELP_MENU, F10, F2,
                CTRL_C, CTRL_X, CTRL_V, CTRL_Z, CTRL_Y,
                TOGGLE_TAB_BAR, TOGGLE_WRAP_LINES, TOGGLE_WORD_WRAP,
                TOGGLE_READING_MODE, TOGGLE_CONFIRM_ON_CLOSE, TOGGLE_MOUSE_MODE, SHOW_KEY_HINTS,
                TOGGLE_GUTTER_DIFF,
                CTRL_F, CTRL_A, CTRL_R, TAB, CTRL_G, ESCAPE,
                ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
                SHIFT_ARROW_UP, SHIFT_ARROW_DOWN, SHIFT_ARROW_LEFT, SHIFT_ARROW_RIGHT,
                PAGE_UP, PAGE_DOWN, HOME, END, BACKSPACE, DELETE, ENTER,
                UNKNOWN -> {
                // Already handled by InputActionDispatcher or intentionally ignored.
            }
            default -> {
                // Ignore unsupported input in editor mode.
            }
        }

        return new Result(
            nextRunning,
            nextQuitConfirmMode,
            nextCloseTabConfirmMode,
            nextPendingCloseTabIndex,
            nextSaveAsMode,
            saveAsPreset,
            tabCommand,
            clearNotification,
            notificationError,
            requestSaveCurrent,
            requestOpenFile,
            requestToggleTheme
        );
    }

    enum SaveAsPreset {
        NONE,
        DEFAULT_FILE_NAME,
        FROM_CURRENT_OR_DEFAULT,
        SUGGESTED_FROM_CURRENT
    }

    enum TabCommand {
        NONE,
        NEW_TAB,
        NEXT_TAB,
        PREV_TAB,
        CLOSE_TAB
    }

    record Result(
        boolean running,
        boolean quitConfirmMode,
        boolean closeTabConfirmMode,
        int pendingCloseTabIndex,
        boolean saveAsMode,
        SaveAsPreset saveAsPreset,
        TabCommand tabCommand,
        boolean clearNotification,
        String notificationError,
        boolean requestSaveCurrent,
        boolean requestOpenFile,
        boolean requestToggleTheme
    ) {
    }
}
