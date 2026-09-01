package dev.tuink.tui;

import dev.tuink.core.SearchOptions;
import java.util.Objects;

/** Caches the application search-summary count across cursor-only frames. */
final class SearchMatchCountCache {
    private String query;
    private SearchOptions options;
    private boolean allTabs;
    private long sourceIdentity;
    private long sourceRevision;
    private int count;
    private boolean valid;

    /** Returns the cached/non-active value, or {@code -1} when it must be recomputed. */
    int lookup(
        boolean searchActive,
        String query,
        SearchOptions options,
        boolean allTabs,
        long sourceIdentity,
        long sourceRevision
    ) {
        if (!searchActive || query == null || query.isEmpty()) {
            return 0;
        }
        SearchOptions safeOptions = options == null ? SearchOptions.DEFAULT : options;
        if (valid
            && this.query.equals(query)
            && this.options.equals(safeOptions)
            && this.allTabs == allTabs
            && this.sourceIdentity == sourceIdentity
            && this.sourceRevision == sourceRevision) {
            return count;
        }
        return -1;
    }

    void store(
        String query,
        SearchOptions options,
        boolean allTabs,
        long sourceIdentity,
        long sourceRevision,
        int count
    ) {
        this.query = Objects.requireNonNull(query, "query");
        this.options = options == null ? SearchOptions.DEFAULT : options;
        this.allTabs = allTabs;
        this.sourceIdentity = sourceIdentity;
        this.sourceRevision = sourceRevision;
        this.count = Math.max(0, count);
        valid = true;
    }
}
