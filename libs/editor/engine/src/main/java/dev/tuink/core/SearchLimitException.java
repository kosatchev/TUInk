package dev.tuink.core;

/** A search stopped before producing a complete result because a resource limit was reached. */
public final class SearchLimitException extends RuntimeException {
    private static final SearchLimitException MEMORY = new SearchLimitException(
        "Not enough memory to search this query; shorten the query");

    SearchLimitException() {
        super("Search is too complex; shorten the wildcard query or use literal search");
    }

    private SearchLimitException(String message) {
        // Reusable even when the allocation itself failed. No stack or mutable
        // suppressed-exception list needs to be allocated while reporting it.
        super(message, null, false, false);
    }

    static SearchLimitException memory() {
        return MEMORY;
    }
}
