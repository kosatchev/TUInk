package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.render.Color;
import dev.tuink.render.ContextualLineHighlighter;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownFenceHighlighterTest {
    private static final DocumentId DOCUMENT_ID = new DocumentId("markdown-test");
    private static final Style KEYWORD = new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false);
    private static final Style TYPE = Style.fg(Color.BRIGHT_MAGENTA);
    private static final Style NUMBER = Style.fg(Color.BRIGHT_CYAN);
    private static final Style COMMENT = Style.fg(Color.BRIGHT_BLACK);
    private static final Style MARKUP = new Style(Color.BRIGHT_YELLOW, Color.DEFAULT, true, false);

    @Test
    void contextualHighlightingFindsFenceStateWhenLinesAreRequestedOutOfOrder() {
        DocumentSnapshot document = document(
            "# Before",
            "```java",
            "public int value = 7; // return",
            "```",
            "# After"
        );
        ContextualLineHighlighter highlighter = markdownHighlighter();

        assertEquals(
            List.of(new StyleSpan(0, document.line(4).length(), MARKUP)),
            highlight(highlighter, document, 4)
        );

        String java = document.line(2);
        assertAll(
            () -> assertEquals(
                List.of(
                    span(java, "public", KEYWORD),
                    span(java, "int", TYPE),
                    span(java, "7", NUMBER),
                    new StyleSpan(java.indexOf("//"), java.length(), COMMENT)
                ),
                highlight(highlighter, document, 2)
            ),
            () -> assertEquals(List.of(), highlight(highlighter, document, 1)),
            () -> assertEquals(List.of(), highlight(highlighter, document, 3)),
            () -> assertEquals(List.of(), highlighter.highlight(java))
        );
    }

    @Test
    void languageTagsAreNormalizedBeforeSelectingEmbeddedHighlighters() {
        DocumentSnapshot document = document(
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

        String typescript = document.line(4);
        String sql = document.line(7);
        assertAll(
            () -> assertEquals(
                List.of(span(document.line(1), "def", KEYWORD)),
                highlight(highlighter, document, 1)
            ),
            () -> assertEquals(
                List.of(
                    span(typescript, "interface", KEYWORD),
                    span(typescript, "readonly", KEYWORD),
                    span(typescript, "number", TYPE)
                ),
                highlight(highlighter, document, 4)
            ),
            () -> assertEquals(
                List.of(span(sql, "select", KEYWORD), span(sql, "1", NUMBER)),
                highlight(highlighter, document, 7)
            )
        );
    }

    @Test
    void closingFenceMustUseTheOpeningMarkerAndAtLeastItsLength() {
        DocumentSnapshot document = document(
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
                List.of(span(document.line(1), "def", KEYWORD)),
                highlight(highlighter, document, 1)
            ),
            () -> assertEquals(
                List.of(span(document.line(3), "def", KEYWORD)),
                highlight(highlighter, document, 3)
            ),
            () -> assertEquals(
                List.of(span(document.line(5), "def", KEYWORD)),
                highlight(highlighter, document, 5)
            ),
            () -> assertEquals(List.of(), highlight(highlighter, document, 7))
        );
    }

    @Test
    void fourSpaceIndentedMarkerDoesNotOpenAFence() {
        DocumentSnapshot document = document("    ```java", "public int value = 7;");
        ContextualLineHighlighter highlighter = markdownHighlighter();

        assertEquals(List.of(), highlight(highlighter, document, 1));
    }

    @Test
    void changedDocumentRevisionInvalidatesPreviouslyComputedFenceState() {
        DocumentSnapshot document = document("```java", "public int value = 7;", "```");
        ContextualLineHighlighter highlighter = markdownHighlighter();

        assertEquals(
            List.of(
                span(document.line(1), "public", KEYWORD),
                span(document.line(1), "int", TYPE),
                span(document.line(1), "7", NUMBER)
            ),
            highlight(highlighter, document, 1)
        );

        document = new DocumentSnapshot(
            DOCUMENT_ID,
            document.revision() + 1,
            List.of("plain", "public int value = 7;", "plain")
        );

        assertEquals(List.of(), highlight(highlighter, document, 1));
    }

    @Test
    void documentIdentityInvalidatesFenceStateEvenAtTheSameRevision() {
        ContextualLineHighlighter highlighter = markdownHighlighter();
        DocumentSnapshot fenced = new DocumentSnapshot(
            new DocumentId("fenced-document"),
            7,
            List.of("```java", "public int value = 7;")
        );
        DocumentSnapshot plain = new DocumentSnapshot(
            new DocumentId("plain-document"),
            7,
            List.of("plain", "public int value = 7;")
        );

        assertEquals(
            List.of(
                span(fenced.line(1), "public", KEYWORD),
                span(fenced.line(1), "int", TYPE),
                span(fenced.line(1), "7", NUMBER)
            ),
            highlight(highlighter, fenced, 1)
        );
        assertEquals(List.of(), highlight(highlighter, plain, 1));
    }

    @Test
    void invalidContextFallsBackToPlainMarkdownHighlighting() {
        DocumentSnapshot document = document("plain");
        ContextualLineHighlighter highlighter = markdownHighlighter();
        String heading = "# Heading";
        List<StyleSpan> expected = List.of(new StyleSpan(0, heading.length(), MARKUP));

        assertAll(
            () -> assertEquals(expected, highlighter.highlight(null, 0, heading)),
            () -> assertEquals(expected, highlighter.highlight(document, -1, heading)),
            () -> assertEquals(expected, highlighter.highlight(document, document.lineCount(), heading)),
            () -> assertEquals(List.of(), highlighter.highlight(document, 0, null))
        );
    }

    @Test
    void embeddedBlockCommentsKeepContextWithinTheirFenceWhenRequestedOutOfOrder() {
        DocumentSnapshot document = document(
            "Markdown outside /*",
            "```java",
            "/* open",
            "public int hidden = 7;",
            "end */ return 8;",
            "```",
            "```java",
            "public int visible = 9;",
            "```"
        );
        ContextualLineHighlighter highlighter = markdownHighlighter();
        String visible = document.line(7);
        String closingComment = document.line(4);

        assertAll(
            () -> assertEquals(
                List.of(
                    span(visible, "public", KEYWORD),
                    span(visible, "int", TYPE),
                    span(visible, "9", NUMBER)
                ),
                highlight(highlighter, document, 7)
            ),
            () -> assertEquals(
                List.of(new StyleSpan(0, document.line(3).length(), COMMENT)),
                highlight(highlighter, document, 3)
            ),
            () -> assertEquals(
                List.of(
                    new StyleSpan(0, closingComment.indexOf("*/") + 2, COMMENT),
                    span(closingComment, "return", KEYWORD),
                    span(closingComment, "8", NUMBER)
                ),
                highlight(highlighter, document, 4)
            )
        );
    }

    @Test
    void unclosedEmbeddedCommentDoesNotLeakIntoTheNextFenceOfTheSameLanguage() {
        DocumentSnapshot document = document(
            "```java",
            "/* never closed",
            "public int hidden = 7;",
            "```",
            "# Outside",
            "```java",
            "public int visible = 9;",
            "```"
        );
        ContextualLineHighlighter highlighter = markdownHighlighter();
        String visible = document.line(6);
        List<StyleSpan> expected = List.of(
            span(visible, "public", KEYWORD),
            span(visible, "int", TYPE),
            span(visible, "9", NUMBER)
        );

        assertEquals(expected, highlight(highlighter, document, 6));
        assertEquals(
            List.of(new StyleSpan(0, document.line(2).length(), COMMENT)),
            highlight(highlighter, document, 2)
        );
        assertEquals(
            List.of(new StyleSpan(0, document.line(4).length(), MARKUP)),
            highlight(highlighter, document, 4)
        );
        assertEquals(expected, highlight(highlighter, document, 6));
    }

    @Test
    void multilineStringsRemainInsideTheirFenceAndResetAtTheNextFence() {
        DocumentSnapshot document = document(
            "```js",
            "const s = `",
            "/* quoted",
            "```",
            "```js",
            "return 7;",
            "```"
        );
        ContextualLineHighlighter highlighter = markdownHighlighter();
        Style string = Style.fg(Color.BRIGHT_GREEN);
        List<StyleSpan> code = List.of(span(document.line(5), "return", KEYWORD), span(document.line(5), "7", NUMBER));

        assertEquals(code, highlight(highlighter, document, 5));
        assertEquals(List.of(new StyleSpan(0, document.line(2).length(), string)), highlight(highlighter, document, 2));
        assertEquals(code, highlight(highlighter, document, 5));
    }

    @Test
    void changedRevisionResetsMultilineStringStateInsideAnUnchangedFence() {
        DocumentSnapshot quoted = document("```js", "const s = `", "return 7;", "```");
        DocumentSnapshot revised = new DocumentSnapshot(DOCUMENT_ID, 2, List.of("```js", "// closed", "return 7;", "```"));
        ContextualLineHighlighter highlighter = markdownHighlighter();
        Style string = Style.fg(Color.BRIGHT_GREEN);

        assertEquals(List.of(new StyleSpan(0, quoted.line(2).length(), string)), highlight(highlighter, quoted, 2));
        assertEquals(
            List.of(span(revised.line(2), "return", KEYWORD), span(revised.line(2), "7", NUMBER)),
            highlight(highlighter, revised, 2)
        );
    }

    private static ContextualLineHighlighter markdownHighlighter() {
        return (ContextualLineHighlighter) RegexHighlighter.forFileName("README.md");
    }

    private static DocumentSnapshot document(String... lines) {
        return new DocumentSnapshot(DOCUMENT_ID, 1, List.of(lines));
    }

    private static List<StyleSpan> highlight(
        ContextualLineHighlighter highlighter,
        DocumentSnapshot document,
        int lineIndex
    ) {
        return highlighter.highlight(document, lineIndex, document.line(lineIndex));
    }

    private static StyleSpan span(String line, String token, Style style) {
        int start = line.indexOf(token);
        return new StyleSpan(start, start + token.length(), style);
    }
}
