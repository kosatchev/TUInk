package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.storage.jvm.JvmFileAdvisory;
import dev.tuink.editor.storage.DocumentEncodingException;
import dev.tuink.core.EditorState;
import dev.tuiframe.explorer.file.FileFailureReason;
import dev.tuink.tui.flow.SaveTargetSuggester;
import dev.tuink.tui.flow.SavePathInput;
import dev.tuiframe.explorer.file.PathLabels;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Predicate;

/** UI target suggestions and actionable file-save messages; persistence remains in the engine. */
final class SaveTargetPolicy {
    static final String DEFAULT_FILE_NAME = "untitled.txt";

    private SaveTargetPolicy() {}

    static boolean requiresSaveAs(Path file) {
        return file == null || isReadOnlyFile(file);
    }

    static boolean isSuitableDraft(String draft) {
        try {
            Path path = SavePathInput.parse(draft);
            return validateSaveTarget(path) == null && !isReadOnlyFile(path);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static String savedMessage(EditorState state) {
        return "Saved " + dev.tuink.tui.ui.NotificationPresentation.path(JvmDocumentStorage.pathOf(state.getCurrentResource()))
            + " [" + state.getFileEncoding().displayName() + "]";
    }

    static String saveErrorMessage(IOException e) {
        if (e instanceof DocumentEncodingException encoding) {
            return "Save failed: " + encoding.charsetName() + " cannot encode U+"
                + String.format(Locale.ROOT, "%04X", encoding.codePoint())
                + ". Choose Save as UTF-8 to keep this text.";
        }
        FileFailureReason.Description reason = FileFailureReason.describe(e);
        return "Save failed: " + switch (reason.kind()) {
            case ACCESS_DENIED -> "permission denied";
            case NO_SPACE -> "no space left on device";
            case DIRECTORY -> "target is a directory";
            case MISSING, OTHER -> reason.detail() == null ? "Unknown I/O error" : reason.detail();
        };
    }

    static String validateSaveTarget(Path path) {
        JvmFileAdvisory.SaveIssue issue = JvmFileAdvisory.inspectSaveTarget(path);
        if (issue == null) return null;
        return "Save failed: " + switch (issue.kind()) {
            case TARGET_DIRECTORY -> "target is a directory: " + issue.path();
            case UNRESOLVED_PATH -> "cannot resolve target path: " + issue.path() + ": " + issue.detail();
            case MISSING_DIRECTORY -> "directory does not exist: " + issue.path();
            case PARENT_NOT_DIRECTORY -> "parent path is not a directory: " + issue.path();
            case DIRECTORY_NOT_WRITABLE -> "no write access to directory: " + issue.path();
            case TARGET_NOT_REGULAR -> "target is not a regular file: " + issue.path();
        };
    }

    static boolean isReadOnlyFile(Path path) {
        return JvmFileAdvisory.isReadOnlyFile(path);
    }

    static String suggestWritableName(Path path, Predicate<Path> occupied) {
        Path preferred = path.getFileName() == null ? Path.of(DEFAULT_FILE_NAME) : path;
        return PathLabels.full(SaveTargetSuggester.firstAvailable(preferred, "-copy", occupied));
    }

}
