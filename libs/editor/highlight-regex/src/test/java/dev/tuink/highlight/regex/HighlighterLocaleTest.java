package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.parallel.Resources.LOCALE;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.TokenSpan;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock(LOCALE)
class HighlighterLocaleTest {
    private static final SemanticStyleKey KEYWORD = SemanticStyleKey.KEYWORD;
    private static final SemanticStyleKey TYPE = SemanticStyleKey.TYPE;

    @Test
    void fileNamesExtensionsAndMarkdownLanguagesAreIndependentOfTheDefaultLocale() {
        Locale original = Locale.getDefault();
        Locale originalDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale originalFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            String config = "enabled=true";
            List<TokenSpan> ini = List.of(new TokenSpan(0, 8, KEYWORD), new TokenSpan(8, 12, TYPE));
            assertEquals(ini, RegexHighlighter.forFileName("SETTINGS.INI").highlight(config));
            assertEquals(ini, RegexHighlighter.forConfig("INI").highlight(config));
            assertEquals(ini, RegexHighlighter.forConfigFileName(".EDITORCONFIG").highlight(config));
            assertEquals(List.of(new TokenSpan(0, 4, KEYWORD)),
                RegexHighlighter.forFileName("DOCKERFILE").highlight("FROM base"));
            assertEquals(List.of(new TokenSpan(0, 4, KEYWORD)),
                RegexHighlighter.forConfigFileName("DOCKERFILE").highlight("FROM base"));

            DocumentSnapshot document = new DocumentSnapshot(new DocumentId("turkish-locale"), 1,
                List.of("```LANGUAGE-INI", config, "```"));
            ContextualSyntaxHighlighter markdown = (ContextualSyntaxHighlighter) RegexHighlighter.forFileName("README.MD");
            assertEquals(ini, markdown.highlight(document, 1, config));
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplay);
            Locale.setDefault(Locale.Category.FORMAT, originalFormat);
        }
    }
}
