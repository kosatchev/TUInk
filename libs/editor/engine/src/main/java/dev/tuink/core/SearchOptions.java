package dev.tuink.core;

/**
 * Options for search/replace behavior.
 */
public record SearchOptions(boolean caseInsensitive, MatchMode matchMode) {
    public static final SearchOptions DEFAULT = new SearchOptions(false, MatchMode.SUBSTRING);

    public enum MatchMode {
        SUBSTRING,
        WHOLE_WORD,
        WILDCARD
    }
}
