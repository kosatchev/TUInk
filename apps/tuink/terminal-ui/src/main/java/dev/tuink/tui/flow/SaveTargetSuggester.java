package dev.tuink.tui.flow;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Predicate;

public final class SaveTargetSuggester {
    private SaveTargetSuggester() {
    }

    public static Path firstAvailable(Path preferred, Predicate<Path> pathExists) {
        Objects.requireNonNull(preferred, "Preferred save target is required");
        Objects.requireNonNull(pathExists, "Path-existence check is required");
        if (!pathExists.test(preferred)) {
            return preferred;
        }

        Path fileNamePath = preferred.getFileName();
        if (fileNamePath == null) {
            return preferred;
        }
        String fileName = fileNamePath.toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        Path parent = preferred.getParent();

        for (int suffix = 1; suffix < Integer.MAX_VALUE; suffix++) {
            String candidateName = base + "-" + suffix + extension;
            Path candidate = parent == null ? Path.of(candidateName) : parent.resolve(candidateName);
            if (!pathExists.test(candidate)) {
                return candidate;
            }
        }
        return preferred;
    }
}
