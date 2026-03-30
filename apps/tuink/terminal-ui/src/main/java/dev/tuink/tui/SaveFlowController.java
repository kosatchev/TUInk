package dev.tuink.tui;

import java.nio.file.Path;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

final class SaveFlowController {
    private SaveFlowController() {
    }

    static SaveAsResult handleSaveAsInput(
        KeyEvent key,
        boolean saveAsMode,
        String saveAsText,
        boolean saveOverwriteConfirmMode,
        Path pendingOverwritePath,
        Path currentFile,
        Function<Path, String> validateSaveTarget,
        Predicate<Path> pathExists,
        Predicate<Path> isReadOnlyFile,
        BiPredicate<Path, Path> isSameFilePath,
        Function<Path, String> suggestWritableName
    ) {
        if (!saveAsMode) {
            return SaveAsResult.notConsumed(saveAsMode, saveOverwriteConfirmMode, pendingOverwritePath);
        }

        if (key instanceof KeyEvent.CharInput charInput) {
            return new SaveAsResult(
                true,
                true,
                saveOverwriteConfirmMode,
                pendingOverwritePath,
                TextInputEffect.insert(charInput.text()),
                true,
                null,
                null
            );
        }

        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
        switch (action) {
            case BACKSPACE -> {
                return new SaveAsResult(
                    true,
                    true,
                    saveOverwriteConfirmMode,
                    pendingOverwritePath,
                    TextInputEffect.backspace(),
                    true,
                    null,
                    null
                );
            }
            case ENTER -> {
                String target = saveAsText.trim();
                if (target.isEmpty()) {
                    return new SaveAsResult(
                        true,
                        false,
                        saveOverwriteConfirmMode,
                        pendingOverwritePath,
                        TextInputEffect.clear(),
                        true,
                        null,
                        null
                    );
                }

                Path path = Path.of(target);
                String validation = validateSaveTarget.apply(path);
                if (validation != null) {
                    return new SaveAsResult(
                        true,
                        true,
                        saveOverwriteConfirmMode,
                        pendingOverwritePath,
                        TextInputEffect.none(),
                        false,
                        validation,
                        null
                    );
                }
                if (isReadOnlyFile.test(path)) {
                    return new SaveAsResult(
                        true,
                        true,
                        saveOverwriteConfirmMode,
                        pendingOverwritePath,
                        TextInputEffect.setText(suggestWritableName.apply(path)),
                        false,
                        "Save failed: target is read-only: " + path + ". Choose another name.",
                        null
                    );
                }
                if (pathExists.test(path) && !isSameFilePath.test(path, currentFile)) {
                    return new SaveAsResult(true, false, true, path, TextInputEffect.none(), true, null, null);
                }
                return new SaveAsResult(
                    true,
                    false,
                    saveOverwriteConfirmMode,
                    pendingOverwritePath,
                    TextInputEffect.clear(),
                    false,
                    null,
                    path
                );
            }
            case ESCAPE, CTRL_Q -> {
                return new SaveAsResult(
                    true,
                    false,
                    saveOverwriteConfirmMode,
                    pendingOverwritePath,
                    TextInputEffect.clear(),
                    true,
                    null,
                    null
                );
            }
            case ARROW_LEFT -> {
                return new SaveAsResult(
                    true,
                    true,
                    saveOverwriteConfirmMode,
                    pendingOverwritePath,
                    TextInputEffect.moveLeft(),
                    false,
                    null,
                    null
                );
            }
            case ARROW_RIGHT -> {
                return new SaveAsResult(
                    true,
                    true,
                    saveOverwriteConfirmMode,
                    pendingOverwritePath,
                    TextInputEffect.moveRight(),
                    false,
                    null,
                    null
                );
            }
            case HOME -> {
                return new SaveAsResult(
                    true,
                    true,
                    saveOverwriteConfirmMode,
                    pendingOverwritePath,
                    TextInputEffect.moveHome(),
                    false,
                    null,
                    null
                );
            }
            case END -> {
                return new SaveAsResult(
                    true,
                    true,
                    saveOverwriteConfirmMode,
                    pendingOverwritePath,
                    TextInputEffect.moveEnd(),
                    false,
                    null,
                    null
                );
            }
            case DELETE -> {
                return new SaveAsResult(
                    true,
                    true,
                    saveOverwriteConfirmMode,
                    pendingOverwritePath,
                    TextInputEffect.deleteForward(),
                    false,
                    null,
                    null
                );
            }
            default -> {
                // Ignore unsupported keys while Save As prompt is active.
            }
        }
        return new SaveAsResult(
            true,
            true,
            saveOverwriteConfirmMode,
            pendingOverwritePath,
            TextInputEffect.none(),
            false,
            null,
            null
        );
    }

