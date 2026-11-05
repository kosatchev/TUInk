package dev.tuink.tui;

import dev.tuink.tui.style.UiStyles;
import java.util.Objects;

/** Effective document decorations, separate from saved preferences and the focused UI recipient. */
final class PresentationSyntaxPolicy {
    record Source(boolean available, boolean enabled, boolean codeLanguages,
        boolean bracketMatching, boolean rainbowBrackets) {}
    record Preview(boolean color) {}

    private PresentationSyntaxPolicy() {}

    static Source source(UiSessionState preferences, UiStyles.Theme theme) {
        return source(preferences.syntaxHighlightingSetting, preferences.markdownCodeHighlightingSetting,
            preferences.rainbowBracketsSetting, theme);
    }

    static Source source(boolean syntax, boolean codeLanguages, boolean rainbowBrackets, UiStyles.Theme theme) {
        Objects.requireNonNull(theme);
        // The language provider is configured independently of the Source master.
        // Switching the master therefore does not rebuild lexical state.
        return new Source(true, syntax, codeLanguages, true, rainbowBrackets);
    }

    static Preview preview(boolean terminalColors, UiStyles.Theme theme) {
        Objects.requireNonNull(theme);
        return new Preview(terminalColors);
    }
}
