package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.TokenSpan;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuotedCommentHighlighterTest {
    private static final DocumentId DOCUMENT_ID = new DocumentId("quoted-comment-test");
    private static final SemanticStyleKey KEYWORD = SemanticStyleKey.KEYWORD;
    private static final SemanticStyleKey TYPE = SemanticStyleKey.TYPE;
    private static final SemanticStyleKey NUMBER = SemanticStyleKey.NUMBER;
    private static final SemanticStyleKey STRING = SemanticStyleKey.STRING;
    private static final SemanticStyleKey COMMENT = SemanticStyleKey.COMMENT;

    @Test
    void hashCommentsAndQuotedValuesAreScannedInSourceOrderAcrossTextFormats() {
        for (String quoted : List.of("\"hash # text\"", "'hash # text'")) {
            String shell = "export value=" + quoted + "; return 7 # actual \"return 9\"";
            assertEquals(List.of(span(shell, "export", KEYWORD), span(shell, quoted, STRING),
                span(shell, "return", KEYWORD), span(shell, "7", NUMBER), comment(shell, shell.lastIndexOf('#'))),
                RegexHighlighter.forShell().highlight(shell));

            String perl = "my $value = " + quoted + "; return 7 # actual \"return 9\"";
            assertEquals(List.of(span(perl, "my", KEYWORD), span(perl, "$value", TYPE), span(perl, quoted, STRING),
                span(perl, "return", KEYWORD), span(perl, "7", NUMBER), comment(perl, perl.lastIndexOf('#'))),
                RegexHighlighter.forPerl().highlight(perl));

            String config = "value = " + quoted + " # actual \"true 9\"";
            for (String fileName : List.of("settings.toml", "settings.ini", ".env")) {
                assertEquals(List.of(span(config, "value =", KEYWORD), span(config, quoted, STRING),
                    comment(config, config.lastIndexOf('#'))),
                    RegexHighlighter.forFileName(fileName).highlight(config), fileName);
            }
        }
    }

    @Test
    void shellHashesOnlyStartCommentsAtWordBoundariesAndUnquotedBackslashesProtectCharacters() {
        String line = "echo path#fragment \\#literal \\\"quoted#text \\'literal#text;# actual \"return 7\"";
        assertEquals(List.of(comment(line, line.lastIndexOf('#'))), RegexHighlighter.forShell().highlight(line));

        String literalSingleQuote = "export value='C:\\' # actual \"return 7\"";
        assertEquals(List.of(span(literalSingleQuote, "export", KEYWORD), span(literalSingleQuote, "'C:\\'", STRING),
            comment(literalSingleQuote, literalSingleQuote.indexOf('#'))),
            RegexHighlighter.forShell().highlight(literalSingleQuote));
    }

    @Test
    void escapedDoubleQuotesAndPerlSingleQuotesKeepHashesInsideTheString() {
        String quoted = "\"escaped \\\" # literal\"";
        for (String fileName : List.of("script.sh", "script.pl", "settings.toml", "settings.ini", ".env")) {
            String line = quoted + " # actual \"return 7\"";
            assertEquals(List.of(span(line, quoted, STRING), comment(line, line.lastIndexOf('#'))),
                RegexHighlighter.forFileName(fileName).highlight(line), fileName);
        }
        String perl = "'escaped \\' # literal' # actual";
        assertEquals(List.of(new TokenSpan(0, perl.lastIndexOf('\'') + 1, STRING),
            comment(perl, perl.lastIndexOf('#'))), RegexHighlighter.forPerl().highlight(perl));
    }

    @Test
    void perlLastArrayIndexDoesNotBecomeAComment() {
        String line = "my $last = $#items; return 7 # actual \"return 9\"";
        assertEquals(List.of(span(line, "my", KEYWORD), span(line, "$last", TYPE), span(line, "return", KEYWORD),
            span(line, "7", NUMBER), comment(line, line.lastIndexOf('#'))), RegexHighlighter.forPerl().highlight(line));
    }

    @Test
    void perlPunctuationVariablesAndLegacyPackageNamesDoNotOpenMultilineStrings() {
        for (String opening : List.of("my $suffix = $';", "my $separator = $\";", "use Foo'Bar;", "package Foo'Bar;")) {
            String following = "return 7 # actual \"comment\"";
            DocumentSnapshot document = document(opening, following);
            ContextualSyntaxHighlighter highlighter = highlighter("script.pl");

            assertEquals(List.of(span(following, "return", KEYWORD), span(following, "7", NUMBER),
                comment(following, following.indexOf('#'))), highlight(highlighter, document, 1), opening);
            List<TokenSpan> expectedOpening = opening.startsWith("my")
                ? List.of(span(opening, "my", KEYWORD),
                    span(opening, opening.contains("$suffix") ? "$suffix" : "$separator", TYPE))
                : List.of(span(opening, opening.startsWith("use") ? "use" : "package", KEYWORD));
            assertEquals(expectedOpening, highlight(highlighter, document, 0), opening);
        }
    }

    @Test
    void perlQuotePrefixesStillAllowStringsWithoutInterveningWhitespace() {
        for (String prefix : List.of("print", "return", "q")) {
            String quoted = "'hash # literal'";
            String line = prefix + quoted + "; # actual";
            List<TokenSpan> expected = prefix.equals("return")
                ? List.of(span(line, prefix, KEYWORD), span(line, quoted, STRING), comment(line, line.lastIndexOf('#')))
                : List.of(span(line, quoted, STRING), comment(line, line.lastIndexOf('#')));
            assertEquals(expected, RegexHighlighter.forPerl().highlight(line), prefix);
        }
    }

    @Test
    void iniCommentsRequireSeparationAndRespectQuotedSemicolons() {
        String line = "value = \"hash # and ; literal\" ; actual \"true 7\"";
        assertEquals(List.of(span(line, "value =", KEYWORD), span(line, "\"hash # and ; literal\"", STRING),
            comment(line, line.lastIndexOf(';'))), RegexHighlighter.forIniLike().highlight(line));

        String unquoted = "value=https://example.invalid/path#fragment;tail # actual";
        assertEquals(List.of(span(unquoted, "value=", KEYWORD), comment(unquoted, unquoted.lastIndexOf('#'))),
            RegexHighlighter.forIniLike().highlight(unquoted));
    }

    @Test
    void propertiesAndEditorConfigOnlyRecognizeWholeLineCommentsAndKeepQuotesLiteral() {
        String value = "value=\"text\" # literal; still a value";
        for (String fileName : List.of("settings.properties", ".editorconfig")) {
            assertEquals(List.of(span(value, "value=", KEYWORD)),
                RegexHighlighter.forFileName(fileName).highlight(value), fileName);
            String comment = " \t# value=\"text\"";
            assertEquals(List.of(comment(comment, comment.indexOf('#'))),
                RegexHighlighter.forFileName(fileName).highlight(comment), fileName);
        }
        String propertiesComment = " \t! value=\"text\"";
        assertEquals(List.of(comment(propertiesComment, propertiesComment.indexOf('!'))),
            RegexHighlighter.forFileName("settings.properties").highlight(propertiesComment));
        String editorConfigComment = " \t; value=\"text\"";
        assertEquals(List.of(comment(editorConfigComment, editorConfigComment.indexOf(';'))),
            RegexHighlighter.forFileName(".editorconfig").highlight(editorConfigComment));
    }

    @Test
    void dockerfileCommentsOccupyTheirOwnLineAndHashesInsideInstructionsRemainLiteral() {
        String instruction = "ENV VALUE=\"hash # literal\" # instruction argument";
        assertEquals(List.of(span(instruction, "ENV", KEYWORD), span(instruction, "\"hash # literal\"", STRING)),
            RegexHighlighter.forDockerfile().highlight(instruction));
        String comment = " \t# ENV VALUE=\"hash # comment\"";
        assertEquals(List.of(comment(comment, comment.indexOf('#'))), RegexHighlighter.forDockerfile().highlight(comment));
    }

    @Test
    void tomlLiteralStringsKeepBackslashesAndOnlyTripleQuotesContinueAcrossLines() {
        String literal = "value = 'C:\\' # actual";
        assertEquals(List.of(span(literal, "value =", KEYWORD), span(literal, "'C:\\'", STRING),
            comment(literal, literal.indexOf('#'))), RegexHighlighter.forToml().highlight(literal));
        DocumentSnapshot ordinary = document("value = \"unclosed", "next = 7 # actual");
        assertEquals(List.of(span(ordinary.line(1), "next =", KEYWORD), span(ordinary.line(1), "7", NUMBER),
            comment(ordinary.line(1), ordinary.line(1).indexOf('#'))),
            highlight(highlighter("settings.toml"), ordinary, 1));
        for (String delimiter : List.of("\"\"\"", "'''")) {
            DocumentSnapshot multiline = document("value = " + delimiter, "# still quoted", delimiter + " # actual");
            ContextualSyntaxHighlighter highlighter = highlighter("settings.toml");
            assertEquals(List.of(new TokenSpan(0, delimiter.length(), STRING),
                comment(multiline.line(2), multiline.line(2).indexOf('#'))), highlight(highlighter, multiline, 2));
            assertEquals(string(multiline.line(1)), highlight(highlighter, multiline, 1));
        }
    }

    @Test
    void unterminatedShellAndEnvStringsContinueAndCloseBeforeARealComment() {
        for (String fileName : List.of("script.sh", ".env")) {
            DocumentSnapshot document = document("\"unclosed", "# still quoted\" # actual \"text\"");
            assertEquals(List.of(new TokenSpan(0, document.line(1).indexOf('"') + 1, STRING),
                comment(document.line(1), document.line(1).lastIndexOf('#'))),
                highlight(highlighter(fileName), document, 1), fileName);
        }
    }

    @Test
    void pythonCommentMarkersStayInsideStringsAndFollowingCodeKeepsItsStyle() {
        for (String quoted : List.of(
            "\"hash # and // /* @decorator\"", "'hash # and // /* @decorator'",
            "\"escaped \\\" # marker\"", "'escaped \\' # marker'"
        )) {
            String line = "value = " + quoted + "; return 7 # actual \"comment\" @decorator";
            assertEquals(
                List.of(span(line, quoted, STRING), span(line, "return", KEYWORD), span(line, "7", NUMBER),
                    comment(line, line.lastIndexOf('#'))),
                RegexHighlighter.forPython().highlight(line), line
            );
        }
    }

    @Test
    void pythonRawStringQuotesStillRespectEscapedDelimiters() {
        String quoted = "\"escaped \\\" # marker\"";
        String line = "value = r" + quoted + "; return 7 # actual";

        assertEquals(
            List.of(span(line, quoted, STRING), span(line, "return", KEYWORD), span(line, "7", NUMBER),
                comment(line, line.lastIndexOf('#'))),
            RegexHighlighter.forPython().highlight(line)
        );
    }

    @Test
    void yamlQuotesRespectTheirOwnEscapeRules() {
        for (String quoted : List.of(
            "\"hash # and // /*\"", "'hash # and // /*'",
            "\"escaped \\\" # marker\"", "'doubled '' # marker'", "'C:\\'"
        )) {
            String line = "key: " + quoted + " # actual \"comment\" true";
            assertEquals(
                List.of(span(line, "key:", KEYWORD), span(line, quoted, STRING),
                    comment(line, line.lastIndexOf('#'))),
                RegexHighlighter.forYaml().highlight(line), line
            );
        }
    }

    @Test
    void sqlQuotesUseDoubledDelimitersAndDoNotTreatBackslashesAsEscapes() {
        for (String quoted : List.of("'-- marker'", "'doubled '' -- marker'", "'C:\\'",
            "\"-- identifier\"", "\"doubled \"\" -- identifier\"", "\"C:\\\"",
            "'/* marker */'", "'doubled '' /* marker'", "\"/* identifier */\"",
            "\"doubled \"\" /* identifier\"")) {
            String line = "select " + quoted + ", 7 -- actual 'comment'";
            assertEquals(
                List.of(span(line, "select", KEYWORD), span(line, quoted, STRING), span(line, "7", NUMBER),
                    comment(line, line.lastIndexOf("--"))),
                RegexHighlighter.forSql().highlight(line), line
            );
        }
    }

    @Test
    void sqlBlockCommentsCoverTheirContentsAndResumeCodeOnTheSameLine() {
        String line = "SELECT 7 /* SELECT ( 'ignored' */ , 8 -- actual";

        assertEquals(
            List.of(span(line, "SELECT", KEYWORD), span(line, "7", NUMBER),
                new TokenSpan(line.indexOf("/*"), line.indexOf("*/") + 2, COMMENT),
                span(line, "8", NUMBER), comment(line, line.indexOf("--"))),
            RegexHighlighter.forSql().highlight(line)
        );
    }

    @Test
    void sqlBlockCommentsCarryAcrossLinesAndResumeCodeWhenRequestedOutOfOrder() {
        DocumentSnapshot document = document(
            "SELECT 7; /* open", "([)] SELECT '/*' -- )", "*/ SELECT 8;", "SELECT 9;"
        );
        ContextualSyntaxHighlighter highlighter = highlighter("test.sql");

        assertEquals(
            List.of(span(document.line(3), "SELECT", KEYWORD), span(document.line(3), "9", NUMBER)),
            highlight(highlighter, document, 3)
        );
        assertEquals(List.of(comment(document.line(1), 0)), highlight(highlighter, document, 1));
        assertEquals(
            List.of(new TokenSpan(0, 2, COMMENT), span(document.line(2), "SELECT", KEYWORD),
                span(document.line(2), "8", NUMBER)),
            highlight(highlighter, document, 2)
        );
        assertEquals(
            List.of(span(document.line(0), "SELECT", KEYWORD), span(document.line(0), "7", NUMBER),
                comment(document.line(0), document.line(0).indexOf("/*"))),
            highlight(highlighter, document, 0)
        );
    }

    @Test
    void sqlBlockCommentOpenersInsideQuotedValuesAndLineCommentsDoNotCarryAcrossLines() {
        for (String quoted : List.of("'/* marker'", "'doubled '' /* marker'",
            "\"/* identifier\"", "\"doubled \"\" /* identifier\"")) {
            DocumentSnapshot document = document("SELECT " + quoted + "; -- /* ignored", "SELECT 7;");
            ContextualSyntaxHighlighter highlighter = highlighter("test.sql");

            assertEquals(
                List.of(span(document.line(1), "SELECT", KEYWORD), span(document.line(1), "7", NUMBER)),
                highlight(highlighter, document, 1), quoted
            );
            assertEquals(
                List.of(span(document.line(0), "SELECT", KEYWORD), span(document.line(0), quoted, STRING),
                    comment(document.line(0), document.line(0).indexOf("--"))),
                highlight(highlighter, document, 0), quoted
            );
        }
    }

    @Test
    void yamlPlainScalarQuotesNeverOpenStringsAndHashesNeedSeparation() {
        for (String plain : List.of("don't", "plain 'quote", "plain \"quote", "value#fragment", "plain, 'quote")) {
            String line = "key: " + plain + " # actual";
            DocumentSnapshot document = document(line, "next: true # next comment");
            ContextualSyntaxHighlighter highlighter = highlighter("test.yaml");

            assertEquals(
                List.of(span(line, "key:", KEYWORD), comment(line, line.lastIndexOf('#'))),
                highlight(highlighter, document, 0), line
            );
            assertEquals(
                List.of(span(document.line(1), "next:", KEYWORD), span(document.line(1), "true", TYPE),
                    comment(document.line(1), document.line(1).indexOf('#'))),
                highlight(highlighter, document, 1), line
            );
        }
        assertEquals(List.of(span("key: value#fragment", "key:", KEYWORD)),
            RegexHighlighter.forYaml().highlight("key: value#fragment"));
    }

    @Test
    void yamlFlowNodesAndNodePropertiesStillRecognizeQuotedScalars() {
        for (String prefix : List.of("key: &anchor ", "key: !!str ", "key: !<tag:example.org,str> ", "- ")) {
            String quoted = "'quoted # text'";
            String line = prefix + quoted + " # actual";
            List<TokenSpan> expected = prefix.startsWith("key:")
                ? List.of(span(line, "key:", KEYWORD), span(line, quoted, STRING), comment(line, line.lastIndexOf('#')))
                : List.of(span(line, quoted, STRING), comment(line, line.lastIndexOf('#')));
            assertEquals(expected, RegexHighlighter.forYaml().highlight(line), line);
        }
        String flow = "{\"key#\":['value#', \"other#\"]} # actual";
        assertEquals(
            List.of(span(flow, "\"key#\"", STRING), span(flow, "'value#'", STRING),
                span(flow, "\"other#\"", STRING), comment(flow, flow.lastIndexOf('#'))),
            RegexHighlighter.forYaml().highlight(flow)
        );
    }

    @Test
    void pythonAndYamlDoNotOpenCStyleBlockOrSlashLineComments() {
        String python = "return 7 // 8 /* 9 */ # actual";
        String yaml = "key: plain // /* marker */ # actual";

        assertEquals(
            List.of(span(python, "return", KEYWORD), span(python, "7", NUMBER), span(python, "8", NUMBER),
                span(python, "9", NUMBER), comment(python, python.indexOf('#'))),
            RegexHighlighter.forPython().highlight(python)
        );
        assertEquals(
            List.of(span(yaml, "key:", KEYWORD), comment(yaml, yaml.indexOf('#'))),
            RegexHighlighter.forYaml().highlight(yaml)
        );
    }

    @Test
    void pythonTripleQuotedStringsRetainCommentMarkersAcrossLinesRequestedOutOfOrder() {
        for (String delimiter : List.of("\"\"\"", "'''")) {
            DocumentSnapshot document = document(
                "value = " + delimiter, "# // /* @decorator return 7", delimiter + "; return 8 # actual", "return 9"
            );
            ContextualSyntaxHighlighter highlighter = highlighter("test.py");

            assertEquals(code(document.line(3), "9"), highlight(highlighter, document, 3));
            assertEquals(string(document.line(1)), highlight(highlighter, document, 1));
            assertEquals(
                List.of(new TokenSpan(0, delimiter.length(), STRING), span(document.line(2), "return", KEYWORD),
                    span(document.line(2), "8", NUMBER), comment(document.line(2), document.line(2).indexOf('#'))),
                highlight(highlighter, document, 2)
            );
        }
    }

    @Test
    void ordinaryPythonStringsContinueOnlyWithAnUnescapedBackslash() {
        DocumentSnapshot continued = document("value = \"first\\", "# quoted\"; return 7 # actual", "return 8");
        ContextualSyntaxHighlighter highlighter = highlighter("test.py");

        assertEquals(code(continued.line(2), "8"), highlight(highlighter, continued, 2));
        assertEquals(
            List.of(new TokenSpan(0, continued.line(1).indexOf('"') + 1, STRING),
                span(continued.line(1), "return", KEYWORD), span(continued.line(1), "7", NUMBER),
                comment(continued.line(1), continued.line(1).lastIndexOf('#'))),
            highlight(highlighter, continued, 1)
        );
        for (String firstLine : List.of("value = \"unclosed", "value = \"two backslashes\\\\")) {
            DocumentSnapshot notContinued = document(firstLine, "return 7 # actual");
            assertEquals(
                List.of(span(notContinued.line(1), "return", KEYWORD), span(notContinued.line(1), "7", NUMBER),
                    comment(notContinued.line(1), notContinued.line(1).indexOf('#'))),
                highlight(highlighter("test.py"), notContinued, 1)
            );
        }
    }

    @Test
    void yamlAndSqlQuotedValuesContinueAcrossLinesAndResumeAfterTheirClosingQuote() {
        for (String fileName : List.of("test.yaml", "test.sql")) {
            for (String delimiter : List.of("\"", "'")) {
                boolean yaml = fileName.endsWith("yaml");
                DocumentSnapshot document = document(
                    "value = " + delimiter, "# -- quoted", " \t", "end" + delimiter + (yaml ? " # actual" : " -- actual")
                );
                if (yaml) {
                    document = document("key: " + delimiter, document.line(1), document.line(2), document.line(3));
                }
                ContextualSyntaxHighlighter highlighter = highlighter(fileName);

                assertEquals(
                    List.of(new TokenSpan(0, "end".length() + 1, STRING),
                        comment(document.line(3), yaml ? document.line(3).indexOf('#') : document.line(3).indexOf("--"))),
                    highlight(highlighter, document, 3), fileName + delimiter
                );
                assertEquals(string(document.line(1)), highlight(highlighter, document, 1), fileName + delimiter);
                assertEquals(string(document.line(2)), highlight(highlighter, document, 2), fileName + delimiter);
            }
        }
    }

    @Test
    void yamlFlowContextSurvivesMultilineQuotedValuesAndComments() {
        DocumentSnapshot document = document("key: [\"open", "end\", # actual", "'next # quoted', \"last # quoted\"] # actual");
        ContextualSyntaxHighlighter highlighter = highlighter("test.yaml");

        assertEquals(
            List.of(span(document.line(2), "'next # quoted'", STRING), span(document.line(2), "\"last # quoted\"", STRING),
                comment(document.line(2), document.line(2).lastIndexOf('#'))),
            highlight(highlighter, document, 2)
        );
        assertEquals(
            List.of(new TokenSpan(0, "end\"".length(), STRING), comment(document.line(1), document.line(1).indexOf('#'))),
            highlight(highlighter, document, 1)
        );
    }

    @Test
    void yamlFlowMappingsRecognizeCollectionsImmediatelyAfterTheirColon() {
        String sequence = "{key:[\"hello # string\", \"tail\"]} # actual";
        assertEquals(
            List.of(span(sequence, "\"hello # string\"", STRING), span(sequence, "\"tail\"", STRING),
                comment(sequence, sequence.lastIndexOf('#'))),
            RegexHighlighter.forYaml().highlight(sequence)
        );
        String mapping = "{key:{inner: \"hello # string\"}} # actual";
        assertEquals(
            List.of(span(mapping, "\"hello # string\"", STRING), comment(mapping, mapping.lastIndexOf('#'))),
            RegexHighlighter.forYaml().highlight(mapping)
        );
    }

    @Test
    void yamlIndentedPlainScalarQuotesDoNotOpenStringsOrLeakIntoTheNextNode() {
        for (String quote : List.of("\"", "'")) {
            for (String opening : List.of("key: plain", "- plain", "- key: plain")) {
                String continuation = (opening.startsWith("- key") ? "    " : "  ") + quote + "text # comment";
                DocumentSnapshot document = document(opening, "", continuation, "next: true # next comment");
                ContextualSyntaxHighlighter highlighter = highlighter("test.yaml");

                assertEquals(
                    List.of(span(document.line(3), "next:", KEYWORD), span(document.line(3), "true", TYPE),
                        comment(document.line(3), document.line(3).indexOf('#'))),
                    highlight(highlighter, document, 3), opening + quote
                );
                assertEquals(List.of(comment(continuation, continuation.indexOf('#'))),
                    highlight(highlighter, document, 2), opening + quote);
            }
        }
        DocumentSnapshot flow = document("key: [plain", "  \"text # comment", ", \"next # string\"]");
        ContextualSyntaxHighlighter highlighter = highlighter("test.yaml");
        assertEquals(List.of(span(flow.line(2), "\"next # string\"", STRING)), highlight(highlighter, flow, 2));
        assertEquals(List.of(comment(flow.line(1), flow.line(1).indexOf('#'))), highlight(highlighter, flow, 1));
    }

    @Test
    void yamlPlainContinuationResetsForDedentedQuotedKeysAndEmptyMappingValues() {
        String next = "  \"quoted # key\": true # actual";
        DocumentSnapshot mappedSequence = document("- key: plain", "    plain continuation", next);
        assertEquals(
            List.of(span(next, "\"quoted # key\"", STRING), span(next, "true", TYPE), comment(next, next.lastIndexOf('#'))),
            highlight(highlighter("test.yaml"), mappedSequence, 2)
        );
        String quotedValue = "  \"quoted # value\" # actual";
        DocumentSnapshot emptyValue = document("key:", quotedValue);
        assertEquals(
            List.of(span(quotedValue, "\"quoted # value\"", STRING), comment(quotedValue, quotedValue.lastIndexOf('#'))),
            highlight(highlighter("test.yaml"), emptyValue, 1)
        );
    }

    @Test
    void revisionsAndDocumentIdentitiesInvalidateCachedStringState() {
        for (String fileName : List.of("test.py", "test.yaml", "test.sql")) {
            String opener = fileName.endsWith("py") ? "value = \"\"\"" : fileName.endsWith("yaml") ? "key: '" : "select '";
            String plain = fileName.endsWith("yaml") ? "key: true" : fileName.endsWith("sql") ? "select 7" : "return 7";
            DocumentSnapshot quoted = document(opener, plain);
            DocumentSnapshot revised = new DocumentSnapshot(DOCUMENT_ID, 2, List.of("", plain));
            DocumentSnapshot different = new DocumentSnapshot(new DocumentId("different"), 1, revised.lines());
            ContextualSyntaxHighlighter highlighter = highlighter(fileName);
            List<TokenSpan> expected = RegexHighlighter.forFileName(fileName).highlight(plain);

            assertEquals(string(plain), highlight(highlighter, quoted, 1), fileName);
            assertEquals(expected, highlighter.highlight(plain), fileName);
            assertEquals(string(plain), highlight(highlighter, quoted, 1), fileName);
            assertEquals(expected, highlight(highlighter, revised, 1), fileName);
            assertEquals(string(plain), highlight(highlighter, quoted, 1), fileName);
            assertEquals(expected, highlight(highlighter, different, 1), fileName);
        }
    }

    @Test
    void markdownFencesApplyQuotedCommentsAndDoNotLeakUnclosedStringsToTheNextFence() {
        for (String language : List.of("python", "yaml", "sql")) {
            String opener = language.equals("python") ? "value = \"\"\"" : language.equals("yaml") ? "key: '" : "select '";
            String quoted = "# -- return 7";
            String plain = language.equals("yaml") ? "key: true # actual" : language.equals("sql") ? "select 7 -- actual" : "return 7 # actual";
            DocumentSnapshot document = document("```" + language, opener, quoted, "```", "```" + language, plain, "```");
            ContextualSyntaxHighlighter highlighter = highlighter("README.md");
            List<TokenSpan> expected = RegexHighlighter.forFileName("test." + (language.equals("python") ? "py" : language)).highlight(plain);

            assertEquals(expected, highlight(highlighter, document, 5), language);
            assertEquals(string(quoted), highlight(highlighter, document, 2), language);
            assertEquals(expected, highlight(highlighter, document, 5), language);
        }
    }

    private static ContextualSyntaxHighlighter highlighter(String fileName) {
        return (ContextualSyntaxHighlighter) RegexHighlighter.forFileName(fileName);
    }

    private static DocumentSnapshot document(String... lines) {
        return new DocumentSnapshot(DOCUMENT_ID, 1, List.of(lines));
    }

    private static List<TokenSpan> highlight(ContextualSyntaxHighlighter highlighter, DocumentSnapshot document, int lineIndex) {
        return highlighter.highlight(document, lineIndex, document.line(lineIndex));
    }

    private static List<TokenSpan> code(String line, String number) {
        return List.of(span(line, "return", KEYWORD), span(line, number, NUMBER));
    }

    private static List<TokenSpan> string(String line) {
        return List.of(new TokenSpan(0, line.length(), STRING));
    }

    private static TokenSpan comment(String line, int start) {
        return new TokenSpan(start, line.length(), COMMENT);
    }

    private static TokenSpan span(String line, String text, SemanticStyleKey style) {
        int start = line.indexOf(text);
        return new TokenSpan(start, start + text.length(), style);
    }
}
