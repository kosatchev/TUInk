package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.tuink.render.Color;
import dev.tuink.render.ContextualLineHighlighter;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
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
