package dev.tuink.tui;

import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Path;

final class EditorActionController {
    private EditorActionController() {
    }

    static Result decide(
        TuinkCommand command,
        boolean running,
        UiModalState modalState,
        boolean confirmOnCloseSetting,
        boolean hasDirtyTabs,
        boolean activeTabDirty,
        int activeTabIndex,
        Path currentFile,
        boolean currentFileReadOnly
    ) {
        boolean nextRunning = running;
        UiModalState nextModalState = modalState;
        SaveAsPreset saveAsPreset = SaveAsPreset.NONE;
        TabCommand tabCommand = TabCommand.NONE;
        boolean clearNotification = false;
        String notificationError = null;
        boolean requestSaveCurrent = false;
        boolean requestOpenFile = false;
        boolean requestToggleTheme = false;

        switch (command) {
            case APP_QUIT -> {
                if (confirmOnCloseSetting && hasDirtyTabs) {
                    nextModalState = UiModalState.quitConfirm();
                } else {
                    nextRunning = false;
                }
            }
            case DOCUMENT_SAVE -> {
                if (currentFile == null) {
                    nextModalState = UiModalState.saveAs();
                    saveAsPreset = SaveAsPreset.DEFAULT_FILE_NAME;
                    clearNotification = true;
                } else if (currentFileReadOnly) {
                    nextModalState = UiModalState.saveAs();
                    saveAsPreset = SaveAsPreset.SUGGESTED_FROM_CURRENT;
                    notificationError = "File is read-only. Use Save As. " + currentFile;
                } else {
                    requestSaveCurrent = true;
                }
            }
            case DOCUMENT_OPEN -> {
                requestOpenFile = true;
            }
            case VIEW_CYCLE_THEME -> {
                requestToggleTheme = true;
            }
            case TAB_NEW -> {
                tabCommand = TabCommand.NEW_TAB;
            }
            case TAB_NEXT -> tabCommand = TabCommand.NEXT_TAB;
            case TAB_PREVIOUS -> tabCommand = TabCommand.PREV_TAB;
            case TAB_CLOSE -> {
                if (activeTabDirty && confirmOnCloseSetting) {
                    nextModalState = UiModalState.closeTabConfirm(activeTabIndex);
                } else {
                    tabCommand = TabCommand.CLOSE_TAB;
                }
            }
            case DOCUMENT_SAVE_AS -> {
                nextModalState = UiModalState.saveAs();
                saveAsPreset = SaveAsPreset.FROM_CURRENT_OR_DEFAULT;
                clearNotification = true;
            }
            default -> {
                // Ignore unsupported input in editor mode.
            }
        }

        return new Result(
            nextRunning,
            nextModalState,
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
        UiModalState modalState,
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
