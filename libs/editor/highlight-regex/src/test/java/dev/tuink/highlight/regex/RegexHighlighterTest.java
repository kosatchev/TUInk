package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.tuink.render.Color;
import dev.tuink.render.ContextualLineHighlighter;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RegexHighlighterTest {
    private static final Style KEYWORD = new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false);
    private static final Style TYPE = Style.fg(Color.BRIGHT_MAGENTA);
    private static final Style NUMBER = Style.fg(Color.BRIGHT_CYAN);
    private static final Style STRING = Style.fg(Color.BRIGHT_GREEN);
    private static final Style COMMENT = Style.fg(Color.BRIGHT_BLACK);
    private static final Style DECORATOR = Style.fg(Color.BRIGHT_YELLOW);

    @Test
    void laterRulesOverrideEarlierRulesAndAdjacentMatchesAreCoalesced() {
        Style base = Style.fg(Color.RED);
        Style overlay = new Style(Color.GREEN, Color.DEFAULT, true, false);
        RegexHighlighter highlighter = new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("[a-z]"), base),
            new RegexRule(Pattern.compile("bc"), overlay),
            new RegexRule(Pattern.compile("(?=d)"), Style.fg(Color.BLUE))
        ));

        assertEquals(
            List.of(
                new StyleSpan(0, 1, base),
                new StyleSpan(1, 3, overlay),
                new StyleSpan(3, 5, base)
            ),
            highlighter.highlight("abcde!")
        );
    }

    @Test
    void constructorSnapshotsTheRuleList() {
        Style initial = Style.fg(Color.RED);
        List<RegexRule> rules = new ArrayList<>();
        rules.add(new RegexRule(Pattern.compile("value"), initial));
        RegexHighlighter highlighter = new RegexHighlighter(rules);

        rules.clear();
        rules.add(new RegexRule(Pattern.compile("value"), Style.fg(Color.BLUE)));

        assertEquals(List.of(new StyleSpan(0, 5, initial)), highlighter.highlight("value"));
    }

    @Test
    void nullEmptyAndUnmatchedLinesHaveNoSpans() {
        RegexHighlighter highlighter = RegexHighlighter.withDefaults();

        assertAll(
            () -> assertEquals(List.of(), highlighter.highlight(null)),
            () -> assertEquals(List.of(), highlighter.highlight("")),
            () -> assertEquals(List.of(), highlighter.highlight("plain_identifier"))
        );
    }

    @Test
    void javaRulesReturnHalfOpenRangesAndCommentsOverrideEmbeddedTokens() {
        String line = "public int answer = 42; // return \"ignored\"";
        int commentStart = line.indexOf("//");

        assertEquals(
            List.of(
                span(line, "public", KEYWORD),
                span(line, "int", TYPE),
                span(line, "42", NUMBER),
                new StyleSpan(commentStart, line.length(), COMMENT)
            ),
            RegexHighlighter.forJava().highlight(line)
        );
    }

    @Test
    void cStyleCommentsInsideQuotedStringsDoNotConsumeFollowingCode() {
        String line = "\"https://example.invalid/* path */\"; 7; // actual comment";
        List<StyleSpan> expected = List.of(
            new StyleSpan(0, line.indexOf(';'), STRING),
            span(line, "7", NUMBER),
            new StyleSpan(line.lastIndexOf("//"), line.length(), COMMENT)
        );

        for (String fileName : List.of(
            "Test.java", "test.c", "test.js", "test.ts", "build.gradle", "test.go",
            "test.rs", "test.php", "test.kt", "test.proto"
        )) {
            assertEquals(expected, RegexHighlighter.forFileName(fileName).highlight(line), fileName);
        }
    }

    @Test
    void escapedQuotesAndCharacterLiteralsKeepCommentMarkersInsideStrings() {
        String line = "\"escaped \\\" // /* */\"; '/' + '\\''; return 7; // actual comment";

        assertEquals(
            List.of(
                new StyleSpan(0, line.indexOf(';'), STRING),
                span(line, "'/'", STRING),
                span(line, "'\\''", STRING),
                span(line, "return", KEYWORD),
                span(line, "7", NUMBER),
                new StyleSpan(line.lastIndexOf("//"), line.length(), COMMENT)
            ),
            RegexHighlighter.forJava().highlight(line)
        );
    }

    @Test
    void adjacentBlockCommentsLeaveCodeBetweenThemHighlighted() {
        String line = "/* \"//\" first */ int count = 7; /* second */ return;";
        int firstEnd = line.indexOf("*/") + 2;
        int secondStart = line.lastIndexOf("/*");
        int secondEnd = line.lastIndexOf("*/") + 2;

        assertEquals(
            List.of(
                new StyleSpan(0, firstEnd, COMMENT),
                span(line, "int", TYPE),
                span(line, "7", NUMBER),
                new StyleSpan(secondStart, secondEnd, COMMENT),
                span(line, "return", KEYWORD)
            ),
            RegexHighlighter.forJava().highlight(line)
        );
    }

    @Test
    void backtickAndCssStringsKeepTheirLanguageSpecificCommentBehavior() {
        String javascript = "`https://example.invalid/* path */`; return 7; // actual comment";
        String css = "url(\"https://example.invalid/* path */\"); 7; /* actual comment */";
        String php = "\"https://example.invalid/#path\"; return 7; # actual comment";

        assertAll(
            () -> assertEquals(
                List.of(
                    new StyleSpan(0, javascript.indexOf(';'), STRING),
                    span(javascript, "return", KEYWORD),
                    span(javascript, "7", NUMBER),
                    new StyleSpan(javascript.lastIndexOf("//"), javascript.length(), COMMENT)
                ),
                RegexHighlighter.forJavaScript().highlight(javascript)
            ),
            () -> assertEquals(
                List.of(
                    new StyleSpan(css.indexOf('"'), css.lastIndexOf('"') + 1, STRING),
                    span(css, "7", NUMBER),
                    new StyleSpan(css.lastIndexOf("/*"), css.length(), COMMENT)
                ),
                RegexHighlighter.forCssLike().highlight(css)
            ),
            () -> assertEquals(
                List.of(
                    new StyleSpan(0, php.indexOf(';'), STRING),
                    span(php, "return", KEYWORD),
                    span(php, "7", NUMBER),
                    new StyleSpan(php.lastIndexOf('#'), php.length(), COMMENT)
                ),
                RegexHighlighter.forPhp().highlight(php)
            )
        );
    }

    @Test
    void rustLifetimesDoNotConsumeCodeAndCommentsButCharactersRemainStrings() {
        String line = "fn value<'a, 'b>(text: &'a str) { let ch = '🦀'; return 7; } // comment";
        String escaped = "'\\'' '\\u{1F980}' '\\x41' // comment";

        assertAll(
            () -> assertEquals(
                List.of(
                    span(line, "fn", KEYWORD),
                    span(line, "str", TYPE),
                    span(line, "let", KEYWORD),
                    span(line, "'🦀'", STRING),
                    span(line, "return", KEYWORD),
                    span(line, "7", NUMBER),
                    new StyleSpan(line.indexOf("//"), line.length(), COMMENT)
                ),
                RegexHighlighter.forRust().highlight(line)
            ),
            () -> assertEquals(
                List.of(
                    span(escaped, "'\\''", STRING),
                    span(escaped, "'\\u{1F980}'", STRING),
                    span(escaped, "'\\x41'", STRING),
                    new StyleSpan(escaped.indexOf("//"), escaped.length(), COMMENT)
                ),
                RegexHighlighter.forRust().highlight(escaped)
            )
        );
    }

    @Test
    void representativePythonAndSqlRulesApplyLanguageSpecificSyntax() {
        String python = "@cached def value(): # return 9";
        String sql = "select 7 FROM records -- where 9";

        assertAll(
            () -> assertEquals(
                List.of(
                    span(python, "@cached", DECORATOR),
                    span(python, "def", KEYWORD),
                    new StyleSpan(python.indexOf('#'), python.length(), COMMENT)
                ),
                RegexHighlighter.forPython().highlight(python)
            ),
            () -> assertEquals(
                List.of(
                    span(sql, "select", KEYWORD),
                    span(sql, "7", NUMBER),
                    span(sql, "FROM", KEYWORD),
                    new StyleSpan(sql.indexOf("--"), sql.length(), COMMENT)
                ),
                RegexHighlighter.forSql().highlight(sql)
            )
        );
    }

    @Test
    void markupAttributesKeepTheirRangesAndStringAndCommentOverrides() {
        String line = "<ns:item \talpha =\"value\" data-id='tail' ns:attr.name_1\t=7/> <!-- ignored=\"text\" -->";
        List<StyleSpan> expected = List.of(
            span(line, "<ns:item", KEYWORD),
            span(line, " \talpha =", TYPE),
            span(line, "\"value\"", STRING),
            span(line, " data-id=", TYPE),
            span(line, "'tail'", STRING),
            span(line, " ns:attr.name_1\t=", TYPE),
            new StyleSpan(line.indexOf("<!--"), line.length(), COMMENT)
        );

        for (String fileName : List.of("index.html", "settings.xml")) {
            assertEquals(expected, RegexHighlighter.forFileName(fileName).highlight(line), fileName);
        }

        String colonName = " :name=value";
        assertAll(
            () -> assertEquals(List.of(new StyleSpan(0, 7, TYPE)), RegexHighlighter.forHtml().highlight(colonName)),
            () -> assertEquals(List.of(), RegexHighlighter.forXml().highlight(colonName))
        );
    }

    @Test
    void markupAttributeScanningStaysResponsiveOnLongWhitespaceAndIdentifiers() {
        String whitespace = " \t".repeat(30_000);
        String identifier = "attribute".repeat(7_500);
        List<String> unmatched = List.of(
            whitespace,
            whitespace + identifier,
            " " + identifier,
            " " + identifier + whitespace,
            " missing attribute".repeat(4_000)
        );
        String assignment = whitespace + identifier + whitespace + "=value";

        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            for (String fileName : List.of("index.html", "settings.xml")) {
                LineHighlighter highlighter = RegexHighlighter.forFileName(fileName);
                for (String line : unmatched) {
                    assertEquals(List.of(), highlighter.highlight(line), fileName);
                }
                assertEquals(
                    List.of(new StyleSpan(0, assignment.length() - "value".length(), TYPE)),
                    highlighter.highlight(assignment),
                    fileName
                );
            }
        });
    }

    @Test
    void markdownLinksRetainSimpleDelimiterRangesAndRecoverAfterInvalidCandidates() {
        for (String fileName : List.of("README.md", "notes.markdown")) {
            LineHighlighter highlighter = RegexHighlighter.forFileName(fileName);
            String line = "[](empty) [empty]() [missing] suffix [first](one) ![画像🦀](図.png) [second](two)";
            assertEquals(
                List.of(
                    span(line, "[first](one)", DECORATOR),
                    span(line, "[画像🦀](図.png)", DECORATOR),
                    span(line, "[second](two)", DECORATOR)
                ),
                highlighter.highlight(line),
                fileName
            );
        }

        RegexHighlighter highlighter = RegexHighlighter.forMarkdown();
        assertAll(
            () -> assertEquals(List.of(), highlighter.highlight("[unclosed [label] without a destination")),
            () -> assertEquals(List.of(), highlighter.highlight("[label](missing close")),
            () -> assertEquals(List.of(), highlighter.highlight("[escaped\\]suffix](target)")),
            () -> assertEquals(List.of(new StyleSpan(0, 17, DECORATOR)), highlighter.highlight("[[nested](target)")),
            () -> assertEquals(List.of(new StyleSpan(0, 18, DECORATOR)), highlighter.highlight("[outer]([inner](u))")),
            () -> assertEquals(List.of(new StyleSpan(0, 16, DECORATOR)), highlighter.highlight("[a](one)[b](two)"))
        );
    }

    @Test
    void markdownLinksKeepTheirLayerBetweenInlineFormattingAndBlockquotes() {
        String inline = "`[code](url)` **[bold](target)**";
        String quote = "> [quoted](url) **bold**";
        assertAll(
            () -> assertEquals(
                List.of(
                    new StyleSpan(0, 1, STRING),
                    span(inline, "[code](url)", DECORATOR),
                    new StyleSpan(12, 13, STRING),
                    new StyleSpan(14, 16, TYPE),
                    span(inline, "[bold](target)", DECORATOR),
                    new StyleSpan(inline.length() - 2, inline.length(), TYPE)
                ),
                RegexHighlighter.forMarkdown().highlight(inline)
            ),
            () -> assertEquals(
                List.of(new StyleSpan(0, quote.length(), COMMENT)),
                RegexHighlighter.forMarkdown().highlight(quote)
            )
        );
    }

    @Test
    void markdownLinksScanLongUnmatchedLabelsAndDestinationsWithoutRepeatedSuffixSearches() {
        String brackets = "[".repeat(80_000);
        String invalidLinks = "[empty]() [missing] ".repeat(4_000);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (LineHighlighter highlighter : List.of(
                RegexHighlighter.forMarkdown(), RegexHighlighter.forFileName("README.md")
            )) {
                assertEquals(List.of(), highlighter.highlight(brackets));
                assertEquals(List.of(), highlighter.highlight(brackets + "] without a destination"));
                assertEquals(List.of(), highlighter.highlight("[target](" + brackets));
                String valid = brackets + "](target)";
                assertEquals(List.of(new StyleSpan(0, valid.length(), DECORATOR)), highlighter.highlight(valid));
                String afterInvalid = invalidLinks + "[valid](target)";
                assertEquals(
                    List.of(new StyleSpan(invalidLinks.length(), afterInvalid.length(), DECORATOR)),
                    highlighter.highlight(afterInvalid)
                );
            }
        });
    }

    @Test
    void fileNameRoutingHandlesCaseSpecialNamesAndFallbacks() {
        String envLine = "TOKEN=\"secret\"";

        assertAll(
            () -> assertEquals(
                List.of(new StyleSpan(0, 6, KEYWORD), new StyleSpan(6, envLine.length(), STRING)),
                RegexHighlighter.forFileName(".ENV.local").highlight(envLine)
            ),
            () -> assertEquals(
                List.of(new StyleSpan(0, 4, KEYWORD)),
                RegexHighlighter.forFileName("DOCKERFILE").highlight("FROM base")
            ),
            () -> assertEquals(
                List.of(new StyleSpan(0, 6, KEYWORD)),
                RegexHighlighter.forFileName("Example.JAVA").highlight("public value")
            ),
            () -> assertInstanceOf(
                ContextualLineHighlighter.class,
                RegexHighlighter.forFileName("README.MD")
            ),
            () -> assertEquals(
                List.of(new StyleSpan(0, 5, KEYWORD)),
                RegexHighlighter.forFileName(null).highlight("class value")
            ),
            () -> assertEquals(
                List.of(new StyleSpan(0, 5, KEYWORD)),
                RegexHighlighter.forFileName("notes.unknown").highlight("class value")
            )
        );
    }

    private static StyleSpan span(String line, String token, Style style) {
        int start = line.indexOf(token);
        return new StyleSpan(start, start + token.length(), style);
    }
}
