package dev.tuink.storage.jvm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Filesystem entry addressing shared by JVM document, settings and advisory operations. */
public final class JvmFilePaths {
    private JvmFilePaths() {}

    /**
     * Resolves the existing parent in filesystem order, retaining the original
     * final component. Parent links are followed before subsequent dot segments.
     * Type checks, leaf-link following and parent creation belong to callers;
     * the leaf may be absent, a link, a directory or a dot component. Each call
     * observes the parent afresh and grants no publication authorization.
     */
    public static Path resolveEntry(Path requested) throws IOException {
        Path absolute = Objects.requireNonNull(requested, "path").toAbsolutePath();
        Path leaf = absolute.getFileName();
        Path parent = absolute.getParent();
        if (leaf == null || parent == null) {
            throw new IOException("Path has no parent directory entry: " + requested);
        }
        return parent.toRealPath().resolve(leaf);
    }
}
