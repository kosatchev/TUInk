package dev.tuink.tui.flow;

import java.nio.file.Path;

public record UiModalState(
    UiModalMode mode,
    Path pendingOverwritePath,
    int pendingCloseTabIndex,
    ConfirmChoice confirmChoice
) {
    public static UiModalState none() {
        return new UiModalState(UiModalMode.NONE, null, -1, ConfirmChoice.CANCEL);
    }

    public static UiModalState saveAs() {
        return new UiModalState(UiModalMode.SAVE_AS, null, -1, ConfirmChoice.CANCEL);
    }

    public static UiModalState saveOverwriteConfirm(Path path) {
        return new UiModalState(UiModalMode.SAVE_OVERWRITE_CONFIRM, path, -1, ConfirmChoice.CANCEL);
    }

    public static UiModalState recoveryConfirm() {
        return new UiModalState(UiModalMode.RECOVERY_CONFIRM, null, -1, ConfirmChoice.CANCEL);
    }

    public static UiModalState quitConfirm() {
        return new UiModalState(UiModalMode.QUIT_CONFIRM, null, -1, ConfirmChoice.CANCEL);
    }

    public static UiModalState closeTabConfirm(int tabIndex) {
        return new UiModalState(UiModalMode.CLOSE_TAB_CONFIRM, null, tabIndex, ConfirmChoice.CANCEL);
    }

    public static UiModalState helpHints() {
        return new UiModalState(UiModalMode.HELP_HINTS, null, -1, ConfirmChoice.CANCEL);
    }

    public static UiModalState helpLicenses() {
        return new UiModalState(UiModalMode.HELP_LICENSES, null, -1, ConfirmChoice.CANCEL);
    }

    public static UiModalState helpAuthor() {
        return new UiModalState(UiModalMode.HELP_AUTHOR, null, -1, ConfirmChoice.CANCEL);
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

    public UiModalState withConfirmChoice(ConfirmChoice nextConfirmChoice) {
        return new UiModalState(
            mode,
            pendingOverwritePath,
            pendingCloseTabIndex,
            nextConfirmChoice == null ? ConfirmChoice.CANCEL : nextConfirmChoice
        );
    }
}
