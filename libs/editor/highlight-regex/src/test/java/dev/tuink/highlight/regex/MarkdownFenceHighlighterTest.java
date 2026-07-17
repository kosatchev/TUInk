package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.render.Color;
import dev.tuink.render.ContextualLineHighlighter;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownFenceHighlighterTest {
    private static final Style KEYWORD = new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false);
    private static final Style TYPE = Style.fg(Color.BRIGHT_MAGENTA);
    private static final Style NUMBER = Style.fg(Color.BRIGHT_CYAN);
    private static final Style COMMENT = Style.fg(Color.BRIGHT_BLACK);
    private static final Style MARKUP = new Style(Color.BRIGHT_YELLOW, Color.DEFAULT, true, false);

    @Test
    void contextualHighlightingFindsFenceStateWhenLinesAreRequestedOutOfOrder() {
        TextModel model = model(
            "# Before",
            "```java",
            "public int value = 7; // return",
            "```",
            "# After"
        );
        ContextualLineHighlighter highlighter = markdownHighlighter();

        assertEquals(
            List.of(new StyleSpan(0, model.getLine(4).length(), MARKUP)),
            highlight(highlighter, model, 4)
        );

        String java = model.getLine(2);
        assertAll(
            () -> assertEquals(
                List.of(
                    span(java, "public", KEYWORD),
                    span(java, "int", TYPE),
                    span(java, "7", NUMBER),
                    new StyleSpan(java.indexOf("//"), java.length(), COMMENT)
                ),
                highlight(highlighter, model, 2)
            ),
            () -> assertEquals(List.of(), highlight(highlighter, model, 1)),
            () -> assertEquals(List.of(), highlight(highlighter, model, 3)),
            () -> assertEquals(List.of(), highlighter.highlight(java))
        );
    }

    @Test
    void languageTagsAreNormalizedBeforeSelectingEmbeddedHighlighters() {
        TextModel model = model(
            "```{.language-python}",
            "def first():",
            "```",
            "```LANG-TS,",
            "interface User { readonly id: number; }",
            "```",
            "```lexer:sql;",
            "select 1",
            "```"
        );
        ContextualLineHighlighter highlighter = markdownHighlighter();

        String typescript = model.getLine(4);
        String sql = model.getLine(7);
        assertAll(
            () -> assertEquals(
                List.of(span(model.getLine(1), "def", KEYWORD)),
                highlight(highlighter, model, 1)
            ),
            () -> assertEquals(
                List.of(
                    span(typescript, "interface", KEYWORD),
                    span(typescript, "readonly", KEYWORD),
                    span(typescript, "number", TYPE)
                ),
                highlight(highlighter, model, 4)
            ),
            () -> assertEquals(
                List.of(span(sql, "select", KEYWORD), span(sql, "1", NUMBER)),
                highlight(highlighter, model, 7)
            )
        );
    }

    @Test
    void closingFenceMustUseTheOpeningMarkerAndAtLeastItsLength() {
        TextModel model = model(
            "~~~~python",
            "def first():",
            "~~~",
            "def second():",
            "````",
            "def third():",
            "~~~~",
            "def outside():"
        );
        ContextualLineHighlighter highlighter = markdownHighlighter();

        assertAll(
            () -> assertEquals(
                List.of(span(model.getLine(1), "def", KEYWORD)),
                highlight(highlighter, model, 1)
            ),
            () -> assertEquals(
                List.of(span(model.getLine(3), "def", KEYWORD)),
                highlight(highlighter, model, 3)
            ),
            () -> assertEquals(
                List.of(span(model.getLine(5), "def", KEYWORD)),
                highlight(highlighter, model, 5)
            ),
            () -> assertEquals(List.of(), highlight(highlighter, model, 7))
        );
    }

    @Test
    void fourSpaceIndentedMarkerDoesNotOpenAFence() {
        TextModel model = model("    ```java", "public int value = 7;");
        ContextualLineHighlighter highlighter = markdownHighlighter();

        assertEquals(List.of(), highlight(highlighter, model, 1));
    }

    @Test
    void changedModelRevisionInvalidatesPreviouslyComputedFenceState() {
        TextModel model = model("```java", "public int value = 7;", "```");
        ContextualLineHighlighter highlighter = markdownHighlighter();

        assertEquals(
            List.of(
                span(model.getLine(1), "public", KEYWORD),
                span(model.getLine(1), "int", TYPE),
                span(model.getLine(1), "7", NUMBER)
            ),
            highlight(highlighter, model, 1)
        );

        model.replaceLines(List.of("plain", "public int value = 7;", "plain"));

        assertEquals(List.of(), highlight(highlighter, model, 1));
    }

    @Test
    void invalidContextFallsBackToPlainMarkdownHighlighting() {
        TextModel model = model("plain");
        ContextualLineHighlighter highlighter = markdownHighlighter();
        String heading = "# Heading";
        List<StyleSpan> expected = List.of(new StyleSpan(0, heading.length(), MARKUP));

        assertAll(
            () -> assertEquals(expected, highlighter.highlight(null, 0, heading)),
            () -> assertEquals(expected, highlighter.highlight(model, -1, heading)),
            () -> assertEquals(expected, highlighter.highlight(model, model.lineCount(), heading)),
            () -> assertEquals(List.of(), highlighter.highlight(model, 0, null))
        );
    }

    private static ContextualLineHighlighter markdownHighlighter() {
        return (ContextualLineHighlighter) RegexHighlighter.forFileName("README.md");
    }

    private static TextModel model(String... lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(lines));
        return model;
    }

    private static List<StyleSpan> highlight(
        ContextualLineHighlighter highlighter,
        TextModel model,
        int lineIndex
    ) {
        return highlighter.highlight(model, lineIndex, model.getLine(lineIndex));
    }

    private static StyleSpan span(String line, String token, Style style) {
        int start = line.indexOf(token);
        return new StyleSpan(start, start + token.length(), style);
    }
}
