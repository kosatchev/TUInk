package dev.tuink.tui;

import dev.tuink.core.DocumentEncodingException;
import dev.tuink.core.EditorState;
import dev.tuink.tui.flow.SaveTargetSuggester;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** UI target suggestions and actionable file-save messages; persistence remains in the engine. */
final class SaveTargetPolicy {
    static final String DEFAULT_FILE_NAME = "untitled.txt";

    private SaveTargetPolicy() {}

    static boolean requiresSaveAs(Path file) {
        return file == null || isReadOnlyFile(file);
    }

    static boolean isSuitableDraft(String draft) {
        try {
            Path path = Path.of(draft);
            return validateSaveTarget(path) == null && !isReadOnlyFile(path);
        } catch (java.nio.file.InvalidPathException invalid) {
            return false;
        }
    }

    static String savedMessage(EditorState state) {
        return "Saved " + state.getCurrentFile() + " [" + state.getFileEncoding().displayName() + "]";
    }

    static String saveErrorMessage(IOException e) {
        if (e instanceof DocumentEncodingException encoding) {
            return "Save failed: " + encoding.charsetName() + " cannot encode U+"
                + String.format(Locale.ROOT, "%04X", encoding.codePoint())
                + ". Copy to a new UTF-8 tab.";
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
        Path normalized = path.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
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
        if (Files.isDirectory(normalized)) {
            return "Save failed: target is a directory: " + normalized;
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

    static String suggestWritableName(Path path) {
        String fileName = path.getFileName() == null ? DEFAULT_FILE_NAME : path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        String candidate = base + "-copy" + ext;
        Path parent = path.getParent();
        Path preferred = parent == null ? Path.of(candidate) : parent.resolve(candidate);
        return SaveTargetSuggester.firstAvailable(preferred, Files::exists).toString();
    }

    static boolean isSameFilePath(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (Exception e) {
            return a.equals(b);
        }
    }

}
