package dev.tuink.render;

import dev.tuink.editor.highlight.SemanticStyleKey;
import java.util.Map;

/** Maps portable syntax roles to one frontend's visual styles. Unknown roles use plain text. */
@FunctionalInterface
public interface SyntaxStyleResolver {
    Style resolve(SemanticStyleKey key);

    /** TUInk's existing syntax palette, independent of the provider's lexical semantics. */
    static SyntaxStyleResolver standard() {
        Map<SemanticStyleKey, Style> styles = Map.of(
            SemanticStyleKey.KEYWORD, new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false),
            SemanticStyleKey.TYPE, Style.fg(Color.BRIGHT_MAGENTA),
            SemanticStyleKey.NUMBER, Style.fg(Color.BRIGHT_CYAN),
            SemanticStyleKey.STRING, Style.fg(Color.BRIGHT_GREEN),
            SemanticStyleKey.COMMENT, Style.fg(Color.BRIGHT_BLACK),
            SemanticStyleKey.DECORATOR, Style.fg(Color.BRIGHT_YELLOW),
            SemanticStyleKey.MARKUP, new Style(Color.BRIGHT_YELLOW, Color.DEFAULT, true, false),
            SemanticStyleKey.LINK, Style.fg(Color.BRIGHT_YELLOW),
            SemanticStyleKey.QUOTE, Style.fg(Color.BRIGHT_BLACK));
        return key -> styles.getOrDefault(key, Style.DEFAULT);
    }
}
