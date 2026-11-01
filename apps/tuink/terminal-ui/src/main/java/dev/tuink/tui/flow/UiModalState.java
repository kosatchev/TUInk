package dev.tuink.tui.flow;

import dev.tuink.editor.document.ViewId;
import java.nio.file.Path;

public record UiModalState(
    UiModalMode mode,
    Path pendingOverwritePath,
    int pendingCloseTabIndex,
    SaveContinuation saveContinuation,
    DocumentSaveFormat saveFormat,
    Path pendingOpenPath,
    ViewId targetViewId
) {
    public UiModalState(UiModalMode mode, Path pendingOverwritePath, int pendingCloseTabIndex,
                        SaveContinuation saveContinuation, DocumentSaveFormat saveFormat, Path pendingOpenPath) {
        this(mode, pendingOverwritePath, pendingCloseTabIndex, saveContinuation, saveFormat, pendingOpenPath, null);
    }

    public UiModalState(UiModalMode mode, Path pendingOverwritePath, int pendingCloseTabIndex,
                        SaveContinuation saveContinuation) {
        this(mode, pendingOverwritePath, pendingCloseTabIndex, saveContinuation, DocumentSaveFormat.ORIGINAL, null);
    }

    public UiModalState {
        if (mode == null || saveContinuation == null || saveFormat == null) {
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
        boolean saveFlowMode = mode == UiModalMode.SAVE_AS || mode == UiModalMode.SAVE_OVERWRITE_CONFIRM
            || mode == UiModalMode.SAVE_UTF8_CONFIRM;
        if (!saveFlowMode && saveFormat != DocumentSaveFormat.ORIGINAL) {
            throw new IllegalArgumentException("Save format requires a save modal");
        }
        if ((mode == UiModalMode.OPEN_UTF16_CONFIRM) != (pendingOpenPath != null)) {
            throw new IllegalArgumentException("Explicit open confirmation requires only its requested path");
        }
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
        if (targetViewId != null && !saveFlowMode && mode != UiModalMode.CLOSE_TAB_CONFIRM
            && mode != UiModalMode.DOCUMENT_FORMAT) {
            throw new IllegalArgumentException("A view target requires a document save or close dialog");
        }
    }

    /** Binds a document dialog once; subsequent selection changes cannot redirect it. */
    public UiModalState bindTo(ViewId viewId) {
        if (targetViewId != null || !isSaveAs() && !isSaveOverwriteConfirm()
            && !isSaveUtf8Confirm() && !isCloseTabConfirm() && !isDocumentFormat()) return this;
        return new UiModalState(mode, pendingOverwritePath, pendingCloseTabIndex, saveContinuation,
            saveFormat, pendingOpenPath, java.util.Objects.requireNonNull(viewId, "viewId"));
    }

    /** Refreshes a close target's display index without changing its identity. */
    public UiModalState withCloseTabIndex(int tabIndex) {
        if (!isCloseTabConfirm() && saveContinuation != SaveContinuation.CLOSE_TAB) return this;
        if (tabIndex == pendingCloseTabIndex) return this;
        return new UiModalState(mode, pendingOverwritePath, tabIndex, saveContinuation,
            saveFormat, pendingOpenPath, targetViewId);
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

    public static UiModalState documentFormat() {
        return state(UiModalMode.DOCUMENT_FORMAT, null, -1, SaveContinuation.NONE);
    }

    public boolean isDocumentFormat() { return mode == UiModalMode.DOCUMENT_FORMAT; }

    public boolean isSaveAs() {
        return mode == UiModalMode.SAVE_AS;
    }

    public boolean isSaveOverwriteConfirm() {
        return mode == UiModalMode.SAVE_OVERWRITE_CONFIRM;
    }

    public boolean isSaveUtf8Confirm() {
        return mode == UiModalMode.SAVE_UTF8_CONFIRM;
    }

    public boolean isOpenUtf16Confirm() {
        return mode == UiModalMode.OPEN_UTF16_CONFIRM;
    }

    public static UiModalState openUtf16Confirm(Path path) {
        return new UiModalState(UiModalMode.OPEN_UTF16_CONFIRM, null, -1, SaveContinuation.NONE,
            DocumentSaveFormat.ORIGINAL, java.util.Objects.requireNonNull(path));
    }

    public UiModalState offerUtf8() {
        if (!isSaveAs() && !isSaveOverwriteConfirm()) {
            throw new IllegalStateException("Encoding choice requires a save flow");
        }
        return new UiModalState(UiModalMode.SAVE_UTF8_CONFIRM, null, pendingCloseTabIndex, saveContinuation,
            DocumentSaveFormat.ORIGINAL, null, targetViewId);
    }

    public UiModalState utf8SaveAs() {
        if (!isSaveUtf8Confirm()) {
            throw new IllegalStateException("UTF-8 Save As requires explicit confirmation");
        }
        return new UiModalState(UiModalMode.SAVE_AS, null, pendingCloseTabIndex, saveContinuation,
            DocumentSaveFormat.UTF8, null, targetViewId);
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
        return new UiModalState(
            UiModalMode.SAVE_OVERWRITE_CONFIRM,
            path,
            pendingCloseTabIndex,
            saveContinuation,
            saveFormat,
            null,
            targetViewId
        );
    }

    public UiModalState backToSaveAs() {
        if (!isSaveOverwriteConfirm()) {
            throw new IllegalStateException("Only overwrite confirmation can return to Save As");
        }
        return new UiModalState(UiModalMode.SAVE_AS, null, pendingCloseTabIndex, saveContinuation, saveFormat, null, targetViewId);
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