    static SaveOverwriteResult handleOverwriteConfirm(
        KeyEvent key,
        boolean saveOverwriteConfirmMode,
        Path pendingOverwritePath
    ) {
        if (!saveOverwriteConfirmMode) {
            return SaveOverwriteResult.notConsumed(saveOverwriteConfirmMode, pendingOverwritePath);
        }

        if (key instanceof KeyEvent.CharInput charInput) {
            char ch = charInput.asciiCharOrZero();
            if (ch == 'y' || ch == 'Y') {
                return new SaveOverwriteResult(true, false, null, false, false, pendingOverwritePath);
            }
            if (ch == 'n' || ch == 'N') {
                return new SaveOverwriteResult(true, false, null, true, true, null);
            }
            return new SaveOverwriteResult(true, true, pendingOverwritePath, false, false, null);
        }

        KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
        switch (action) {
            case ESCAPE, CTRL_Q, ENTER -> {
                return new SaveOverwriteResult(true, false, null, true, true, null);
            }
            default -> {
                // Ignore unsupported keys in overwrite confirmation.
            }
        }
        return new SaveOverwriteResult(true, true, pendingOverwritePath, false, false, null);
    }

    record SaveAsResult(
        boolean consumed,
        boolean saveAsMode,
        boolean saveOverwriteConfirmMode,
        Path pendingOverwritePath,
        TextInputEffect textInputEffect,
        boolean clearNotification,
        String errorMessage,
        Path saveNowPath
    ) {
        static SaveAsResult notConsumed(boolean saveAsMode, boolean saveOverwriteConfirmMode, Path pendingOverwritePath) {
            return new SaveAsResult(
                false,
                saveAsMode,
                saveOverwriteConfirmMode,
                pendingOverwritePath,
                TextInputEffect.none(),
                false,
                null,
                null
            );
        }
    }

    record TextInputEffect(TextInputEffectKind kind, Character ch, String text) {
        static TextInputEffect none() {
            return new TextInputEffect(TextInputEffectKind.NONE, null, null);
        }

        static TextInputEffect insert(char ch) {
            return insert(String.valueOf(ch));
        }

        static TextInputEffect insert(String text) {
            String safe = text == null ? "" : text;
            Character legacyChar = safe.length() == 1 ? safe.charAt(0) : null;
            return new TextInputEffect(TextInputEffectKind.INSERT, legacyChar, safe);
        }

        static TextInputEffect setText(String text) {
            return new TextInputEffect(TextInputEffectKind.SET_TEXT, null, text);
        }

        static TextInputEffect backspace() {
            return new TextInputEffect(TextInputEffectKind.BACKSPACE, null, null);
        }

        static TextInputEffect moveLeft() {
            return new TextInputEffect(TextInputEffectKind.MOVE_LEFT, null, null);
        }

        static TextInputEffect moveRight() {
            return new TextInputEffect(TextInputEffectKind.MOVE_RIGHT, null, null);
        }

        static TextInputEffect moveHome() {
            return new TextInputEffect(TextInputEffectKind.MOVE_HOME, null, null);
        }

        static TextInputEffect moveEnd() {
            return new TextInputEffect(TextInputEffectKind.MOVE_END, null, null);
        }

        static TextInputEffect deleteForward() {
            return new TextInputEffect(TextInputEffectKind.DELETE_FORWARD, null, null);
        }

        static TextInputEffect clear() {
            return new TextInputEffect(TextInputEffectKind.CLEAR, null, null);
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

    record SaveOverwriteResult(
        boolean consumed,
        boolean saveOverwriteConfirmMode,
        Path pendingOverwritePath,
        boolean saveAsMode,
        boolean clearNotification,
        Path saveNowPath
    ) {
        static SaveOverwriteResult notConsumed(boolean saveOverwriteConfirmMode, Path pendingOverwritePath) {
            return new SaveOverwriteResult(false, saveOverwriteConfirmMode, pendingOverwritePath, false, false, null);
        }
    }
}
