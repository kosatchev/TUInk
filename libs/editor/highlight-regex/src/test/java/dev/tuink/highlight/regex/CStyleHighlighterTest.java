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

class CStyleHighlighterTest {
    private static final DocumentId DOCUMENT_ID = new DocumentId("c-style-test");
    private static final Style KEYWORD = new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false);
    private static final Style TYPE = Style.fg(Color.BRIGHT_MAGENTA);
    private static final Style NUMBER = Style.fg(Color.BRIGHT_CYAN);
    private static final Style STRING = Style.fg(Color.BRIGHT_GREEN);
    private static final Style COMMENT = Style.fg(Color.BRIGHT_BLACK);

    @Test
    void blockCommentsCoverInteriorLinesAndResumeCodeAfterClosingWhenRequestedOutOfOrder() {
        DocumentSnapshot document = document(
            "int before = 7; /* open",
            "public int hidden = 42; \"//\"",
            "",
            "end */ return 8;",
            "return 9;"
        );

        for (String fileName : List.of("Test.java", "test.c")) {
            ContextualLineHighlighter highlighter = highlighter(fileName);
            assertEquals(code(document.line(4), "9"), highlight(highlighter, document, 4), fileName);
            assertEquals(List.of(), highlight(highlighter, document, 2), fileName);
            assertEquals(comment(document.line(1)), highlight(highlighter, document, 1), fileName);
            assertEquals(
                List.of(
                    new StyleSpan(0, document.line(3).indexOf("*/") + 2, COMMENT),
                    span(document.line(3), "return", KEYWORD),
                    span(document.line(3), "8", NUMBER)
                ),
                highlight(highlighter, document, 3),
                fileName
            );
            assertEquals(
                List.of(
                    span(document.line(0), "int", TYPE),
                    span(document.line(0), "7", NUMBER),
                    new StyleSpan(document.line(0).indexOf("/*"), document.line(0).length(), COMMENT)
                ),
                highlight(highlighter, document, 0),
                fileName
            );
        }
    }

    @Test
    void blockCommentMarkersInsideStringsAndLineCommentsDoNotAffectLaterLines() {
        DocumentSnapshot document = document(
            "\"https://example.invalid/*\";",
            "// /* ignored",
            "return 7;"
        );
        ContextualLineHighlighter highlighter = highlighter("Test.java");

        assertAll(
            () -> assertEquals(code(document.line(2), "7"), highlight(highlighter, document, 2)),
            () -> assertEquals(
                List.of(new StyleSpan(0, document.line(0).indexOf(';'), STRING)),
                highlight(highlighter, document, 0)
            ),
            () -> assertEquals(comment(document.line(1)), highlight(highlighter, document, 1))
        );
    }

    @Test
    void changedRevisionInvalidatesPreviouslyComputedCommentState() {
        ContextualLineHighlighter highlighter = highlighter("Test.java");
        DocumentSnapshot commented = document("/* open", "return 7;");
        DocumentSnapshot plain = new DocumentSnapshot(DOCUMENT_ID, 2, List.of("// closed", "return 7;"));
        DocumentSnapshot reopened = new DocumentSnapshot(DOCUMENT_ID, 3, commented.lines());

        assertEquals(comment(commented.line(1)), highlight(highlighter, commented, 1));
        assertEquals(code(plain.line(1), "7"), highlight(highlighter, plain, 1));
        assertEquals(comment(reopened.line(1)), highlight(highlighter, reopened, 1));
    }

    @Test
    void documentIdentityInvalidatesCommentStateAtTheSameRevision() {
        ContextualLineHighlighter highlighter = highlighter("Test.java");
        DocumentSnapshot commented = document("/* open", "return 7;");
        DocumentSnapshot plain = new DocumentSnapshot(
            new DocumentId("different-document"),
            commented.revision(),
            List.of("// closed", "return 7;")
        );

        assertEquals(comment(commented.line(1)), highlight(highlighter, commented, 1));
        assertEquals(code(plain.line(1), "7"), highlight(highlighter, plain, 1));
        assertEquals(comment(commented.line(1)), highlight(highlighter, commented, 1));
    }

    @Test
    void kotlinAndRustRetainNestedCommentDepthAcrossLines() {
        DocumentSnapshot document = document(
            "/* outer",
            "/* inner",
            "*/ return 7;",
            "*/ return 8;"
        );

        for (String fileName : List.of("test.kt", "test.rs")) {
            ContextualLineHighlighter highlighter = highlighter(fileName);
            assertEquals(
                List.of(
                    new StyleSpan(0, 2, COMMENT),
                    span(document.line(3), "return", KEYWORD),
                    span(document.line(3), "8", NUMBER)
                ),
                highlight(highlighter, document, 3),
                fileName
            );
            assertEquals(comment(document.line(2)), highlight(highlighter, document, 2), fileName);
            assertEquals(comment(document.line(1)), highlight(highlighter, document, 1), fileName);
        }
    }

    @Test
    void javaCommentsDoNotNest() {
        DocumentSnapshot document = document("/* outer", "/* not nested", "*/ return 7;");
        ContextualLineHighlighter highlighter = highlighter("Test.java");

        assertEquals(
            List.of(
                new StyleSpan(0, 2, COMMENT),
                span(document.line(2), "return", KEYWORD),
                span(document.line(2), "7", NUMBER)
            ),
            highlight(highlighter, document, 2)
        );
    }

    @Test
    void ordinaryLineHighlightingIsStatelessAndDoesNotAlterContextualResults() {
        DocumentSnapshot document = document("/* open", "return 7;");
        ContextualLineHighlighter highlighter = highlighter("Test.java");

        assertEquals(comment(document.line(1)), highlight(highlighter, document, 1));
        assertEquals(code(document.line(1), "7"), highlighter.highlight(document.line(1)));
        assertEquals(comment("/* standalone"), highlighter.highlight("/* standalone"));
        assertEquals(code(document.line(1), "7"), highlighter.highlight(document.line(1)));
        assertEquals(comment(document.line(1)), highlight(highlighter, document, 1));
    }

    @Test
    void invalidContextUsesStatelessLineHighlighting() {
        DocumentSnapshot document = document("/* open", "return 7;");
        ContextualLineHighlighter highlighter = highlighter("Test.java");
        String line = document.line(1);
        List<StyleSpan> expected = code(line, "7");

        assertAll(
            () -> assertEquals(expected, highlighter.highlight(null, 1, line)),
            () -> assertEquals(expected, highlighter.highlight(document, -1, line)),
            () -> assertEquals(expected, highlighter.highlight(document, document.lineCount(), line)),
            () -> assertEquals(List.of(), highlighter.highlight(document, 1, null))
        );
    }

    @Test
    void multilineBacktickStringsKeepCommentMarkersQuotedAndResumeCode() {
        DocumentSnapshot document = document("const s = `", "/* // quoted", "`; return 7;", "return 8;");

        for (String fileName : List.of("test.js", "test.ts", "test.go")) {
            ContextualLineHighlighter highlighter = highlighter(fileName);
            assertEquals(code(document.line(3), "8"), highlight(highlighter, document, 3), fileName);
            assertEquals(string(document.line(1)), highlight(highlighter, document, 1), fileName);
            assertEquals(
                List.of(new StyleSpan(0, 1, STRING), span(document.line(2), "return", KEYWORD), span(document.line(2), "7", NUMBER)),
                highlight(highlighter, document, 2),
                fileName
            );
        }
    }

    @Test
    void ordinaryQuotedStringsCloseOnTheirDelimiterAndSingleLineOpenersDoNotCarry() {
        String line = "\"\" \"/* quoted */\"; return 7; // comment";
        ContextualLineHighlighter highlighter = highlighter("Test.java");
        DocumentSnapshot document = document("value = \"", "return 7;");

        assertAll(
            () -> assertEquals(
                List.of(
                    new StyleSpan(0, 2, STRING),
                    span(line, "\"/* quoted */\"", STRING),
                    span(line, "return", KEYWORD),
                    span(line, "7", NUMBER),
                    new StyleSpan(line.indexOf("//"), line.length(), COMMENT)
                ),
                highlighter.highlight(line)
            ),
            () -> assertEquals(code(document.line(1), "7"), highlight(highlighter, document, 1))
        );
    }

    @Test
    void javascriptBackticksHonorEscapesAndGoBackticksRemainRaw() {
        DocumentSnapshot javascript = document("const s = `", "escaped \\` /* quoted", "`; return 7;");
        ContextualLineHighlighter jsHighlighter = highlighter("test.js");
        assertEquals(string(javascript.line(1)), highlight(jsHighlighter, javascript, 1));
        assertEquals(
            List.of(new StyleSpan(0, 1, STRING), span(javascript.line(2), "return", KEYWORD), span(javascript.line(2), "7", NUMBER)),
            highlight(jsHighlighter, javascript, 2)
        );

        DocumentSnapshot go = document("const s = `", "\\`; /* actual", "*/ return 7;");
        ContextualLineHighlighter goHighlighter = highlighter("test.go");
        assertEquals(
            List.of(new StyleSpan(0, 2, STRING), new StyleSpan(go.line(1).indexOf("/*"), go.line(1).length(), COMMENT)),
            highlight(goHighlighter, go, 1)
        );
        assertEquals(
            List.of(new StyleSpan(0, 2, COMMENT), span(go.line(2), "return", KEYWORD), span(go.line(2), "7", NUMBER)),
            highlight(goHighlighter, go, 2)
        );
    }

    @Test
    void javaKotlinAndGroovyTripleQuotedStringsKeepMultilineCommentMarkersQuoted() {
        DocumentSnapshot document = document("value = \"\"\"", "/* // quoted", "\"\"\"; return 7;", "return 8;");

        for (String fileName : List.of("Test.java", "test.kt", "build.gradle")) {
            ContextualLineHighlighter highlighter = highlighter(fileName);
            Style returnStyle = fileName.endsWith("gradle") ? TYPE : KEYWORD;
            assertEquals(
                List.of(span(document.line(3), "return", returnStyle), span(document.line(3), "8", NUMBER)),
                highlight(highlighter, document, 3),
                fileName
            );
            assertEquals(string(document.line(1)), highlight(highlighter, document, 1), fileName);
            assertEquals(
                List.of(new StyleSpan(0, 3, STRING), span(document.line(2), "return", returnStyle), span(document.line(2), "7", NUMBER)),
                highlight(highlighter, document, 2),
                fileName
            );
        }
    }

    @Test
    void kotlinTripleQuotesRemainRawWhileJavaTextBlocksHonorEscapes() {
        DocumentSnapshot document = document("value = \"\"\"", "\\\"\"\"; /* actual", "*/ return 7;");
        ContextualLineHighlighter kotlin = highlighter("test.kt");
        ContextualLineHighlighter java = highlighter("Test.java");

        assertAll(
            () -> assertEquals(
                List.of(new StyleSpan(0, 4, STRING), new StyleSpan(document.line(1).indexOf("/*"), document.line(1).length(), COMMENT)),
                highlight(kotlin, document, 1)
            ),
            () -> assertEquals(
                List.of(new StyleSpan(0, 2, COMMENT), span(document.line(2), "return", KEYWORD), span(document.line(2), "7", NUMBER)),
                highlight(kotlin, document, 2)
            ),
            () -> assertEquals(string(document.line(1)), highlight(java, document, 1)),
            () -> assertEquals(string(document.line(2)), highlight(java, document, 2))
        );
    }

    @Test
    void rustAndPhpOrdinaryStringsCanContinueAcrossLines() {
        for (String fileName : List.of("test.rs", "test.php")) {
            for (char quote : fileName.endsWith("php") ? new char[]{'"', '\''} : new char[]{'"'}) {
                DocumentSnapshot document = document("value = " + quote, "/* // quoted", quote + "; return 7;", "return 8;");
                ContextualLineHighlighter highlighter = highlighter(fileName);
                assertEquals(code(document.line(3), "8"), highlight(highlighter, document, 3), fileName);
                assertEquals(string(document.line(1)), highlight(highlighter, document, 1), fileName);
                assertEquals(
                    List.of(new StyleSpan(0, 1, STRING), span(document.line(2), "return", KEYWORD), span(document.line(2), "7", NUMBER)),
                    highlight(highlighter, document, 2),
                    fileName
                );
            }
        }
    }

    @Test
    void revisionAndDocumentIdentityInvalidateMultilineStringState() {
        ContextualLineHighlighter highlighter = highlighter("test.js");
        DocumentSnapshot quoted = document("const s = `", "return 7;", "`;");
        DocumentSnapshot revised = new DocumentSnapshot(DOCUMENT_ID, 2, List.of("// closed", "return 7;", "// closed"));
        DocumentSnapshot different = new DocumentSnapshot(new DocumentId("different-string-document"), 1, revised.lines());

        assertEquals(string(quoted.line(1)), highlight(highlighter, quoted, 1));
        assertEquals(code(revised.line(1), "7"), highlight(highlighter, revised, 1));
        assertEquals(string(quoted.line(1)), highlight(highlighter, quoted, 1));
        assertEquals(code(different.line(1), "7"), highlight(highlighter, different, 1));
    }

    @Test
    void javascriptRegexEscapesAndCharacterClassesDoNotOpenBlockComments() {
        for (String fileName : List.of("test.js", "test.ts")) {
            for (String regex : List.of("/[/*]/g", "/[\\]/*]/g", "/https?:\\/\\/[/*]/g")) {
                DocumentSnapshot document = document("const re = " + regex + ";", "return 7;");
                ContextualLineHighlighter highlighter = highlighter(fileName);
                assertEquals(code(document.line(1), "7"), highlight(highlighter, document, 1), fileName + " " + regex);
                assertEquals(
                    List.of(span(document.line(0), "const", KEYWORD), span(document.line(0), regex, STRING)),
                    highlight(highlighter, document, 0),
                    fileName + " " + regex
                );
            }
        }
        DocumentSnapshot returned = document("return /[/*]/.test(value);", "return 7;");
        ContextualLineHighlighter highlighter = highlighter("test.js");
        assertEquals(code(returned.line(1), "7"), highlight(highlighter, returned, 1));
        assertEquals(
            List.of(span(returned.line(0), "return", KEYWORD), span(returned.line(0), "/[/*]/", STRING)),
            highlight(highlighter, returned, 0)
        );
    }

    @Test
    void javascriptDivisionKeepsFollowingBlockCommentsReal() {
        DocumentSnapshot document = document("const n = value / divisor / 2; /* actual", "return 7;", "*/ return 9;");
        ContextualLineHighlighter highlighter = highlighter("test.js");

        assertEquals(comment(document.line(1)), highlight(highlighter, document, 1));
        assertEquals(
            List.of(
                span(document.line(0), "const", KEYWORD),
                span(document.line(0), "2", NUMBER),
                new StyleSpan(document.line(0).indexOf("/*"), document.line(0).length(), COMMENT)
            ),
            highlight(highlighter, document, 0)
        );
        assertEquals(
            List.of(new StyleSpan(0, 2, COMMENT), span(document.line(2), "return", KEYWORD), span(document.line(2), "9", NUMBER)),
            highlight(highlighter, document, 2)
        );
    }

    private static ContextualLineHighlighter highlighter(String fileName) {
        return (ContextualLineHighlighter) RegexHighlighter.forFileName(fileName);
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

    private static List<StyleSpan> comment(String line) {
        return List.of(new StyleSpan(0, line.length(), COMMENT));
    }

    private static List<StyleSpan> string(String line) {
        return List.of(new StyleSpan(0, line.length(), STRING));
    }

    private static List<StyleSpan> code(String line, String number) {
        return List.of(span(line, "return", KEYWORD), span(line, number, NUMBER));
    }

    private static StyleSpan span(String line, String token, Style style) {
        int start = line.indexOf(token);
        return new StyleSpan(start, start + token.length(), style);
    }
}
