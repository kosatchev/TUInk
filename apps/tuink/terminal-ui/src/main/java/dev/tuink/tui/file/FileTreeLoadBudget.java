package dev.tuink.tui.file;

import java.nio.file.Path;

/** One conservative allocation and metadata-work allowance for an entire expanded tree. */
final class FileTreeLoadBudget {
    static final Limits DEFAULT_LIMITS = new Limits(20_000, 32L * 1024L * 1024L, 100_000);

    private final Limits limits;
    private int entries;
    private long estimatedBytes;
    private int metadataEntries;

    FileTreeLoadBudget(Limits limits) {
        this.limits = limits;
    }

    void reserveEntry(Path path) {
        // Include scan records, attributes, rows, metadata and publication/presentation references.
        // This is an admission estimate, not a promise about the JVM's exact object layout.
        long bytes = 1024L + 8L * path.toString().length();
        if (entries >= limits.maxEntries()) {
            throw new LimitReachedException("tree entry limit reached (" + limits.maxEntries() + ")");
        }
        if (bytes > limits.maxEstimatedBytes() - estimatedBytes) {
            throw new LimitReachedException("tree memory budget reached");
        }
        entries++;
        estimatedBytes += bytes;
    }

    boolean inspectMetadataEntry() {
        if (metadataEntries >= limits.maxMetadataEntries()) {
            return false;
        }
        metadataEntries++;
        return true;
    }

    record Limits(int maxEntries, long maxEstimatedBytes, int maxMetadataEntries) {
        Limits {
            if (maxEntries < 1 || maxEstimatedBytes < 1L || maxMetadataEntries < 0) {
                throw new IllegalArgumentException("invalid file tree load limits");
            }
        }
    }

    static final class LimitReachedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private LimitReachedException(String reason) {
            super(reason, null, false, false);
        }
    }
}
