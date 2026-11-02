package dev.tuink.markdown;

/** An explicit admission/processing failure, leaving the owning document untouched. */
public final class MarkdownParseException extends RuntimeException {
    public MarkdownParseException(String message) { super(message); }
    public MarkdownParseException(String message, Throwable cause) { super(message, cause); }
}
