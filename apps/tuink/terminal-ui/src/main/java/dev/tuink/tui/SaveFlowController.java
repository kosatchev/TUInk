package dev.tuink.tui;

import dev.tuink.tui.flow.SavePathInput;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Path;

/** Pure filename-input decisions; resource inspection belongs to the document session. */
final class SaveFlowController {
    private SaveFlowController() {}

    static SaveAsResult handleSaveAsInput(TuinkCommand command, UiModalState modal, String draft) {
        if (!modal.isSaveAs()) return result(false, modal, TextInputEffect.none(), false, null, null);
        if (command == TuinkCommand.UI_ESCAPE || command == TuinkCommand.APP_QUIT) {
            return result(true, UiModalState.none(), TextInputEffect.clear(), true, null, null);
        }
        if (command != TuinkCommand.EDIT_INSERT_NEWLINE) {
            return result(true, modal, TextInputEffect.none(), false, null, null);
        }
        if (draft.isEmpty()) return rejected(modal, "Save failed: file name is empty");
        final Path target;
        try {
            target = SavePathInput.parse(draft);
        } catch (IllegalArgumentException invalid) {
            return rejected(modal, "Save failed: invalid target path");
        }
        Path leaf = target.toAbsolutePath().getFileName();
        if (leaf == null || leaf.toString().equals(".") || leaf.toString().equals("..")) {
            return rejected(modal, "Save failed: target is a directory: " + target.toAbsolutePath());
        }
        return result(true, modal, TextInputEffect.none(), false, null, target);
    }

    private static SaveAsResult rejected(UiModalState modal, String error) {
        return result(true, modal, TextInputEffect.none(), false, error, null);
    }

    private static SaveAsResult result(boolean consumed, UiModalState modal, TextInputEffect filename,
                                       boolean clearNotice, String error, Path target) {
        return new SaveAsResult(consumed, modal, filename, clearNotice, error, target);
    }

    record SaveAsResult(boolean consumed, UiModalState modalState, TextInputEffect textInputEffect,
                        boolean clearNotification, String errorMessage, Path saveNowPath) {}

    record TextInputEffect(TextInputEffectKind kind, String text) {
        static TextInputEffect none() {
            return new TextInputEffect(TextInputEffectKind.NONE, null);
        }

        static TextInputEffect setText(String text) {
            return new TextInputEffect(TextInputEffectKind.SET_TEXT, text);
        }

        static TextInputEffect clear() {
            return new TextInputEffect(TextInputEffectKind.CLEAR, null);
        }
    }

    enum TextInputEffectKind {
        NONE,
        SET_TEXT,
        CLEAR
    }
}
