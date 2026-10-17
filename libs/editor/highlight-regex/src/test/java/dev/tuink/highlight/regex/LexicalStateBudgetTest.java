package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.render.ContextualLineHighlighter;
import dev.tuink.render.HighlightRequest;
import dev.tuink.render.LineLexicalScanner;
import java.util.List;
import org.junit.jupiter.api.Test;

class LexicalStateBudgetTest {
    @Test
    void boundedJavascriptContinuationPreservesControlConditionsAndDivision() {
        int depth = CStyleHighlighter.MAX_CONTEXT_DEPTH;
        for (String file : List.of("app.js", "app.ts")) {
            for (String opening : List.of("if (", "call(")) {
                LineLexicalScanner scanner = scanner(file);
                String first = opening + "(".repeat(depth - 1);
                var previous = scanner.scan(first, first.length(), null);
                assertTrue(previous.contextKnown());
                var empty = scanner.scan("", 0, previous.endState());
                assertTrue(empty.contextKnown());
                assertEquals(previous.endState(), empty.endState());
                String closing = ")".repeat(depth) + " /[()]/.test(value);";
                var result = scanner.scan(closing, closing.length(), empty.endState());
                assertTrue(result.contextKnown());
                assertEquals(opening.startsWith("if")
                        ? List.of(new LineLexicalScanner.Range(depth + 1, depth + 7)) : List.of(),
                    result.excludedRanges(), file + " " + opening);
            }
        }
    }

    @Test
    void depthFailureNeverBecomesKnownAgainThroughAnEmptyLineOrClosingParenthesis() {
        for (String file : List.of("app.js", "app.ts")) {
            LineLexicalScanner scanner = scanner(file);
            String first = "(".repeat(CStyleHighlighter.MAX_CONTEXT_DEPTH);
            var previous = scanner.scan(first, first.length(), null);
            assertTrue(previous.contextKnown());
            var failed = scanner.scan("(", 1, previous.endState());
            assertUnknown(failed, 1);
            assertEquals(0, failed.scannedCodeUnits());
            assertUnknown(scanner.scan("", 0, failed.endState()), 0);
            assertUnknown(scanner.scan(") /[()]/", 8, failed.endState()), 8);
        }
    }

    @Test
    void nodeAllocationBudgetAlsoBoundsFlatJavascriptAndRubyBraceReplacement() {
        String javascript = "()".repeat(CStyleHighlighter.MAX_CONTEXT_NODES_PER_SCAN + 1);
        var flat = scanner("app.js").scan(javascript, javascript.length(), null);
        assertUnknown(flat, javascript.length());
        assertEquals(2 * CStyleHighlighter.MAX_CONTEXT_NODES_PER_SCAN, flat.scannedCodeUnits());

        String ruby = "\"#{" + "{".repeat(CStyleHighlighter.MAX_CONTEXT_NODES_PER_SCAN + 1);
        var braces = scanner("app.rb").scan(ruby, ruby.length(), null);
        assertUnknown(braces, ruby.length());
        assertTrue(braces.scannedCodeUnits() < ruby.length());
        String nestedRuby = "\"" + "#{\"".repeat(CStyleHighlighter.MAX_CONTEXT_DEPTH) + "value";
        assertUnknown(scanner("app.rb").scan(nestedRuby, nestedRuby.length(), null), nestedRuby.length());
    }

    @Test
    void quoteAndRangeCapsStopBeforeRetainingUnboundedOutput() {
        String atLimit = "\"\" ".repeat(LineLexicalScanner.MAX_QUOTES);
        var complete = scanner("app.java").scan(atLimit, atLimit.length(), null);
        assertTrue(complete.contextKnown());
        assertEquals(LineLexicalScanner.MAX_QUOTES, complete.quotes().size());
        assertEquals(LineLexicalScanner.MAX_EXCLUDED_RANGES, complete.excludedRanges().size());

        String adjacent = "\"\"".repeat(LineLexicalScanner.MAX_QUOTES + 1);
        assertUnknown(scanner("app.js").scan(adjacent, adjacent.length(), null), adjacent.length());
        String comments = "/*x*/ ".repeat(LineLexicalScanner.MAX_EXCLUDED_RANGES + 1);
        assertUnknown(scanner("app.java").scan(comments, comments.length(), null), comments.length());
        // Rust records a character literal after painting it; exhausting its
        // quote budget must trim that already painted token to the proven prefix.
        String characters = "'x' ".repeat(LineLexicalScanner.MAX_QUOTES + 1);
        assertUnknown(scanner("app.rs").scan(characters, characters.length(), null), characters.length());
    }

    @Test
    void prefixExhaustionIsExplicitEvenWhenAnUnknownContinuationReachesEmptyInput() {
        for (String file : List.of("app.java", "app.js", "app.rb", "notes.md")) {
            LineLexicalScanner scanner = scanner(file);
            assertTrue(scanner.scan("", 0, null).contextKnown());
            var failed = scanner.scan("/*", 0, null);
            assertUnknown(failed, 2);
            assertUnknown(scanner.scan("", 0, failed.endState()), 0);
        }
    }

