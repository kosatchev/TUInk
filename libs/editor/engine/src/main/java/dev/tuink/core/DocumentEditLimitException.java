package dev.tuink.core;

/** A bulk edit was rejected before mutation because its result cannot be built safely. */
public final class DocumentEditLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DocumentEditLimitException() {
        super("Replacement result is too large for available memory. Use less text, fewer matches or a shorter replacement.");
    }
}
