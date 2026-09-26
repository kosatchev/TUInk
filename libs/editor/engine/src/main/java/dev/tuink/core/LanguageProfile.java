package dev.tuink.core;

import java.util.Set;

/**
 * Editing behavior profile selected by file type.
 */
public enum LanguageProfile {
    PLAIN_TEXT(true, ColonIndentMode.NONE, Set.of()),
    GENERIC_CODE(true, ColonIndentMode.NONE, Set.of("else", "catch", "finally", "case", "default")),
    PYTHON(true, ColonIndentMode.PYTHON, Set.of("elif", "else", "except", "finally", "case")),
    YAML(false, ColonIndentMode.YAML, Set.of()),
    MARKDOWN(false, ColonIndentMode.NONE, Set.of()),
    STRUCTURED_TEXT(true, ColonIndentMode.NONE, Set.of());

    public enum ColonIndentMode {
        NONE,
        PYTHON,
        YAML
    }

    private final boolean bracketIndentEnabled;
    private final ColonIndentMode colonIndentMode;
    private final Set<String> dedentKeywords;

    LanguageProfile(
        boolean bracketIndentEnabled,
        ColonIndentMode colonIndentMode,
        Set<String> dedentKeywords
    ) {
        this.bracketIndentEnabled = bracketIndentEnabled;
        this.colonIndentMode = colonIndentMode;
        this.dedentKeywords = dedentKeywords;
    }

    public boolean bracketIndentEnabled() {
        return bracketIndentEnabled;
    }

    public ColonIndentMode colonIndentMode() {
        return colonIndentMode;
    }

    public Set<String> dedentKeywords() {
        return dedentKeywords;
    }

    public boolean keywordDedentEnabled() {
        return !dedentKeywords.isEmpty();
    }
}
