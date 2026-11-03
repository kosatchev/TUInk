package dev.tuink.core;

/** A search stopped before producing a complete result because a resource limit was reached. */
public final class SearchLimitException extends RuntimeException {
    private static final SearchLimitException MEMORY = new SearchLimitException(
        "Not enough memory to search this query; shorten the query", "Not enough memory to search");
    private final String summary;

    SearchLimitException() {
        super("Search is too complex; shorten the wildcard query or use literal search");
        summary = "Search is too complex";
    }

    private SearchLimitException(String message, String summary) {
        // Reusable even when the allocation itself failed. No stack or mutable
        // suppressed-exception list needs to be allocated while reporting it.
        super(message, null, false, false);
        this.summary = summary;
    }

    /** Complete short reason for constrained presentations; getMessage retains the guidance. */
    public String summary() { return summary; }

    static SearchLimitException memory() {
        return MEMORY;
    }
}
