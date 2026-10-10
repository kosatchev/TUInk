package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.tuink.render.Color;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class HighlighterResponsivenessTest {
    private static final Style KEYWORD = new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false);
    private static final Style TYPE = Style.fg(Color.BRIGHT_MAGENTA);
    private static final Style STRING = Style.fg(Color.BRIGHT_GREEN);
    private static final Style COMMENT = Style.fg(Color.BRIGHT_BLACK);

    @Test
    void markupCommentsEndAtTheirOwnDelimiterAndLeaveInterveningTagsVisible() {
        String line = "<!-- first --> <node value=\"text\"> <!-- second -->";
        for (LineHighlighter highlighter : List.of(RegexHighlighter.forXml(), RegexHighlighter.forHtml())) {
            assertEquals(List.of(
                span(line, "<!-- first -->", COMMENT),
                span(line, "<node", KEYWORD),
                span(line, " value=", TYPE),
                span(line, "\"text\"", STRING),
                span(line, "<!-- second -->", COMMENT)
            ), highlighter.highlight(line));
        }
    }

    @Test
    void repeatedUnterminatedMarkupCommentsDoNotRescanTheRemainingLine() {
        String opening = "<!--".repeat(40_000);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (LineHighlighter highlighter : List.of(RegexHighlighter.forXml(), RegexHighlighter.forHtml())) {
                assertEquals(List.of(new StyleSpan(0, opening.length(), COMMENT)), highlighter.highlight(opening));
                String closed = opening + "--> <node>";
                assertEquals(List.of(
                    new StyleSpan(0, opening.length() + 3, COMMENT),
                    span(closed, "<node", KEYWORD)
                ), highlighter.highlight(closed));
            }
        });
    }

    @Test
    void makeVariablesPreserveTheirLayerAndRecoverAfterEmptyCandidates() {
        String line = "$() ${} $(FIRST) ${SECOND} # $(hidden)";
        assertEquals(List.of(
            span(line, "$(FIRST)", TYPE),
            span(line, "${SECOND}", TYPE),
            new StyleSpan(line.indexOf('#'), line.length(), COMMENT)
        ), RegexHighlighter.forMakefile().highlight(line));
    }

    @Test
    void repeatedUnterminatedMakeVariablesStillAllowTheOtherDelimiterKind() {
        LineHighlighter highlighter = RegexHighlighter.forMakefile();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (String opening : List.of("$(", "${")) {
                String unmatched = opening.repeat(60_000);
                assertEquals(List.of(), highlighter.highlight(unmatched));
                String valid = opening.equals("$(") ? "${VALUE}" : "$(VALUE)";
                String line = unmatched + " " + valid;
                assertEquals(List.of(new StyleSpan(unmatched.length() + 1, line.length(), TYPE)),
                    highlighter.highlight(line));
            }
        });
    }

    @Test
    void longEscapedStringsRemainHighlightedAcrossTheBuiltInRegexFormats() {
        String quoted = "\"" + "plain\\\"text".repeat(15_000) + "\"";
        String singleQuoted = "'" + "plain\\'text".repeat(15_000) + "'";
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            for (String fileName : List.of(
                "unknown", "script.sh", "script.rb", "script.pl", "data.json", "data.toml",
                "data.ini", "data.xml", "page.html", "Dockerfile", ".env"
            )) {
                assertEquals(List.of(new StyleSpan(0, quoted.length(), STRING)),
                    RegexHighlighter.forFileName(fileName).highlight(quoted), fileName);
                if (!fileName.endsWith("json")) {
                    boolean literalSingleQuotes = List.of("script.sh", "data.toml", "Dockerfile", ".env").contains(fileName);
                    String value = literalSingleQuotes ? "'" + "plain\\text".repeat(15_000) + "'" : singleQuoted;
                    assertEquals(List.of(new StyleSpan(0, value.length(), STRING)),
                        RegexHighlighter.forFileName(fileName).highlight(value), fileName);
                }
            }
        });
    }

    @Test
    void longCsvFieldsPreserveDoubledQuotesWithoutRecursiveRegexMatching() {
        String line = "\"" + "plain\"\"text".repeat(30_000) + "\"";
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
            assertEquals(List.of(new StyleSpan(0, line.length(), STRING)),
                RegexHighlighter.forCsv().highlight(line)));
    }

    @Test
    void expensiveCustomRulesHaveBoundedRetriesAndDoNotDisableTheFollowingRules() {
        Style earlier = Style.fg(Color.RED);
        Style following = Style.fg(Color.BLUE);
        RegexHighlighter highlighter = new RegexHighlighter(List.of(
            new RegexRule(Pattern.compile("prefix"), earlier),
            // This uses non-recursive quantifiers: catching stack overflow cannot bound its time.
            new RegexRule(Pattern.compile("a+a+z"), STRING),
            new RegexRule(Pattern.compile("suffix"), following)
        ));
        String line = "prefix " + "a".repeat(80_000) + " suffix";
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
            assertEquals(List.of(span(line, "prefix", earlier), span(line, "suffix", following)),
                highlighter.highlight(line)));
    }

    private static StyleSpan span(String line, String token, Style style) {
        int start = line.indexOf(token);
        return new StyleSpan(start, start + token.length(), style);
    }
}
