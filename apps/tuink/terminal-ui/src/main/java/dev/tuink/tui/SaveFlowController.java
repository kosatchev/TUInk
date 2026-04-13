package dev.tuink.tui;

import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.input.KeyEvent;
import java.nio.file.Path;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

final class SaveFlowController {
    private SaveFlowController() {
    }

    static SaveAsResult handleSaveAsInput(
        KeyEvent key,
        UiModalState modalState,
        String saveAsText,
        Path currentFile,
        Function<Path, String> validateSaveTarget,
        Predicate<Path> pathExists,
        Predicate<Path> isReadOnlyFile,
        BiPredicate<Path, Path> isSameFilePath,
        Function<Path, String> suggestWritableName
    ) {
        if (!modalState.isSaveAs()) {
            return result(false, modalState, TextInputEffect.none(), false, null, null);
        }
        if (key instanceof KeyEvent.CharInput charInput) {
            return result(true, modalState, TextInputEffect.insert(charInput.text()), true, null, null);
        }

        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
        return switch (action) {
            case BACKSPACE -> result(true, modalState, TextInputEffect.backspace(), true, null, null);
            case ENTER -> handleEnter(
                saveAsText,
                currentFile,
                validateSaveTarget,
                pathExists,
                isReadOnlyFile,
                isSameFilePath,
                suggestWritableName
            );
            case ESCAPE, CTRL_Q -> result(
                true,
                UiModalState.none(),
                TextInputEffect.clear(),
                true,
                null,
                null
            );
            case ARROW_LEFT -> result(true, modalState, TextInputEffect.moveLeft(), false, null, null);
            case ARROW_RIGHT -> result(true, modalState, TextInputEffect.moveRight(), false, null, null);
            case HOME -> result(true, modalState, TextInputEffect.moveHome(), false, null, null);
            case END -> result(true, modalState, TextInputEffect.moveEnd(), false, null, null);
            case DELETE -> result(true, modalState, TextInputEffect.deleteForward(), false, null, null);
            default -> result(true, modalState, TextInputEffect.none(), false, null, null);
        };
    }

    private static SaveAsResult handleEnter(
        String saveAsText,
        Path currentFile,
        Function<Path, String> validateSaveTarget,
        Predicate<Path> pathExists,
        Predicate<Path> isReadOnlyFile,
        BiPredicate<Path, Path> isSameFilePath,
        Function<Path, String> suggestWritableName
    ) {
        String target = saveAsText.trim();
        if (target.isEmpty()) {
            return result(true, UiModalState.none(), TextInputEffect.clear(), true, null, null);
        }

        final Path path;
        try {
            path = Path.of(target);
        } catch (RuntimeException e) {
            return result(
                true,
                UiModalState.saveAs(),
                TextInputEffect.none(),
                false,
                "Save failed: invalid target path",
                null
            );
        }
        String validation = validateSaveTarget.apply(path);
        if (validation != null) {
            return result(true, UiModalState.saveAs(), TextInputEffect.none(), false, validation, null);
        }
        if (isReadOnlyFile.test(path)) {
            return result(
                true,
                UiModalState.saveAs(),
                TextInputEffect.setText(suggestWritableName.apply(path)),
                false,
                "Save failed: target is read-only: " + path + ". Choose another name.",
                null
            );
        }
        if (pathExists.test(path) && !isSameFilePath.test(path, currentFile)) {
            return result(
                true,
                UiModalState.saveOverwriteConfirm(path),
                TextInputEffect.none(),
                true,
                null,
                null
            );
        }
        return result(true, UiModalState.none(), TextInputEffect.clear(), false, null, path);
    }

    private static SaveAsResult result(
        boolean consumed,
        UiModalState modalState,
        TextInputEffect textInputEffect,
        boolean clearNotification,
        String errorMessage,
        Path saveNowPath
    ) {
        return new SaveAsResult(
            consumed,
            modalState,
            textInputEffect,
            clearNotification,
            errorMessage,
            saveNowPath
        );
    }

    record SaveAsResult(
        boolean consumed,
        UiModalState modalState,
        TextInputEffect textInputEffect,
        boolean clearNotification,
        String errorMessage,
        Path saveNowPath
    ) {
    }

    record TextInputEffect(TextInputEffectKind kind, String text) {
        static TextInputEffect none() {
            return new TextInputEffect(TextInputEffectKind.NONE, null);
        }

        static TextInputEffect insert(String text) {
            return new TextInputEffect(TextInputEffectKind.INSERT, text == null ? "" : text);
        }

        static TextInputEffect setText(String text) {
            return new TextInputEffect(TextInputEffectKind.SET_TEXT, text);
        }

        static TextInputEffect backspace() {
            return new TextInputEffect(TextInputEffectKind.BACKSPACE, null);
        }

        static TextInputEffect moveLeft() {
            return new TextInputEffect(TextInputEffectKind.MOVE_LEFT, null);
        }

        static TextInputEffect moveRight() {
            return new TextInputEffect(TextInputEffectKind.MOVE_RIGHT, null);
        }

        static TextInputEffect moveHome() {
            return new TextInputEffect(TextInputEffectKind.MOVE_HOME, null);
        }

        static TextInputEffect moveEnd() {
            return new TextInputEffect(TextInputEffectKind.MOVE_END, null);
        }

        static TextInputEffect deleteForward() {
            return new TextInputEffect(TextInputEffectKind.DELETE_FORWARD, null);
        }

        static TextInputEffect clear() {
            return new TextInputEffect(TextInputEffectKind.CLEAR, null);
        }
    }

    enum TextInputEffectKind {
        NONE,
        INSERT,
        SET_TEXT,
        BACKSPACE,
        MOVE_LEFT,
        MOVE_RIGHT,
        MOVE_HOME,
        MOVE_END,
        DELETE_FORWARD,
        CLEAR
    }
}
