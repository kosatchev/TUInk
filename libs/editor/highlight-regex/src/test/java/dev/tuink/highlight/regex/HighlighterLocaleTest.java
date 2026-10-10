package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.parallel.Resources.LOCALE;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.render.Color;
import dev.tuink.render.ContextualLineHighlighter;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock(LOCALE)
class HighlighterLocaleTest {
    private static final Style KEYWORD = new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false);
    private static final Style TYPE = Style.fg(Color.BRIGHT_MAGENTA);

    @Test
    void fileNamesExtensionsAndMarkdownLanguagesAreIndependentOfTheDefaultLocale() {
        Locale original = Locale.getDefault();
        Locale originalDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale originalFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            String config = "enabled=true";
            List<StyleSpan> ini = List.of(new StyleSpan(0, 8, KEYWORD), new StyleSpan(8, 12, TYPE));
            assertEquals(ini, RegexHighlighter.forFileName("SETTINGS.INI").highlight(config));
            assertEquals(ini, RegexHighlighter.forConfig("INI").highlight(config));
            assertEquals(ini, RegexHighlighter.forConfigFileName(".EDITORCONFIG").highlight(config));
            assertEquals(List.of(new StyleSpan(0, 4, KEYWORD)),
                RegexHighlighter.forFileName("DOCKERFILE").highlight("FROM base"));
            assertEquals(List.of(new StyleSpan(0, 4, KEYWORD)),
                RegexHighlighter.forConfigFileName("DOCKERFILE").highlight("FROM base"));

            DocumentSnapshot document = new DocumentSnapshot(new DocumentId("turkish-locale"), 1,
                List.of("```LANGUAGE-INI", config, "```"));
            ContextualLineHighlighter markdown = (ContextualLineHighlighter) RegexHighlighter.forFileName("README.MD");
            assertEquals(ini, markdown.highlight(document, 1, config));
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplay);
            Locale.setDefault(Locale.Category.FORMAT, originalFormat);
        }
    }
}
