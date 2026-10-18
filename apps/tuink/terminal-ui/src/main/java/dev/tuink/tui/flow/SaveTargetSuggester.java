package dev.tuink.tui.flow;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Predicate;

public final class SaveTargetSuggester {
    private SaveTargetSuggester() {
    }

    public static Path firstAvailable(Path preferred, Predicate<Path> occupied) {
        return firstAvailable(preferred, "", occupied);
    }

    /** Each collision reserves the complete suffix before shortening the original filename. */
    public static Path firstAvailable(Path preferred, String baseSuffix, Predicate<Path> occupied) {
        Objects.requireNonNull(preferred, "Preferred save target is required");
        Objects.requireNonNull(baseSuffix, "Save-name suffix is required");
        Objects.requireNonNull(occupied, "Save-target availability check is required");
        Path fileNamePath = preferred.getFileName();
        if (fileNamePath == null) {
            return preferred;
        }
        Path first = SavePathInput.withNameSuffix(preferred, baseSuffix);
        if (!occupied.test(first)) {
            return first;
        }
        for (int suffix = 1; suffix < Integer.MAX_VALUE; suffix++) {
            Path candidate = SavePathInput.withNameSuffix(preferred, baseSuffix + "-" + suffix);
            if (!occupied.test(candidate)) {
                return candidate;
            }
        }
        return first;
    }
}
