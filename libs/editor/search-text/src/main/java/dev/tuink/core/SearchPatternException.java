package dev.tuink.core;

/** Invalid input reported to every search frontend without exposing a regex implementation type. */
public final class SearchPatternException extends SearchException {
    public SearchPatternException(String message) { super(message, "Invalid regular expression"); }
}
