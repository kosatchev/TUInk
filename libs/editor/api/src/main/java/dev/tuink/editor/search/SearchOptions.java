package dev.tuink.editor.search;

/**
 * Options for search/replace behavior.
 *
 * <p>Case-insensitive matching compares code points after
 * {@code Character.toLowerCase(Character.toUpperCase(codePoint))}. It does not
 * normalize canonical equivalents, apply locale-specific rules, or expand one
 * code point into several; ranges still address the original UTF-16 text.</p>
 * <p>Whole-word matching is an independent Unicode boundary filter. Regex mode
 * uses Java Pattern syntax on one logical line, with Unicode-aware case folding.</p>
 */
public record SearchOptions(boolean caseInsensitive, MatchMode matchMode, boolean wholeWord) {
    public SearchOptions(boolean caseInsensitive, MatchMode matchMode) {
        this(caseInsensitive, matchMode, false);
    }
    public static final SearchOptions DEFAULT = new SearchOptions(false, MatchMode.SUBSTRING);

    public enum MatchMode {
        SUBSTRING,
        WILDCARD,
        REGEX;

        public MatchMode next() { return values()[(ordinal() + 1) % values().length]; }

        public String label() {
            return switch (this) { case SUBSTRING -> "Strict"; case WILDCARD -> "Wildcard"; case REGEX -> "Regex"; };
        }
    }
}
