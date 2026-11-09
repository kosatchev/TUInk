package dev.tuink.core;

/** A user-visible query or resource failure shared by all search frontends. */
public abstract class SearchException extends RuntimeException {
    private final String summary;
    protected SearchException(String message, String summary) { super(message); this.summary = summary; }
    protected SearchException(String message, String summary, boolean stack) {
        super(message, null, false, stack); this.summary = summary;
    }
    public final String summary() { return summary; }
}
