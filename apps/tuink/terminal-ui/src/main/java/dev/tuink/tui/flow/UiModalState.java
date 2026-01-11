package dev.tuink.tui.flow;

import java.nio.file.Path;

public record UiModalState(
    UiModalMode mode,
    Path pendingOverwritePath,
    int pendingCloseTabIndex
) {
    public static UiModalState none() {
        return new UiModalState(UiModalMode.NONE, null, -1);
    }

    public static UiModalState saveAs() {
        return new UiModalState(UiModalMode.SAVE_AS, null, -1);
    }

    public static UiModalState saveOverwriteConfirm(Path path) {
        return new UiModalState(UiModalMode.SAVE_OVERWRITE_CONFIRM, path, -1);
    }

    public static UiModalState quitConfirm() {
        return new UiModalState(UiModalMode.QUIT_CONFIRM, null, -1);
    }

    public static UiModalState closeTabConfirm(int tabIndex) {
        return new UiModalState(UiModalMode.CLOSE_TAB_CONFIRM, null, tabIndex);
    }

    public static UiModalState helpHints() {
        return new UiModalState(UiModalMode.HELP_HINTS, null, -1);
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

    public boolean isQuitConfirm() {
        return mode == UiModalMode.QUIT_CONFIRM;
    }

    public boolean isCloseTabConfirm() {
        return mode == UiModalMode.CLOSE_TAB_CONFIRM;
    }

    public boolean isHelpHints() {
        return mode == UiModalMode.HELP_HINTS;
    }
}
