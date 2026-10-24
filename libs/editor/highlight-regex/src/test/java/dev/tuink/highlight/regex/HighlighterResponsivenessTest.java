package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.tuink.editor.highlight.SyntaxHighlighter;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.TokenSpan;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class HighlighterResponsivenessTest {
    private static final SemanticStyleKey KEYWORD = SemanticStyleKey.KEYWORD;
    private static final SemanticStyleKey TYPE = SemanticStyleKey.TYPE;
    private static final SemanticStyleKey STRING = SemanticStyleKey.STRING;
    private static final SemanticStyleKey COMMENT = SemanticStyleKey.COMMENT;

    @Test
    void markupCommentsEndAtTheirOwnDelimiterAndLeaveInterveningTagsVisible() {
        String line = "<!-- first --> <node value=\"text\"> <!-- second -->";
        for (SyntaxHighlighter highlighter : List.of(RegexHighlighter.forXml(), RegexHighlighter.forHtml())) {
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
            for (SyntaxHighlighter highlighter : List.of(RegexHighlighter.forXml(), RegexHighlighter.forHtml())) {
                assertEquals(List.of(new TokenSpan(0, opening.length(), COMMENT)), highlighter.highlight(opening));
                String closed = opening + "--> <node>";
                assertEquals(List.of(
                    new TokenSpan(0, opening.length() + 3, COMMENT),
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
            new TokenSpan(line.indexOf('#'), line.length(), COMMENT)
        ), RegexHighlighter.forMakefile().highlight(line));
    }

    @Test
    void repeatedUnterminatedMakeVariablesStillAllowTheOtherDelimiterKind() {
        SyntaxHighlighter highlighter = RegexHighlighter.forMakefile();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (String opening : List.of("$(", "${")) {
                String unmatched = opening.repeat(60_000);
                assertEquals(List.of(), highlighter.highlight(unmatched));
                String valid = opening.equals("$(") ? "${VALUE}" : "$(VALUE)";
                String line = unmatched + " " + valid;
                assertEquals(List.of(new TokenSpan(unmatched.length() + 1, line.length(), TYPE)),
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
                assertEquals(List.of(new TokenSpan(0, quoted.length(), STRING)),
                    RegexHighlighter.forFileName(fileName).highlight(quoted), fileName);
                if (!fileName.endsWith("json")) {
                    boolean literalSingleQuotes = List.of("script.sh", "data.toml", "Dockerfile", ".env").contains(fileName);
                    String value = literalSingleQuotes ? "'" + "plain\\text".repeat(15_000) + "'" : singleQuoted;
                    assertEquals(List.of(new TokenSpan(0, value.length(), STRING)),
                        RegexHighlighter.forFileName(fileName).highlight(value), fileName);
                }
            }
        });
    }

    @Test
    void longCsvFieldsPreserveDoubledQuotesWithoutRecursiveRegexMatching() {
        String line = "\"" + "plain\"\"text".repeat(30_000) + "\"";
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
            assertEquals(List.of(new TokenSpan(0, line.length(), STRING)),
                RegexHighlighter.forCsv().highlight(line)));
    }

    @Test
    void expensiveCustomRulesHaveBoundedRetriesAndDoNotDisableTheFollowingRules() {
        SemanticStyleKey earlier = new SemanticStyleKey("test.red");
        SemanticStyleKey following = new SemanticStyleKey("test.blue");
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

    private static TokenSpan span(String line, String token, SemanticStyleKey style) {
        int start = line.indexOf(token);
        return new TokenSpan(start, start + token.length(), style);
    }
}
