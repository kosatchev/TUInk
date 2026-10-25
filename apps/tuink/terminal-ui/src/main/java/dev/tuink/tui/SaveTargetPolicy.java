package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.core.DocumentEncodingException;
import dev.tuink.core.EditorState;
import dev.tuink.tui.flow.SaveTargetSuggester;
import dev.tuink.tui.flow.SavePathInput;
import dev.tuink.tui.ui.PathPresentation;
import java.io.IOException;
import java.nio.file.Files;
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
        String msg = e.getMessage() == null ? "Unknown I/O error" : e.getMessage();
        String lower = msg.toLowerCase();
        if (lower.contains("permission denied") || lower.contains("access denied")) {
            return "Save failed: permission denied";
        }
        if (lower.contains("no space") || lower.contains("not enough space")) {
            return "Save failed: no space left on device";
        }
        if (lower.contains("is a directory")) {
            return "Save failed: target is a directory";
        }
        return "Save failed: " + msg;
    }

    static String validateSaveTarget(Path path) {
        Path absolute = path.toAbsolutePath();
        Path leaf = absolute.getFileName();
        if (leaf == null || leaf.toString().equals(".") || leaf.toString().equals("..")) {
            return "Save failed: target is a directory: " + absolute;
        }
        Path target;
        try {
            target = Files.isSymbolicLink(absolute) ? absolute.toRealPath()
                : absolute.getParent().toRealPath().resolve(leaf);
        } catch (IOException error) {
            return "Save failed: cannot resolve target path: " + absolute + ": " + error.getMessage();
        }
        Path parent = target.getParent();
        if (parent != null) {
            if (!Files.exists(parent)) {
                return "Save failed: directory does not exist: " + parent;
            }
            if (!Files.isDirectory(parent)) {
                return "Save failed: parent path is not a directory: " + parent;
            }
            if (!Files.isWritable(parent)) {
                return "Save failed: no write access to directory: " + parent;
            }
        }
        if (Files.isDirectory(target)) {
            return "Save failed: target is a directory: " + target;
        }
        if (Files.exists(target) && !Files.isRegularFile(target)) {
            return "Save failed: target is not a regular file: " + target;
        }
        return null;
    }

    static boolean isReadOnlyFile(Path path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.exists(path) && !Files.isWritable(path);
        } catch (SecurityException e) {
            return true;
        }
    }

    static String suggestWritableName(Path path, Predicate<Path> occupied) {
        Path preferred = path.getFileName() == null ? Path.of(DEFAULT_FILE_NAME) : path;
        return PathPresentation.full(SaveTargetSuggester.firstAvailable(preferred, "-copy", occupied));
    }

}
