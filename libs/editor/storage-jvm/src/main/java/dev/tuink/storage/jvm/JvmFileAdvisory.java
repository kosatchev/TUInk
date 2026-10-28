package dev.tuink.storage.jvm;

import dev.tuink.editor.storage.ResourceBinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Filesystem observations for host open/save hints. These observations can become
 * stale immediately; they grant no write authorization and never replace target
 * inspection, exact approval or the storage provider's publication checks.
 */
public final class JvmFileAdvisory {
    private JvmFileAdvisory() {}

    /**
     * Matches the physical target retained by a loaded document, rather than a
     * symlink spelling that may now lead elsewhere. A named, not-yet-created
     * document can still match its own unresolved endpoint.
     */
    public static boolean matchesOpenResource(ResourceBinding binding, Path requested) {
        Objects.requireNonNull(binding, "binding");
        if (binding.resource() == null || requested == null) return false;
        Path current;
        FileBaseline baseline;
        try {
            current = JvmDocumentStorage.pathOf(binding.resource());
            baseline = JvmDocumentStorage.baselineOf(binding.version());
        } catch (IllegalArgumentException foreignProvider) {
            return false;
        }
        Path target = requested.toAbsolutePath();
        if (target.getFileName() == null) return false;
        Path namedTarget;
        try {
            namedTarget = target.getParent().toRealPath().resolve(target.getFileName());
        } catch (IOException | SecurityException unavailable) {
            namedTarget = target;
        }
        Path physical = baseline == null ? current : baseline.target();
        if (namedTarget.equals(current)
            && (baseline == null || physical.equals(current) && !Files.isSymbolicLink(current))) {
            return true;
        }
        try {
            return physical.equals(physical.toRealPath()) && Files.isSameFile(target, physical);
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }

    /** A broken symlink also occupies a proposed filename. */
    public static boolean exists(Path target) {
        return Files.exists(target, LinkOption.NOFOLLOW_LINKS);
    }

    public static boolean isReadOnlyFile(Path path) {
        if (path == null) return false;
        try {
            return Files.exists(path) && !Files.isWritable(path);
        } catch (SecurityException inaccessible) {
            return true;
        }
    }

    /** Returns a host-presented issue, or {@code null} when this observation permits offering a save. */
    public static SaveIssue inspectSaveTarget(Path path) {
        Path absolute = path.toAbsolutePath();
        Path leaf = absolute.getFileName();
        if (leaf == null || leaf.toString().equals(".") || leaf.toString().equals("..")) {
            return new SaveIssue(SaveIssueKind.TARGET_DIRECTORY, absolute, null);
        }
        Path target;
        try {
            target = Files.isSymbolicLink(absolute) ? absolute.toRealPath()
                : absolute.getParent().toRealPath().resolve(leaf);
        } catch (IOException error) {
            return new SaveIssue(SaveIssueKind.UNRESOLVED_PATH, absolute, error.getMessage());
        }
        Path parent = target.getParent();
        if (parent != null) {
            if (!Files.exists(parent)) return new SaveIssue(SaveIssueKind.MISSING_DIRECTORY, parent, null);
            if (!Files.isDirectory(parent)) return new SaveIssue(SaveIssueKind.PARENT_NOT_DIRECTORY, parent, null);
            if (!Files.isWritable(parent)) return new SaveIssue(SaveIssueKind.DIRECTORY_NOT_WRITABLE, parent, null);
        }
        if (Files.isDirectory(target)) return new SaveIssue(SaveIssueKind.TARGET_DIRECTORY, target, null);
        if (Files.exists(target) && !Files.isRegularFile(target)) {
            return new SaveIssue(SaveIssueKind.TARGET_NOT_REGULAR, target, null);
        }
        return null;
    }

    public enum SaveIssueKind {
        TARGET_DIRECTORY, UNRESOLVED_PATH, MISSING_DIRECTORY, PARENT_NOT_DIRECTORY,
        DIRECTORY_NOT_WRITABLE, TARGET_NOT_REGULAR
    }

    /** Provider observation only; localized/actionable wording belongs to the host. */
    public record SaveIssue(SaveIssueKind kind, Path path, String detail) {}
}