    @Test
    void permanentContextFailureDoesNotScheduleIdleRetriesAndAnEditRestoresColoring() {
        for (boolean markdown : List.of(false, true)) {
            var id = new DocumentId(markdown ? "markdown-depth" : "javascript-depth");
            var original = new DocumentSnapshot(id, 1, markdown
                ? List.of("```js", "(".repeat(CStyleHighlighter.MAX_CONTEXT_DEPTH + 1), "", ") /[()]/;")
                : List.of("const before = 1;", "(".repeat(CStyleHighlighter.MAX_CONTEXT_DEPTH + 1), "", ") /[()]/;"));
            ContextualLineHighlighter highlighter = highlighter(markdown ? "notes.md" : "app.js");
            HighlightRequest request = new HighlightRequest(0, original.line(3).length(), 65_536, 4096, 1_048_576);
            for (int attempt = 0; attempt < 3; attempt++) {
                var result = highlighter.highlightResult(original, 3, original.line(3), request);
                assertFalse(result.retryPending(), "A permanent state limit cannot resume while idle");
                assertTrue(result.spans().isEmpty());
            }
            var edited = new DocumentSnapshot(id, 2, markdown
                ? List.of("```js", "if (true)", "", "/[()]/;")
                : List.of("const before = 1;", "if (true)", "", "/[()]/;"));
            var result = highlighter.highlightResult(edited, 3, edited.line(3), request);
            assertFalse(result.retryPending());
            assertFalse(result.spans().isEmpty());
            assertEquals(highlighter(markdown ? "notes.md" : "app.js").highlight(edited, 3, edited.line(3), request),
                result.spans());
        }
    }

    @Test
    void markdownContainerDepthIsBoundedBeforeAllocationAndUnknownSurvivesEmptyLines() {
        LineLexicalScanner scanner = scanner("notes.md");
        String atLimit = "- ".repeat(MarkdownFenceHighlighter.MAX_CONTAINER_DEPTH) + "value";
        var known = scanner.scan(atLimit, atLimit.length(), null);
        assertTrue(known.contextKnown());
        assertTrue(scanner.scan("", 0, known.endState()).contextKnown());
        String excessive = "- ".repeat(MarkdownFenceHighlighter.MAX_CONTAINER_DEPTH + 1) + "value";
        var stopped = scanner.scan(excessive, excessive.length(), null);
        assertUnknown(stopped, excessive.length());
        assertUnknown(scanner.scan("", 0, stopped.endState()), 0);

        var id = new DocumentId("markdown-container-limit");
        var original = new DocumentSnapshot(id, 1, List.of("intro", excessive, "", "# Heading"));
        ContextualLineHighlighter highlighter = highlighter("notes.md");
        HighlightRequest request = new HighlightRequest(0, 9, 65_536, 4096, 1_048_576);
        for (int attempt = 0; attempt < 3; attempt++) {
            var result = highlighter.highlightResult(original, 3, original.line(3), request);
            assertTrue(result.spans().isEmpty());
            assertFalse(result.retryPending());
        }
        var visible = highlighter.highlightResult(original, 1, excessive,
            new HighlightRequest(0, excessive.length(), 65_536, 4096, 1_048_576));
        assertTrue(visible.spans().isEmpty());
        assertFalse(visible.retryPending());
        var edited = new DocumentSnapshot(id, 2, List.of("intro", "- item", "", "# Heading"));
        var result = highlighter.highlightResult(edited, 3, edited.line(3), request);
        assertFalse(result.spans().isEmpty());
        assertFalse(result.retryPending());
        assertEquals(highlighter("notes.md").highlight(edited, 3, edited.line(3), request), result.spans());
    }

    private static void assertUnknown(LineLexicalScanner.Result result, int sourceLength) {
        assertFalse(result.contextKnown());
        assertSame(LineLexicalScanner.State.UNKNOWN, result.endState());
        assertTrue(result.scannedCodeUnits() <= sourceLength);
        assertTrue(result.excludedRanges().size() <= LineLexicalScanner.MAX_EXCLUDED_RANGES);
        assertTrue(result.quotes().size() <= LineLexicalScanner.MAX_QUOTES);
        for (var range : result.excludedRanges()) assertTrue(range.end() <= result.scannedCodeUnits());
        for (var quote : result.quotes()) {
            assertTrue(quote.start() < result.scannedCodeUnits());
            assertTrue(quote.end() >= 0 && quote.end() < result.scannedCodeUnits());
        }
    }

    private static ContextualLineHighlighter highlighter(String file) {
        return (ContextualLineHighlighter) RegexHighlighter.forFileName(file);
    }

    private static LineLexicalScanner scanner(String file) {
        return highlighter(file).lexicalScanner();
    }
}
