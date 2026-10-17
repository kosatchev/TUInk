package dev.tuink.core;

/**
 * Options for search/replace behavior.
 *
 * <p>Case-insensitive matching compares code points after
 * {@code Character.toLowerCase(Character.toUpperCase(codePoint))}. It does not
 * normalize canonical equivalents, apply locale-specific rules, or expand one
 * code point into several; ranges still address the original UTF-16 text.</p>
 */
public record SearchOptions(boolean caseInsensitive, MatchMode matchMode) {
    public static final SearchOptions DEFAULT = new SearchOptions(false, MatchMode.SUBSTRING);

    public enum MatchMode {
        SUBSTRING,
        WHOLE_WORD,
        WILDCARD
    }
}
