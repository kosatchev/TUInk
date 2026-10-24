package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.TokenSpan;
import java.util.List;
import org.junit.jupiter.api.Test;

class CStyleHighlighterTest {
    private static final DocumentId DOCUMENT_ID = new DocumentId("c-style-test");
    private static final SemanticStyleKey KEYWORD = SemanticStyleKey.KEYWORD;
    private static final SemanticStyleKey TYPE = SemanticStyleKey.TYPE;
    private static final SemanticStyleKey NUMBER = SemanticStyleKey.NUMBER;
    private static final SemanticStyleKey STRING = SemanticStyleKey.STRING;
    private static final SemanticStyleKey COMMENT = SemanticStyleKey.COMMENT;

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
            ContextualSyntaxHighlighter highlighter = highlighter(fileName);
            assertEquals(code(document.line(4), "9"), highlight(highlighter, document, 4), fileName);
            assertEquals(List.of(), highlight(highlighter, document, 2), fileName);
            assertEquals(comment(document.line(1)), highlight(highlighter, document, 1), fileName);
            assertEquals(
                List.of(
                    new TokenSpan(0, document.line(3).indexOf("*/") + 2, COMMENT),
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
                    new TokenSpan(document.line(0).indexOf("/*"), document.line(0).length(), COMMENT)
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
        ContextualSyntaxHighlighter highlighter = highlighter("Test.java");

        assertAll(
            () -> assertEquals(code(document.line(2), "7"), highlight(highlighter, document, 2)),
            () -> assertEquals(
                List.of(new TokenSpan(0, document.line(0).indexOf(';'), STRING)),
                highlight(highlighter, document, 0)
            ),
            () -> assertEquals(comment(document.line(1)), highlight(highlighter, document, 1))
        );
    }

    @Test
    void changedRevisionInvalidatesPreviouslyComputedCommentState() {
        ContextualSyntaxHighlighter highlighter = highlighter("Test.java");
        DocumentSnapshot commented = document("/* open", "return 7;");
        DocumentSnapshot plain = new DocumentSnapshot(DOCUMENT_ID, 2, List.of("// closed", "return 7;"));
        DocumentSnapshot reopened = new DocumentSnapshot(DOCUMENT_ID, 3, commented.lines());

        assertEquals(comment(commented.line(1)), highlight(highlighter, commented, 1));
        assertEquals(code(plain.line(1), "7"), highlight(highlighter, plain, 1));
        assertEquals(comment(reopened.line(1)), highlight(highlighter, reopened, 1));
    }

    @Test
    void documentIdentityInvalidatesCommentStateAtTheSameRevision() {
        ContextualSyntaxHighlighter highlighter = highlighter("Test.java");
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
            ContextualSyntaxHighlighter highlighter = highlighter(fileName);
            assertEquals(
                List.of(
                    new TokenSpan(0, 2, COMMENT),
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
        ContextualSyntaxHighlighter highlighter = highlighter("Test.java");

        assertEquals(
            List.of(
                new TokenSpan(0, 2, COMMENT),
                span(document.line(2), "return", KEYWORD),
                span(document.line(2), "7", NUMBER)
            ),
            highlight(highlighter, document, 2)
        );
    }

    @Test
    void ordinaryLineHighlightingIsStatelessAndDoesNotAlterContextualResults() {
        DocumentSnapshot document = document("/* open", "return 7;");
        ContextualSyntaxHighlighter highlighter = highlighter("Test.java");

        assertEquals(comment(document.line(1)), highlight(highlighter, document, 1));
        assertEquals(code(document.line(1), "7"), highlighter.highlight(document.line(1)));
        assertEquals(comment("/* standalone"), highlighter.highlight("/* standalone"));
        assertEquals(code(document.line(1), "7"), highlighter.highlight(document.line(1)));
        assertEquals(comment(document.line(1)), highlight(highlighter, document, 1));
    }

    @Test
    void invalidContextUsesStatelessLineHighlighting() {
        DocumentSnapshot document = document("/* open", "return 7;");
        ContextualSyntaxHighlighter highlighter = highlighter("Test.java");
        String line = document.line(1);
        List<TokenSpan> expected = code(line, "7");

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
            ContextualSyntaxHighlighter highlighter = highlighter(fileName);
            assertEquals(code(document.line(3), "8"), highlight(highlighter, document, 3), fileName);
            assertEquals(string(document.line(1)), highlight(highlighter, document, 1), fileName);
            assertEquals(
                List.of(new TokenSpan(0, 1, STRING), span(document.line(2), "return", KEYWORD), span(document.line(2), "7", NUMBER)),
                highlight(highlighter, document, 2),
                fileName
            );
        }
    }

    @Test
    void ordinaryQuotedStringsCloseOnTheirDelimiterAndSingleLineOpenersDoNotCarry() {
        String line = "\"\" \"/* quoted */\"; return 7; // comment";
        ContextualSyntaxHighlighter highlighter = highlighter("Test.java");
        DocumentSnapshot document = document("value = \"", "return 7;");

        assertAll(
            () -> assertEquals(
                List.of(
                    new TokenSpan(0, 2, STRING),
                    span(line, "\"/* quoted */\"", STRING),
                    span(line, "return", KEYWORD),
                    span(line, "7", NUMBER),
                    new TokenSpan(line.indexOf("//"), line.length(), COMMENT)
                ),
                highlighter.highlight(line)
            ),
            () -> assertEquals(code(document.line(1), "7"), highlight(highlighter, document, 1))
        );
    }

    @Test
    void javascriptBackticksHonorEscapesAndGoBackticksRemainRaw() {
        DocumentSnapshot javascript = document("const s = `", "escaped \\` /* quoted", "`; return 7;");
        ContextualSyntaxHighlighter jsHighlighter = highlighter("test.js");
        assertEquals(string(javascript.line(1)), highlight(jsHighlighter, javascript, 1));
        assertEquals(
            List.of(new TokenSpan(0, 1, STRING), span(javascript.line(2), "return", KEYWORD), span(javascript.line(2), "7", NUMBER)),
            highlight(jsHighlighter, javascript, 2)
        );

        DocumentSnapshot go = document("const s = `", "\\`; /* actual", "*/ return 7;");
        ContextualSyntaxHighlighter goHighlighter = highlighter("test.go");
        assertEquals(
            List.of(new TokenSpan(0, 2, STRING), new TokenSpan(go.line(1).indexOf("/*"), go.line(1).length(), COMMENT)),
            highlight(goHighlighter, go, 1)
        );
        assertEquals(
            List.of(new TokenSpan(0, 2, COMMENT), span(go.line(2), "return", KEYWORD), span(go.line(2), "7", NUMBER)),
            highlight(goHighlighter, go, 2)
        );
    }

    @Test
    void javaKotlinAndGroovyTripleQuotedStringsKeepMultilineCommentMarkersQuoted() {
        DocumentSnapshot document = document("value = \"\"\"", "/* // quoted", "\"\"\"; return 7;", "return 8;");

        for (String fileName : List.of("Test.java", "test.kt", "build.gradle")) {
            ContextualSyntaxHighlighter highlighter = highlighter(fileName);
            SemanticStyleKey returnStyle = fileName.endsWith("gradle") ? TYPE : KEYWORD;
            assertEquals(
                List.of(span(document.line(3), "return", returnStyle), span(document.line(3), "8", NUMBER)),
                highlight(highlighter, document, 3),
                fileName
            );
            assertEquals(string(document.line(1)), highlight(highlighter, document, 1), fileName);
            assertEquals(
                List.of(new TokenSpan(0, 3, STRING), span(document.line(2), "return", returnStyle), span(document.line(2), "7", NUMBER)),
                highlight(highlighter, document, 2),
                fileName
            );
        }
    }

    @Test
    void kotlinTripleQuotesRemainRawWhileJavaTextBlocksHonorEscapes() {
        DocumentSnapshot document = document("value = \"\"\"", "\\\"\"\"; /* actual", "*/ return 7;");
        ContextualSyntaxHighlighter kotlin = highlighter("test.kt");
        ContextualSyntaxHighlighter java = highlighter("Test.java");

        assertAll(
            () -> assertEquals(
                List.of(new TokenSpan(0, 4, STRING), new TokenSpan(document.line(1).indexOf("/*"), document.line(1).length(), COMMENT)),
                highlight(kotlin, document, 1)
            ),
            () -> assertEquals(
                List.of(new TokenSpan(0, 2, COMMENT), span(document.line(2), "return", KEYWORD), span(document.line(2), "7", NUMBER)),
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
                ContextualSyntaxHighlighter highlighter = highlighter(fileName);
                assertEquals(code(document.line(3), "8"), highlight(highlighter, document, 3), fileName);
                assertEquals(string(document.line(1)), highlight(highlighter, document, 1), fileName);
                assertEquals(
                    List.of(new TokenSpan(0, 1, STRING), span(document.line(2), "return", KEYWORD), span(document.line(2), "7", NUMBER)),
                    highlight(highlighter, document, 2),
                    fileName
                );
            }
        }
    }

    @Test
    void rustRawStringPrefixesAndDelimitersRemainQuotedWithoutBackslashEscapes() {
        for (String prefix : List.of("r", "br", "cr")) {
            for (String hashes : List.of("", "#", "###")) {
                String literal = prefix + hashes + "\"/* // \\\"" + hashes;
                String line = "let value = " + literal + "; return 7; // actual";
                assertEquals(
                    List.of(
                        span(line, "let", KEYWORD),
                        span(line, literal, STRING),
                        span(line, "return", KEYWORD),
                        span(line, "7", NUMBER),
                        new TokenSpan(line.lastIndexOf("//"), line.length(), COMMENT)
                    ),
                    highlighter("test.rs").highlight(line),
                    prefix + hashes
                );
            }
        }
    }

    @Test
    void rustRawStringsCloseOnlyAfterTheirRequiredHashesAndLeaveAdditionalHashesOutside() {
        for (String prefix : List.of("r", "br", "cr")) {
            String literal = prefix + "###\"one \"# /* // two \"## end \"###";
            String line = literal + "#; return 7;";
            assertEquals(
                List.of(span(line, literal, STRING), span(line, "return", KEYWORD), span(line, "7", NUMBER)),
                highlighter("test.rs").highlight(line),
                prefix
            );
        }

        String hashes = "#".repeat(255);
        String literal = "r" + hashes + "\"body\"" + hashes;
        String line = literal + "#; return 7;";
        assertEquals(
            List.of(span(line, literal, STRING), span(line, "return", KEYWORD), span(line, "7", NUMBER)),
            highlighter("test.rs").highlight(line)
        );
    }

    @Test
    void rustRawStringsCarryTheirDelimiterAcrossLinesAndResumeCodeWhenRequestedOutOfOrder() {
        for (String prefix : List.of("r", "br", "cr")) {
            for (String hashes : List.of("", "##")) {
                String inside = hashes.isEmpty() ? "/* // return 7;" : "\"# /* // return 7;";
                DocumentSnapshot document = document(
                    "let value = " + prefix + hashes + "\"",
                    inside,
                    "\\\"" + hashes + "; return 8; // actual",
                    "return 9;"
                );
                ContextualSyntaxHighlighter highlighter = highlighter("test.rs");

                assertEquals(code(document.line(3), "9"), highlight(highlighter, document, 3), prefix + hashes);
                assertEquals(string(document.line(1)), highlight(highlighter, document, 1), prefix + hashes);
                assertEquals(
                    List.of(
                        new TokenSpan(0, hashes.length() + 2, STRING),
                        span(document.line(2), "return", KEYWORD),
                        span(document.line(2), "8", NUMBER),
                        new TokenSpan(document.line(2).indexOf("//"), document.line(2).length(), COMMENT)
                    ),
                    highlight(highlighter, document, 2),
                    prefix + hashes
                );
                assertEquals(
                    List.of(
                        span(document.line(0), "let", KEYWORD),
                        new TokenSpan(document.line(0).indexOf(prefix + hashes + "\""), document.line(0).length(), STRING)
                    ),
                    highlight(highlighter, document, 0),
                    prefix + hashes
                );
            }
        }
    }

    @Test
    void revisionAndDocumentIdentityInvalidateTheCachedRustRawDelimiter() {
        ContextualSyntaxHighlighter highlighter = highlighter("test.rs");
        DocumentSnapshot quoted = document("r##\"open", "\"#; return 7;", "return 8;");
        DocumentSnapshot revised = new DocumentSnapshot(
            DOCUMENT_ID, 2, List.of("r#\"open", quoted.line(1), quoted.line(2))
        );
        DocumentSnapshot different = new DocumentSnapshot(
            new DocumentId("different-raw-string-document"), revised.revision(), quoted.lines()
        );

        assertEquals(string(quoted.line(2)), highlight(highlighter, quoted, 2));
        assertEquals(code(revised.line(2), "8"), highlight(highlighter, revised, 2));
        assertEquals(string(different.line(2)), highlight(highlighter, different, 2));
        assertEquals(code(revised.line(2), "8"), highlighter.highlight(revised.line(2)));
        assertEquals(string(different.line(2)), highlight(highlighter, different, 2));
    }

    @Test
    void rustRawPrefixesRequireAnIdentifierBoundaryAndLeaveOrdinaryStringsAndLifetimesIntact() {
        ContextualSyntaxHighlighter highlighter = highlighter("test.rs");
        for (String identifier : List.of("addr", "number", "_r", "αr", "identifierbr", "namecr")) {
            String line = identifier + "#\"/* //\"#; return 7;";
            assertEquals(
                List.of(span(line, "\"/* //\"", STRING), span(line, "return", KEYWORD), span(line, "7", NUMBER)),
                highlighter.highlight(line),
                identifier
            );
        }

        String literal = "\"r#\\\"// /*\\\"#\"";
        String line = "'a " + literal + "; return 7; // actual";
        assertEquals(
            List.of(
                span(line, literal, STRING),
                span(line, "return", KEYWORD),
                span(line, "7", NUMBER),
                new TokenSpan(line.lastIndexOf("//"), line.length(), COMMENT)
            ),
            highlighter.highlight(line)
        );
    }

    @Test
    void rustRawPrefixesKeepTheirOrdinaryMeaningInOtherLanguages() {
        String line = "r#\"/* quoted */\"#; return 7;";
        for (String fileName : List.of("Test.java", "test.c", "test.go", "test.kt")) {
            assertEquals(
                List.of(span(line, "\"/* quoted */\"", STRING), span(line, "return", KEYWORD), span(line, "7", NUMBER)),
                highlighter(fileName).highlight(line),
                fileName
            );
        }
        assertEquals(
            List.of(new TokenSpan(line.indexOf('#'), line.length(), COMMENT)),
            highlighter("test.php").highlight(line)
        );
    }

    @Test
    void revisionAndDocumentIdentityInvalidateMultilineStringState() {
        ContextualSyntaxHighlighter highlighter = highlighter("test.js");
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
                ContextualSyntaxHighlighter highlighter = highlighter(fileName);
                assertEquals(code(document.line(1), "7"), highlight(highlighter, document, 1), fileName + " " + regex);
                assertEquals(
                    List.of(span(document.line(0), "const", KEYWORD), span(document.line(0), regex, STRING)),
                    highlight(highlighter, document, 0),
                    fileName + " " + regex
                );
            }
        }
        DocumentSnapshot returned = document("return /[/*]/.test(value);", "return 7;");
        ContextualSyntaxHighlighter highlighter = highlighter("test.js");
        assertEquals(code(returned.line(1), "7"), highlight(highlighter, returned, 1));
        assertEquals(
            List.of(span(returned.line(0), "return", KEYWORD), span(returned.line(0), "/[/*]/", STRING)),
            highlight(highlighter, returned, 0)
        );
    }

