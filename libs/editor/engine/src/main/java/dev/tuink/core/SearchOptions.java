package dev.tuink.core;

/**
 * Options for search/replace behavior.
 *
 * <p>Case-insensitive matching uses Java's Unicode simple case matching. It
 * does not normalize canonical equivalents or perform locale-specific/full
 * case folding; every reported range still addresses the original UTF-16 text.</p>
 */
public record SearchOptions(boolean caseInsensitive, MatchMode matchMode) {
    public static final SearchOptions DEFAULT = new SearchOptions(false, MatchMode.SUBSTRING);

    public enum MatchMode {
        SUBSTRING,
        WHOLE_WORD,
        WILDCARD
    }
}
