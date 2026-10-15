package dev.tuink.tui;

import dev.tuink.core.SaveDisposition;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Path;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

final class SaveFlowController {
    private SaveFlowController() {
    }

    static SaveAsResult handleSaveAsInput(
        TuinkCommand command,
        UiModalState modalState,
        String saveAsText,
        Path currentFile,
        SaveDisposition currentSaveDisposition,
        Function<Path, String> validateSaveTarget,
        Predicate<Path> pathExists,
        Predicate<Path> isReadOnlyFile,
        BiPredicate<Path, Path> isSameFilePath,
        Function<Path, String> suggestWritableName
    ) {
        if (!modalState.isSaveAs()) {
            return result(false, modalState, TextInputEffect.none(), false, null, null);
        }
        if (command == null) {
            return result(true, modalState, TextInputEffect.none(), false, null, null);
        }
        return switch (command) {
            case EDIT_INSERT_NEWLINE -> handleEnter(
                modalState,
                saveAsText,
                currentFile,
                currentSaveDisposition,
                validateSaveTarget,
                pathExists,
                isReadOnlyFile,
                isSameFilePath,
                suggestWritableName
            );
            case UI_ESCAPE, APP_QUIT -> result(
                true,
                UiModalState.none(),
                TextInputEffect.clear(),
                true,
                null,
                null
            );
            default -> result(true, modalState, TextInputEffect.none(), false, null, null);
        };
    }

    private static SaveAsResult handleEnter(
        UiModalState modalState,
        String saveAsText,
        Path currentFile,
        SaveDisposition currentSaveDisposition,
        Function<Path, String> validateSaveTarget,
        Predicate<Path> pathExists,
        Predicate<Path> isReadOnlyFile,
        BiPredicate<Path, Path> isSameFilePath,
        Function<Path, String> suggestWritableName
    ) {
        String target = saveAsText;
        if (target.isEmpty()) {
            return result(true, modalState, TextInputEffect.none(), false, "Save failed: file name is empty", null);
        }

        final Path path;
        try {
            path = Path.of(target);
        } catch (RuntimeException e) {
            return result(
                true,
                modalState,
                TextInputEffect.none(),
                false,
                "Save failed: invalid target path",
                null
            );
        }
        String validation = validateSaveTarget.apply(path);
        if (validation != null) {
            return result(true, modalState, TextInputEffect.none(), false, validation, null);
        }
        if (isReadOnlyFile.test(path)) {
            return result(
                true,
                modalState,
                TextInputEffect.setText(suggestWritableName.apply(path)),
                false,
                "Save failed: target is read-only: " + path + ". Choose another name.",
                null
            );
        }
        boolean targetExists = pathExists.test(path);
        boolean sameFile = targetExists
            && currentSaveDisposition == SaveDisposition.REPLACE_EXISTING
            && isSameFilePath.test(path, currentFile);
        if (targetExists && !sameFile) {
            return result(
                true,
                modalState.confirmOverwrite(path),
                TextInputEffect.none(),
                true,
                null,
                null
            );
        }
        return result(
            true,
            modalState,
            TextInputEffect.none(),
            false,
            null,
            path,
            targetExists ? SaveDisposition.REPLACE_EXISTING : SaveDisposition.CREATE_NEW
        );
    }

    private static SaveAsResult result(
        boolean consumed,
        UiModalState modalState,
        TextInputEffect textInputEffect,
        boolean clearNotification,
        String errorMessage,
        Path saveNowPath
    ) {
        return result(
            consumed,
            modalState,
            textInputEffect,
            clearNotification,
            errorMessage,
            saveNowPath,
            saveNowPath == null ? null : SaveDisposition.CREATE_NEW
        );
    }

    private static SaveAsResult result(
        boolean consumed,
        UiModalState modalState,
        TextInputEffect textInputEffect,
        boolean clearNotification,
        String errorMessage,
        Path saveNowPath,
        SaveDisposition saveDisposition
    ) {
        return new SaveAsResult(
            consumed,
            modalState,
            textInputEffect,
            clearNotification,
            errorMessage,
            saveNowPath,
            saveDisposition
        );
    }

    record SaveAsResult(
        boolean consumed,
        UiModalState modalState,
        TextInputEffect textInputEffect,
        boolean clearNotification,
        String errorMessage,
        Path saveNowPath,
        SaveDisposition saveDisposition
    ) {
    }

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
