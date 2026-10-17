package dev.tuink.core;

/** A search stopped before producing a complete result because its work limit was reached. */
public final class SearchLimitException extends RuntimeException {
    SearchLimitException() {
        super("Search is too complex; shorten the wildcard query or use literal search");
    }
}
