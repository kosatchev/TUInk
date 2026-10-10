package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.render.ContextualLineHighlighter;
import dev.tuink.render.LineLexicalScanner;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LexicalScannerTest {
    @Test
    void multilineLanguageLiteralsExcludeTheirContentsAndReleaseFollowingCode() {
        for (LiteralCase example : literalCases()) {
            LineLexicalScanner scanner = scanner(example.fileName());
            LineLexicalScanner.State state = null;
            for (int index = 0; index < example.lines().size(); index++) {
                String line = example.lines().get(index);
                LineLexicalScanner.Result result = scanner.scan(line, line.length(), state);
                assertEquals(line.length(), result.scannedCodeUnits(), example.fileName());
                state = result.endState();
                if (index == 1) {
                    assertEquals(List.of(new LineLexicalScanner.Range(0, line.length())),
                        result.excludedRanges(), example.fileName());
                } else if (index == example.lines().size() - 1) {
                    assertEquals(List.of(), result.excludedRanges(), example.fileName());
                }
            }
        }
    }

    @Test
    void ordinaryQuoteOffsetsUseLexicalEscapingAndDoNotIncludeComments() {
        String line = "\"a\\\"b\" 'x' \"unterminated";
        LineLexicalScanner scanner = scanner("test.java");
        LineLexicalScanner.Result result = scanner.scan(line, line.length(), null);

        assertEquals(List.of(
            new LineLexicalScanner.Quote(0, 5),
            new LineLexicalScanner.Quote(7, 9),
            new LineLexicalScanner.Quote(11, -1)
        ), result.quotes());
        assertEquals(List.of(), scanner.scan("// \"quoted\"", 12, null).quotes());
    }

    @Test
    void perlPunctuationVariablesAndPackageSeparatorsLeaveFollowingBracketsVisible() {
        for (String opening : List.of("my $suffix = $';", "my $separator = $\";", "use Foo'Bar;", "package Foo'Bar;")) {
            LineLexicalScanner scanner = scanner("script.pl");
            LineLexicalScanner.Result first = scanner.scan(opening, opening.length(), null);
            assertEquals(opening.length(), first.scannedCodeUnits(), opening);
            assertEquals(List.of(), first.excludedRanges(), opening);
            assertEquals(List.of(), first.quotes(), opening);

            String following = "return ([value]) # actual \"comment\"";
            LineLexicalScanner.Result next = scanner.scan(following, following.length(), first.endState());
            assertEquals(List.of(new LineLexicalScanner.Range(following.indexOf('#'), following.length())),
                next.excludedRanges(), opening);
            assertEquals(List.of(), next.quotes(), opening);
        }
    }

    @Test
    void truncatedStringsExcludeOnlyTheScannedPrefixAndAreNotDeclaredUnclosed() {
        String line = "\"" + "(".repeat(2_000_000) + "\"";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (String fileName : List.of("test.java", "test.pl")) {
                LineLexicalScanner.Result result = scanner(fileName).scan(line, 64, null);
                assertEquals(64, result.scannedCodeUnits(), fileName);
                assertEquals(List.of(new LineLexicalScanner.Range(0, 64)), result.excludedRanges(), fileName);
                assertEquals(List.of(), result.quotes(), fileName);
            }
        });
    }

    @Test
    void unterminatedPerlQuotesShareABoundedLookaheadBudget() {
        String line = "'" + "\\'".repeat(80_000);
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            LineLexicalScanner.Result result = scanner("script.pl").scan(line, line.length(), null);
            assertTrue(result.scannedCodeUnits() < line.length());
            RegexHighlighter.forPerl().highlight(line);
        });
    }

    @Test
    void longMarkdownFenceInfoIsNormalizedWithoutCopyingEveryPunctuationSuffix() {
        String line = "```python" + ".".repeat(200_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            LineLexicalScanner scanner = scanner("README.md");
            LineLexicalScanner.Result fence = scanner.scan(line, line.length(), null);
            assertEquals(line.length(), fence.scannedCodeUnits());
            String opening = "\"\"\"";
            LineLexicalScanner.Result literal = scanner.scan(opening, opening.length(), fence.endState());
            String content = "([)]";
            assertEquals(List.of(new LineLexicalScanner.Range(0, content.length())),
                scanner.scan(content, content.length(), literal.endState()).excludedRanges());
        });
    }

    @Test
    void malformedJavaScriptAndRustLiteralsHaveABoundedTotalSuffixSearchBudget() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (LiteralCase example : List.of(
                new LiteralCase("test.js", List.of("/[".repeat(40_000))),
                new LiteralCase("test.rs", List.of("'\\u{ ".repeat(20_000)))
            )) {
                String line = example.lines().getFirst();
                LineLexicalScanner.Result result = scanner(example.fileName()).scan(line, line.length(), null);
                assertTrue(result.scannedCodeUnits() < line.length(), example.fileName());
                for (LineLexicalScanner.Range range : result.excludedRanges()) {
                    assertTrue(range.end() <= result.scannedCodeUnits());
                }
                RegexHighlighter.forFileName(example.fileName()).highlight(line);
            }
        });
    }

    static List<LiteralCase> literalCases() {
        return List.of(
            new LiteralCase("test.py", List.of("value = \"\"\"", "([)]", "\"\"\"", "()")),
            new LiteralCase("test.java", List.of("String value = \"\"\"", "([)]", "\"\"\";", "()")),
            new LiteralCase("test.kt", List.of("val value = \"\"\"", "([)]", "\"\"\"", "()")),
            new LiteralCase("test.rs", List.of("let value = r##\"", "([)]\"#", "\"##;", "()")),
            new LiteralCase("test.go", List.of("value := `", "([)]", "`", "()")),
            new LiteralCase("test.js", List.of("const value = `", "([)] ${text}", "`;", "()")),
            new LiteralCase("test.sql", List.of("/*", "([)] ' SELECT", "*/", "()")),
            new LiteralCase("test.sql", List.of("SELECT 1;", "/* ([)] ' SELECT */", "SELECT 2;", "()")),
            new LiteralCase("test.rb", List.of("value = \"", "([)]", "\"", "()")),
            new LiteralCase("test.html", List.of("<!--", "([)]", "-->", "()")),
            new LiteralCase("test.xml", List.of("<!--", "([)]", "-->", "()")),
            new LiteralCase("README.md", List.of("```python", "\"\"\"([)]", "\"\"\"", "```", "()")),
            new LiteralCase("README.md", List.of("```html", "<!-- ([)]", "-->", "```", "()"))
        );
    }

    private static LineLexicalScanner scanner(String fileName) {
        ContextualLineHighlighter highlighter = (ContextualLineHighlighter) RegexHighlighter.forFileName(fileName);
        LineLexicalScanner scanner = highlighter.lexicalScanner();
        assertNotNull(scanner, fileName);
        return scanner;
    }

    record LiteralCase(String fileName, List<String> lines) {
    }
}
