package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.SyntaxHighlighter;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.TokenSpan;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarkdownFenceHighlighterTest {
    private static final DocumentId DOCUMENT_ID = new DocumentId("markdown-test");
    private static final SemanticStyleKey KEYWORD = SemanticStyleKey.KEYWORD;
    private static final SemanticStyleKey TYPE = SemanticStyleKey.TYPE;
    private static final SemanticStyleKey NUMBER = SemanticStyleKey.NUMBER;
    private static final SemanticStyleKey COMMENT = SemanticStyleKey.COMMENT;
    private static final SemanticStyleKey MARKUP = SemanticStyleKey.MARKUP;

    @Test
    void contextualHighlightingFindsFenceStateWhenLinesAreRequestedOutOfOrder() {
        DocumentSnapshot document = document(
            "# Before",
            "```java",
            "public int value = 7; // return",
            "```",
            "# After"
        );
        ContextualSyntaxHighlighter highlighter = markdownHighlighter();

        assertEquals(
            List.of(new TokenSpan(0, document.line(4).length(), MARKUP)),
            highlight(highlighter, document, 4)
        );

        String java = document.line(2);
        assertAll(
            () -> assertEquals(
                List.of(
                    span(java, "public", KEYWORD),
                    span(java, "int", TYPE),
                    span(java, "7", NUMBER),
                    new TokenSpan(java.indexOf("//"), java.length(), COMMENT)
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
        ContextualSyntaxHighlighter highlighter = markdownHighlighter();

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
        ContextualSyntaxHighlighter highlighter = markdownHighlighter();

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
        ContextualSyntaxHighlighter highlighter = markdownHighlighter();

        assertEquals(List.of(), highlight(highlighter, document, 1));
    }

    @Test
    void changedDocumentRevisionInvalidatesPreviouslyComputedFenceState() {
        DocumentSnapshot document = document("```java", "public int value = 7;", "```");
        ContextualSyntaxHighlighter highlighter = markdownHighlighter();

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
        ContextualSyntaxHighlighter highlighter = markdownHighlighter();
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
        ContextualSyntaxHighlighter highlighter = markdownHighlighter();
        String heading = "# Heading";
        List<TokenSpan> expected = List.of(new TokenSpan(0, heading.length(), MARKUP));

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
        ContextualSyntaxHighlighter highlighter = markdownHighlighter();
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
                List.of(new TokenSpan(0, document.line(3).length(), COMMENT)),
                highlight(highlighter, document, 3)
            ),
            () -> assertEquals(
                List.of(
                    new TokenSpan(0, closingComment.indexOf("*/") + 2, COMMENT),
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
        ContextualSyntaxHighlighter highlighter = markdownHighlighter();
        String visible = document.line(6);
        List<TokenSpan> expected = List.of(
            span(visible, "public", KEYWORD),
            span(visible, "int", TYPE),
            span(visible, "9", NUMBER)
        );

        assertEquals(expected, highlight(highlighter, document, 6));
        assertEquals(
            List.of(new TokenSpan(0, document.line(2).length(), COMMENT)),
            highlight(highlighter, document, 2)
        );
        assertEquals(
            List.of(new TokenSpan(0, document.line(4).length(), MARKUP)),
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
        ContextualSyntaxHighlighter highlighter = markdownHighlighter();
        SemanticStyleKey string = SemanticStyleKey.STRING;
        List<TokenSpan> code = List.of(span(document.line(5), "return", KEYWORD), span(document.line(5), "7", NUMBER));

        assertEquals(code, highlight(highlighter, document, 5));
        assertEquals(List.of(new TokenSpan(0, document.line(2).length(), string)), highlight(highlighter, document, 2));
        assertEquals(code, highlight(highlighter, document, 5));
    }

    @Test
    void rustRawStringsKeepTheirDelimiterWithinAFenceAndResetAtTheNextFence() {
        for (String prefix : List.of("r", "br", "cr")) {
            DocumentSnapshot document = document(
                "Markdown outside r###\"",
                "```rust",
                prefix + "##\"open",
                "\"# /* // quoted",
                "\"##; return 7;",
                prefix + "###\"never closed",
                "```",
                "# Outside",
                "```rs",
                "return 8;",
                "```"
            );
            ContextualSyntaxHighlighter highlighter = markdownHighlighter();
            SemanticStyleKey string = SemanticStyleKey.STRING;
            List<TokenSpan> visible = List.of(
                span(document.line(9), "return", KEYWORD), span(document.line(9), "8", NUMBER)
            );

            assertEquals(visible, highlight(highlighter, document, 9), prefix);
            assertEquals(
                List.of(new TokenSpan(0, document.line(3).length(), string)),
                highlight(highlighter, document, 3),
                prefix
            );
            assertEquals(
                List.of(
                    new TokenSpan(0, 3, string),
                    span(document.line(4), "return", KEYWORD),
                    span(document.line(4), "7", NUMBER)
                ),
                highlight(highlighter, document, 4),
                prefix
            );
            assertEquals(
                List.of(new TokenSpan(0, document.line(7).length(), MARKUP)),
                highlight(highlighter, document, 7),
                prefix
            );
            assertEquals(visible, highlight(highlighter, document, 9), prefix);
        }
    }

    @Test
    void changedRevisionResetsMultilineStringStateInsideAnUnchangedFence() {
        DocumentSnapshot quoted = document("```js", "const s = `", "return 7;", "```");
        DocumentSnapshot revised = new DocumentSnapshot(DOCUMENT_ID, 2, List.of("```js", "// closed", "return 7;", "```"));
        ContextualSyntaxHighlighter highlighter = markdownHighlighter();
        SemanticStyleKey string = SemanticStyleKey.STRING;

        assertEquals(List.of(new TokenSpan(0, quoted.line(2).length(), string)), highlight(highlighter, quoted, 2));
        assertEquals(
            List.of(span(revised.line(2), "return", KEYWORD), span(revised.line(2), "7", NUMBER)),
            highlight(highlighter, revised, 2)
        );
    }

    @Test
    void unnamedFencesReuseOneFallbackAcrossLinesBlocksAndRevisions() throws ReflectiveOperationException {
        MarkdownFenceHighlighter highlighter = new MarkdownFenceHighlighter();
        SyntaxHighlighter fallback = null;
        for (int revision = 1; revision <= 4; revision++) {
            List<String> lines = new ArrayList<>();
            lines.add("```");
            for (int line = 0; line < 128; line++) {
                lines.add("public value = " + revision + ";");
            }
            lines.add("```");
            lines.add("~~~");
            lines.add("return 7;");
            lines.add("~~~");
            DocumentSnapshot document = new DocumentSnapshot(DOCUMENT_ID, revision, lines);

            assertEquals(
                List.of(span(document.line(131), "return", KEYWORD), span(document.line(131), "7", NUMBER)),
                highlight(highlighter, document, 131)
            );
            if (fallback == null) {
                fallback = embeddedDelegate(highlighter, "");
            }
            for (int line = 1; line <= 128; line++) {
                assertEquals(fallback.highlight(document.line(line)), highlight(highlighter, document, line));
            }
            assertSame(fallback, embeddedDelegate(highlighter, ""), "second fence in revision " + revision);
        }
    }

    @Test
    void unsupportedFenceTagsReuseFallbackWithoutAccumulatingAcrossRevisions() throws ReflectiveOperationException {
        MarkdownFenceHighlighter highlighter = new MarkdownFenceHighlighter();
        SyntaxHighlighter fallback = null;
        for (int revision = 1; revision <= 32; revision++) {
            DocumentSnapshot document = new DocumentSnapshot(
                DOCUMENT_ID,
                revision,
                List.of("```unsupported-" + revision, "return 7;", "```", "~~~", "return 8;", "~~~")
            );

            assertEquals(
                List.of(span(document.line(4), "return", KEYWORD), span(document.line(4), "8", NUMBER)),
                highlight(highlighter, document, 4)
            );
            if (fallback == null) {
                fallback = embeddedDelegate(highlighter, "");
            }
            assertSame(fallback, embeddedDelegate(highlighter, "unsupported-" + revision), "unsupported tag in revision " + revision);
            assertSame(fallback, embeddedDelegate(highlighter, ""), "unnamed fence in revision " + revision);
        }
        Field cache = MarkdownFenceHighlighter.class.getDeclaredField("languageCache");
        cache.setAccessible(true);
        assertEquals(0, ((Map<?, ?>) cache.get(highlighter)).size(), "unsupported tags must not grow the cache");
    }

    // Equal spans cannot distinguish shared rules from a newly compiled rule set per line.
    private static SyntaxHighlighter embeddedDelegate(MarkdownFenceHighlighter highlighter, String tag)
        throws ReflectiveOperationException {
        var delegate = MarkdownFenceHighlighter.class.getDeclaredMethod("embeddedHighlighter", String.class);
        delegate.setAccessible(true);
        return (SyntaxHighlighter) delegate.invoke(highlighter, tag);
    }

    private static ContextualSyntaxHighlighter markdownHighlighter() {
        return (ContextualSyntaxHighlighter) RegexHighlighter.forFileName("README.md");
    }

    private static DocumentSnapshot document(String... lines) {
        return new DocumentSnapshot(DOCUMENT_ID, 1, List.of(lines));
    }

    private static List<TokenSpan> highlight(
        ContextualSyntaxHighlighter highlighter,
        DocumentSnapshot document,
        int lineIndex
    ) {
        return highlighter.highlight(document, lineIndex, document.line(lineIndex));
    }

    private static TokenSpan span(String line, String token, SemanticStyleKey style) {
        int start = line.indexOf(token);
        return new TokenSpan(start, start + token.length(), style);
    }
}
