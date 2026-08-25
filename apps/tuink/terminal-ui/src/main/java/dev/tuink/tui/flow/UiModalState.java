package dev.tuink.tui.flow;

import java.nio.file.Path;

public record UiModalState(
    UiModalMode mode,
    Path pendingOverwritePath,
    int pendingCloseTabIndex,
    SaveContinuation saveContinuation
) {
    public UiModalState {
        if (mode == null || saveContinuation == null) {
            throw new IllegalArgumentException("Modal mode and save continuation are required");
        }
        if (mode == UiModalMode.SAVE_OVERWRITE_CONFIRM) {
            if (pendingOverwritePath == null) {
                throw new IllegalArgumentException("Overwrite confirmation requires an overwrite path");
            }
        } else if (mode == UiModalMode.CLOSE_TAB_CONFIRM) {
            if (pendingOverwritePath != null
                || pendingCloseTabIndex < 0
                || saveContinuation != SaveContinuation.NONE) {
                throw new IllegalArgumentException("Close-tab confirmation requires only a tab index");
            }
        } else if (pendingOverwritePath != null) {
            throw new IllegalArgumentException("Modal payload does not match mode " + mode);
        }
        boolean saveFlowMode = mode == UiModalMode.SAVE_AS || mode == UiModalMode.SAVE_OVERWRITE_CONFIRM;
        if (!saveFlowMode && saveContinuation != SaveContinuation.NONE) {
            throw new IllegalArgumentException("Save continuation requires a save modal");
        }
        if (saveContinuation == SaveContinuation.CLOSE_TAB && pendingCloseTabIndex < 0) {
            throw new IllegalArgumentException("Close-tab continuation requires a tab index");
        }
        if (saveContinuation != SaveContinuation.CLOSE_TAB
            && mode != UiModalMode.CLOSE_TAB_CONFIRM
            && pendingCloseTabIndex != -1) {
            throw new IllegalArgumentException("Tab index requires a close-tab flow");
        }
    }

    public static UiModalState none() {
        return state(UiModalMode.NONE, null, -1, SaveContinuation.NONE);
    }

    public static UiModalState saveAs() {
        return state(UiModalMode.SAVE_AS, null, -1, SaveContinuation.NONE);
    }

    public static UiModalState saveAsForQuit() {
        return state(UiModalMode.SAVE_AS, null, -1, SaveContinuation.QUIT);
    }

    public static UiModalState saveAsForCloseTab(int tabIndex) {
        return state(UiModalMode.SAVE_AS, null, tabIndex, SaveContinuation.CLOSE_TAB);
    }

    public static UiModalState recoveryConfirm() {
        return state(UiModalMode.RECOVERY_CONFIRM, null, -1, SaveContinuation.NONE);
    }

    public static UiModalState quitConfirm() {
        return state(UiModalMode.QUIT_CONFIRM, null, -1, SaveContinuation.NONE);
    }

    public static UiModalState closeTabConfirm(int tabIndex) {
        return state(UiModalMode.CLOSE_TAB_CONFIRM, null, tabIndex, SaveContinuation.NONE);
    }

    public static UiModalState helpHints() {
        return state(UiModalMode.HELP_HINTS, null, -1, SaveContinuation.NONE);
    }

    public static UiModalState helpLicenses() {
        return state(UiModalMode.HELP_LICENSES, null, -1, SaveContinuation.NONE);
    }

    public static UiModalState helpAuthor() {
        return state(UiModalMode.HELP_AUTHOR, null, -1, SaveContinuation.NONE);
    }

    public boolean isNone() {
        return mode == UiModalMode.NONE;
    }

    public boolean isSaveAs() {
        return mode == UiModalMode.SAVE_AS;
    }

    public boolean isSaveOverwriteConfirm() {
        return mode == UiModalMode.SAVE_OVERWRITE_CONFIRM;
    }

    public boolean isRecoveryConfirm() {
        return mode == UiModalMode.RECOVERY_CONFIRM;
    }

    public boolean isQuitConfirm() {
        return mode == UiModalMode.QUIT_CONFIRM;
    }

    public boolean isCloseTabConfirm() {
        return mode == UiModalMode.CLOSE_TAB_CONFIRM;
    }

    public boolean isHelpHints() {
        return mode == UiModalMode.HELP_HINTS;
    }

    public boolean isHelpLicenses() {
        return mode == UiModalMode.HELP_LICENSES;
    }

    public boolean isHelpAuthor() {
        return mode == UiModalMode.HELP_AUTHOR;
    }

    public UiModalState confirmOverwrite(Path path) {
        if (!isSaveAs()) {
            throw new IllegalStateException("Overwrite confirmation can only follow Save As");
        }
        return state(
            UiModalMode.SAVE_OVERWRITE_CONFIRM,
            path,
            pendingCloseTabIndex,
            saveContinuation
        );
    }

    public UiModalState backToSaveAs() {
        if (!isSaveOverwriteConfirm()) {
            throw new IllegalStateException("Only overwrite confirmation can return to Save As");
        }
        return state(UiModalMode.SAVE_AS, null, pendingCloseTabIndex, saveContinuation);
    }

    private static UiModalState state(
        UiModalMode mode,
        Path overwritePath,
        int closeTabIndex,
        SaveContinuation continuation
    ) {
        return new UiModalState(
            mode,
            overwritePath,
            closeTabIndex,
            continuation
        );
    }
}