    @Test
    void javascriptDivisionKeepsFollowingBlockCommentsReal() {
        DocumentSnapshot document = document("const n = value / divisor / 2; /* actual", "return 7;", "*/ return 9;");
        ContextualSyntaxHighlighter highlighter = highlighter("test.js");

        assertEquals(comment(document.line(1)), highlight(highlighter, document, 1));
        assertEquals(
            List.of(
                span(document.line(0), "const", KEYWORD),
                span(document.line(0), "2", NUMBER),
                new TokenSpan(document.line(0).indexOf("/*"), document.line(0).length(), COMMENT)
            ),
            highlight(highlighter, document, 0)
        );
        assertEquals(
            List.of(new TokenSpan(0, 2, COMMENT), span(document.line(2), "return", KEYWORD), span(document.line(2), "9", NUMBER)),
            highlight(highlighter, document, 2)
        );
    }

    private static ContextualSyntaxHighlighter highlighter(String fileName) {
        return (ContextualSyntaxHighlighter) RegexHighlighter.forFileName(fileName);
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

    private static List<TokenSpan> comment(String line) {
        return List.of(new TokenSpan(0, line.length(), COMMENT));
    }

    private static List<TokenSpan> string(String line) {
        return List.of(new TokenSpan(0, line.length(), STRING));
    }

    private static List<TokenSpan> code(String line, String number) {
        return List.of(span(line, "return", KEYWORD), span(line, number, NUMBER));
    }

    private static TokenSpan span(String line, String token, SemanticStyleKey style) {
        int start = line.indexOf(token);
        return new TokenSpan(start, start + token.length(), style);
    }
}
