package dev.tuink.core;

/** A search stopped before producing a complete result because a resource limit was reached. */
public final class SearchLimitException extends SearchException {
    private static final SearchLimitException MEMORY = new SearchLimitException(
        "Not enough memory to search this query; shorten the query", "Not enough memory to search");

    SearchLimitException() {
        super("Search is too complex; simplify the pattern or use strict search", "Search is too complex");
    }

    private SearchLimitException(String message, String summary) {
        // Reusable even when the allocation itself failed. No stack or mutable
        // suppressed-exception list needs to be allocated while reporting it.
        super(message, summary, false);
    }

    static SearchLimitException memory() {
        return MEMORY;
    }
}
