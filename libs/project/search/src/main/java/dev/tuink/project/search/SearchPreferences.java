package dev.tuink.project.search;

import dev.tuink.editor.storage.DocumentOpenMode;
import java.util.Objects;

/** Defaults for new window searches; a query's temporary filters are separate. */
public record SearchPreferences(boolean automatic, boolean respectIgnore, boolean hidden,
                                String exclusions, DocumentOpenMode encoding, int maximumFileMiB,
                                int maximumMatches) {
    public SearchPreferences {
        Objects.requireNonNull(exclusions); Objects.requireNonNull(encoding);
        if (exclusions.length() > 4096 || exclusions.indexOf('\n') >= 0 || exclusions.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Exclusions must fit on one line (4096 characters)");
        if (maximumFileMiB < 1 || maximumFileMiB > 64)
            throw new IllegalArgumentException("Maximum file size must be 1–64 MiB");
        if (maximumMatches < 1 || maximumMatches > 100_000)
            throw new IllegalArgumentException("Maximum results must be 1–100000");
    }
    public static SearchPreferences defaults() {
        return new SearchPreferences(true, true, true, "", DocumentOpenMode.DETECT, 64, 10_000);
    }
}
