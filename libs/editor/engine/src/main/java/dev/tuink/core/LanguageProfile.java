package dev.tuink.core;

import java.util.Set;

/**
 * Editing behavior profile selected by file type.
 */
public enum LanguageProfile {
    PLAIN_TEXT(true, ColonIndentMode.NONE, Set.of(), false, false),
    GENERIC_CODE(true, ColonIndentMode.NONE, Set.of("else", "catch", "finally", "case", "default"), false, false),
    PYTHON(true, ColonIndentMode.PYTHON, Set.of("elif", "else", "except", "finally", "case"), false, false),
    YAML(false, ColonIndentMode.YAML, Set.of(), true, false),
    MARKDOWN(false, ColonIndentMode.NONE, Set.of(), false, true),
    STRUCTURED_TEXT(true, ColonIndentMode.NONE, Set.of(), false, false);

    public enum ColonIndentMode {
        NONE,
        PYTHON,
        YAML
    }

    private final boolean bracketIndentEnabled;
    private final ColonIndentMode colonIndentMode;
    private final Set<String> dedentKeywords;
    private final boolean yamlAutoListEnabled;
    private final boolean markdownAutoListEnabled;

    LanguageProfile(
        boolean bracketIndentEnabled,
        ColonIndentMode colonIndentMode,
        Set<String> dedentKeywords,
        boolean yamlAutoListEnabled,
        boolean markdownAutoListEnabled
    ) {
        this.bracketIndentEnabled = bracketIndentEnabled;
        this.colonIndentMode = colonIndentMode;
        this.dedentKeywords = dedentKeywords;
        this.yamlAutoListEnabled = yamlAutoListEnabled;
        this.markdownAutoListEnabled = markdownAutoListEnabled;
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

    public boolean yamlAutoListEnabled() {
        return yamlAutoListEnabled;
    }

    public boolean markdownAutoListEnabled() {
        return markdownAutoListEnabled;
    }
}
