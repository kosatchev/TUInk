package dev.tuiframe.explorer.file;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.util.Locale;
import java.util.Objects;

/** Pure failure description shared by file-operation presenters. */
public final class FileFailureReason {
    private FileFailureReason() {}

    public enum Kind { MISSING, ACCESS_DENIED, NO_SPACE, DIRECTORY, OTHER }

    /** A path may be attached only when the detail itself contains no file address. */
    public record Description(Kind kind, String detail, boolean canAttachPath) {}

    public static Description describe(Exception failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure instanceof AccessDeniedException) {
            return new Description(Kind.ACCESS_DENIED, "permission denied", true);
        }
        if (failure instanceof NoSuchFileException) {
            return new Description(Kind.MISSING, "file no longer exists", true);
        }
        if (failure instanceof FileSystemException filesystem) {
            String reason = filesystem.getReason();
            if (reason != null && !reason.isBlank()) {
                Kind kind = failure instanceof NotDirectoryException ? Kind.OTHER : classify(reason);
                return new Description(kind, reason, true);
            }
            // getMessage() includes filenames. Never classify its substrings as a reason.
            return new Description(Kind.OTHER, filesystem.getMessage(), false);
        }
        String detail = failure.getMessage();
        return new Description(failure instanceof IOException ? classify(detail) : Kind.OTHER, detail, false);
    }

    private static Kind classify(String reason) {
        if (reason == null) return Kind.OTHER;
        return switch (reason.trim().toLowerCase(Locale.ROOT)) {
            case "permission denied", "access denied" -> Kind.ACCESS_DENIED;
            case "no space", "no space left on device", "not enough space" -> Kind.NO_SPACE;
            case "is a directory" -> Kind.DIRECTORY;
            default -> Kind.OTHER;
        };
    }
}
