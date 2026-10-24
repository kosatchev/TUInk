package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.LexicalScanner;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.TokenSpan;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommentContextHighlighterTest {
    private static final DocumentId ID = new DocumentId("comment-context-test");
    private static final SemanticStyleKey KEYWORD = SemanticStyleKey.KEYWORD;
    private static final SemanticStyleKey NUMBER = SemanticStyleKey.NUMBER;
    private static final SemanticStyleKey STRING = SemanticStyleKey.STRING;
    private static final SemanticStyleKey COMMENT = SemanticStyleKey.COMMENT;

    @Test
    void rubyHashesAndInterpolationStayInsideStringsAndLeaveFollowingCodeVisible() {
        for (String quoted : List.of(
            "\"Hello #{name}\"", "\"hello #world\"", "'hello #world'",
            "\"#{lookup(\"#key\")}\"", "\"#{ {key: \"#{name}\"} }\"", "`echo #{name} # literal`",
            "\"escaped \\\" # literal\""
        )) {
            String line = "puts " + quoted + "; return 7 # actual \"comment\"";
            assertEquals(List.of(span(line, quoted, STRING), span(line, "return", KEYWORD),
                span(line, "7", NUMBER), new TokenSpan(line.lastIndexOf('#'), line.length(), COMMENT)),
                RegexHighlighter.forRuby().highlight(line), quoted);
        }
    }

    @Test
    void multilineRubyInterpolationPreservesNestedQuotesAndCommentsUntilTheOuterStringCloses() {
        DocumentSnapshot document = document(
            "value = \"#{lookup(",
            "  \"#key\", # expression comment with a misleading \"",
            "  {other: \"#{name}\"})} tail\"; return 7 # actual",
            "return 8;"
        );
        ContextualSyntaxHighlighter highlighter = highlighter("script.rb");

        assertEquals(code(document.line(3), "8"), highlight(highlighter, document, 3));
        assertEquals(List.of(new TokenSpan(0, document.line(1).length(), STRING)),
            highlight(highlighter, document, 1));
        String closing = document.line(2);
        assertEquals(List.of(new TokenSpan(0, closing.indexOf("; return"), STRING),
            span(closing, "return", KEYWORD), span(closing, "7", NUMBER),
            new TokenSpan(closing.indexOf('#', closing.indexOf("; return")), closing.length(), COMMENT)),
            highlight(highlighter, document, 2));
    }

    @Test
    void markupCommentsContinueAcrossLinesAndCloseBeforeFollowingTags() {
        for (String fileName : List.of("page.html", "settings.xml")) {
            DocumentSnapshot document = document("<!-- comment", "<hidden value=\"text\">([)]</hidden>",
                "--> <visible>", "<after>");
            ContextualSyntaxHighlighter highlighter = highlighter(fileName);

            assertEquals(List.of(span(document.line(3), "<after", KEYWORD)),
                highlight(highlighter, document, 3), fileName);
            assertEquals(List.of(new TokenSpan(0, document.line(0).length(), COMMENT)),
                highlight(highlighter, document, 0), fileName);
            assertEquals(List.of(new TokenSpan(0, document.line(1).length(), COMMENT)),
                highlight(highlighter, document, 1), fileName);
            assertEquals(List.of(new TokenSpan(0, 3, COMMENT), span(document.line(2), "<visible", KEYWORD)),
                highlight(highlighter, document, 2), fileName);
        }
    }

    @Test
    void markupAttributeQuotesProtectCommentMarkersAndTextApostrophesDoNotOpenStrings() {
        for (String fileName : List.of("page.html", "settings.xml")) {
            DocumentSnapshot document = document("Don't treat 1 < 2 and apostrophes as an attribute's opening.",
                "<node value=\"<!-- literal -->\" path=\"C:\\\"> <!-- actual", "hidden", "--> <after>");
            ContextualSyntaxHighlighter highlighter = highlighter(fileName);
            String line = document.line(1);
            assertEquals(List.of(new TokenSpan(0, 3, COMMENT), span(document.line(3), "<after", KEYWORD)),
                highlight(highlighter, document, 3), fileName);
            assertTrue(highlight(highlighter, document, 1).contains(span(line, "\"<!-- literal -->\"", STRING)), fileName);
            assertEquals(List.of(new TokenSpan(0, document.line(2).length(), COMMENT)),
                highlight(highlighter, document, 2), fileName);
        }
    }

    @Test
    void markdownMarkupCommentsResetBetweenFencesAndAfterRevisionChanges() {
        for (String tag : List.of("html", "xml")) {
            DocumentSnapshot document = document("```" + tag, "<!-- unclosed", "<hidden>", "```",
                "```" + tag, "<visible>", "```", "```" + tag, "<!-- comment", "hidden", "--> <after>", "```");
            ContextualSyntaxHighlighter highlighter = highlighter("README.md");
            assertEquals(List.of(span(document.line(5), "<visible", KEYWORD)),
                highlight(highlighter, document, 5), tag);
            assertEquals(List.of(new TokenSpan(0, document.line(2).length(), COMMENT)),
                highlight(highlighter, document, 2), tag);
            assertEquals(List.of(new TokenSpan(0, 3, COMMENT), span(document.line(10), "<after", KEYWORD)),
                highlight(highlighter, document, 10), tag);

            DocumentSnapshot revised = new DocumentSnapshot(ID, 2,
                List.of("```" + tag, "<!-- closed -->", "<visible>", "```"));
            assertEquals(List.of(span(revised.line(2), "<visible", KEYWORD)),
                highlight(highlighter, revised, 2), tag);
        }
    }

    @Test
    void javascriptRegexAfterControlConditionsDoesNotStartAComment() {
        for (String fileName : List.of("script.js", "script.ts")) {
            for (String control : List.of("if (ready)", "if (ready && check(value))", "while (ready)",
                "for (const value of values)", "for await (const value of values)", "with (scope)", "else", "do")) {
                DocumentSnapshot document = document(control + " /[/*]/.test(value);", "return 7;");
                ContextualSyntaxHighlighter highlighter = highlighter(fileName);
                assertEquals(code(document.line(1), "7"), highlight(highlighter, document, 1), control);
                assertTrue(highlight(highlighter, document, 0).contains(span(document.line(0), "/[/*]/", STRING)), control);
            }
        }
    }

    @Test
    void javascriptConditionsKeepParenthesisContextAcrossLinesAndBlockComments() {
        DocumentSnapshot document = document("if /* between keyword and condition */ (check(",
            "  value /* condition", "  */)) /[/*]/.test(value);", "return 7;");
        ContextualSyntaxHighlighter highlighter = highlighter("script.js");
        assertEquals(code(document.line(3), "7"), highlight(highlighter, document, 3));
        assertTrue(highlight(highlighter, document, 2).contains(span(document.line(2), "/[/*]/", STRING)));

        DocumentSnapshot splitKeyword = new DocumentSnapshot(ID, 2,
            List.of("if", "(", "ready", ") /[/*]/.test(value);", "return 8;"));
        assertEquals(code(splitKeyword.line(4), "8"), highlight(highlighter, splitKeyword, 4));
        assertTrue(highlight(highlighter, splitKeyword, 3).contains(span(splitKeyword.line(3), "/[/*]/", STRING)));
    }

    @Test
    void javascriptControlRegexOnlyExcludesItsLiteralFromBracketMatching() {
        LexicalScanner scanner = highlighter("script.js").lexicalScanner();
        String first = "if (ready && check(value)) /[/*]/.test(value);";
        LexicalScanner.Result condition = scanner.scan(first, first.length(), null);
        int start = first.indexOf("/[/*]/");
        assertEquals(List.of(new LexicalScanner.Range(start, start + "/[/*]/".length())),
            condition.excludedRanges());

        String following = "return ([value]);";
        LexicalScanner.Result code = scanner.scan(following, following.length(), condition.endState());
        assertEquals(List.of(), code.excludedRanges());
        assertEquals(following.length(), code.scannedCodeUnits());
    }

    @Test
    void javascriptMethodNamesAndOrdinaryCallsDoNotTurnDivisionIntoRegexLiterals() {
        for (String expression : List.of("object.if(ready)", "object.while(ready)", "object.catch(ready)",
            "call(nested(value))", "value++", "value--")) {
            String line = expression + " / divisor / 2; /* actual";
            DocumentSnapshot document = document(line, "return 7;", "*/ return 8;");
            ContextualSyntaxHighlighter highlighter = highlighter("script.js");
            assertEquals(List.of(new TokenSpan(0, document.line(1).length(), COMMENT)),
                highlight(highlighter, document, 1), expression);
            List<TokenSpan> spans = highlight(highlighter, document, 0);
            assertTrue(spans.contains(span(line, "2", NUMBER)), expression);
            assertTrue(spans.contains(new TokenSpan(line.indexOf("/*"), line.length(), COMMENT)), expression);
            assertTrue(spans.stream().noneMatch(span -> span.styleKey().equals(STRING)), expression);
        }
    }

    @Test
    void deepRubyInterpolationAndJavascriptConditionsKeepScanningBoundedWithoutRecursion() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            String withinLimit = "\"#{".repeat(128) + "value" + "}\"".repeat(128);
            assertEquals(List.of(new TokenSpan(0, withinLimit.length(), STRING)),
                RegexHighlighter.forRuby().highlight(withinLimit));
            String ruby = "\"#{".repeat(20_000) + "value" + "}\"".repeat(20_000);
            assertTrue(RegexHighlighter.forRuby().highlight(ruby).isEmpty());
            String javascript = "if (" + "(".repeat(60_000) + "value" + ")".repeat(60_000) + ") /[/*]/;";
            for (String fileName : List.of("script.rb", "script.js", "page.html", "settings.xml")) {
                String line = fileName.endsWith("rb") ? ruby : fileName.endsWith("js") ? javascript
                    : "<!--" + "(".repeat(200_000) + "-->";
                LexicalScanner scanner = highlighter(fileName).lexicalScanner();
                LexicalScanner.Result prefix = scanner.scan(line, 64, null);
                assertEquals(64, prefix.scannedCodeUnits(), fileName);
                assertEquals(List.of(), prefix.quotes(), fileName);
                for (LexicalScanner.Range range : prefix.excludedRanges()) {
                    assertTrue(range.end() <= 64, fileName);
                }
                LexicalScanner.Result complete = scanner.scan(line, line.length(), null);
                if (fileName.endsWith("rb") || fileName.endsWith("js")) {
                    assertFalse(complete.contextKnown(), fileName);
                    assertTrue(complete.scannedCodeUnits() < line.length(), fileName);
                    assertEquals(LexicalScanner.State.UNKNOWN, complete.endState(), fileName);
                } else {
                    assertTrue(complete.contextKnown(), fileName);
                    assertEquals(line.length(), complete.scannedCodeUnits(), fileName);
                }
            }
        });
    }

    @Test
    void independentlyScannedDeepContinuationStatesHaveNonRecursiveEqualityAndHashes() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (String fileName : List.of("script.js", "script.rb")) {
                String line = fileName.endsWith("js") ? "if (" + "(".repeat(12_000) : "\"#{".repeat(12_000);
                LexicalScanner scanner = highlighter(fileName).lexicalScanner();
                LexicalScanner.State first = scanner.scan(line, line.length(), null).endState();
                LexicalScanner.State second = scanner.scan(line, line.length(), null).endState();
                assertEquals(first, second, fileName);
                assertEquals(first.hashCode(), second.hashCode(), fileName);
                // Hashing retained prefixes must not repeatedly walk their entire parent chain.
                LexicalScanner.State state = null;
                for (int index = 0; index < 12_000; index++) {
                    String opening = fileName.endsWith("js") ? "(" : "\"#{";
                    state = scanner.scan(opening, opening.length(), state).endState();
                    state.hashCode();
                }
            }
        });
    }

    private static ContextualSyntaxHighlighter highlighter(String fileName) {
        return (ContextualSyntaxHighlighter) RegexHighlighter.forFileName(fileName);
    }

    private static DocumentSnapshot document(String... lines) {
        return new DocumentSnapshot(ID, 1, List.of(lines));
    }

    private static List<TokenSpan> highlight(ContextualSyntaxHighlighter highlighter, DocumentSnapshot document, int index) {
        return highlighter.highlight(document, index, document.line(index));
    }

    private static List<TokenSpan> code(String line, String number) {
        return List.of(span(line, "return", KEYWORD), span(line, number, NUMBER));
    }

    private static TokenSpan span(String line, String token, SemanticStyleKey style) {
        int start = line.indexOf(token);
        return new TokenSpan(start, start + token.length(), style);
    }
}
