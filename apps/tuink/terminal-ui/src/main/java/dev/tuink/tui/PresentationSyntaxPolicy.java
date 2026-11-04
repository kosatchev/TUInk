package dev.tuink.tui;

import dev.tuink.tui.style.UiStyles;
import java.util.Objects;

/** Effective syntax decisions, separate from persisted preferences and the focused UI recipient. */
final class PresentationSyntaxPolicy {
    record Source(boolean available, boolean enabled, boolean codeLanguages) {}
    record Preview(boolean color) {}

    private PresentationSyntaxPolicy() {}

    static Source source(UiSessionState preferences, UiStyles.Theme theme) {
        return source(preferences.syntaxHighlightingSetting, preferences.markdownCodeHighlightingSetting, theme);
    }

    static Source source(boolean syntax, boolean codeLanguages, UiStyles.Theme theme) {
        boolean available = Objects.requireNonNull(theme) != UiStyles.Theme.LIGHT;
        // The language provider is configured independently of the Source master.
        // Switching the master therefore does not rebuild lexical state.
        return new Source(available, available && syntax, codeLanguages);
    }

    static Preview preview(boolean terminalColors) {
        // Preview's current language coloring is independent of Source's two preferences.
        return new Preview(terminalColors);
    }
}
