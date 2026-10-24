package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.DocumentView;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.HighlightRequest;
import dev.tuink.editor.highlight.SyntaxHighlighter;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.TokenSpan;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedHighlightingTest {
    private static final SemanticStyleKey COMMENT = SemanticStyleKey.COMMENT;
    private static final SemanticStyleKey STRING = SemanticStyleKey.STRING;

    @Test
    void visibleSpansRetainOriginalOffsetsAndOrdinaryLanguageStyles() {
        for (String file : List.of("data.csv", "data.json", "Main.java", "module.py", "notes.md", "page.html")) {
            List<String> lines = file.endsWith("md")
                ? List.of("# Heading", "```python", "\"\"\"", "documentation )", "\"\"\"", "def value(): pass", "```", "[link](url)")
                : List.of("public return 12; // comment", "\"quoted 42\", 19", "/* open", "return 7;", "*/ return 8;", "@cached def value():");
            DocumentSnapshot document = new DocumentSnapshot(new DocumentId(file), 1, lines);
            SyntaxHighlighter highlighter = RegexHighlighter.forFileName(file);
            for (int line = 0; line < document.lineCount(); line++) {
                String text = document.line(line);
                HighlightRequest request = new HighlightRequest(2, text.length(), 4096, 256, 1_000_000);
                List<TokenSpan> complete = highlighter instanceof ContextualSyntaxHighlighter contextual
                    ? contextual.highlight(document, line, text) : highlighter.highlight(text);
                List<TokenSpan> actual = highlighter instanceof ContextualSyntaxHighlighter contextual
                    ? contextual.highlight(document, line, text, request) : highlighter.highlight(text, request);
                assertEquals(request.clipSpans(complete), actual, file + ":" + line);
            }
        }
    }

    @Test
    void statelessRegexDoesNotMistakeAnIncompleteQuotedPrefixForACompleteLine() {
        String text = "\"" + "1,".repeat(100_000) + "\"";
        HighlightRequest request = new HighlightRequest(0, 32, 64, 32, 1_000_000);
        for (SyntaxHighlighter highlighter : List.of(RegexHighlighter.forJson(), RegexHighlighter.forCsv())) {
            assertEquals(List.of(), highlighter.highlight(text, request));
        }
    }

    @Test
    void denseOrdinaryLinesProduceOnlyVisibleSpansAndRespectOutputAndWorkLimits() {
        String text = "1,".repeat(30_000);
        SyntaxHighlighter highlighter = RegexHighlighter.forCsv();
        HighlightRequest request = new HighlightRequest(10_000, 10_080, 65_536, 64, 2_000_000);
        List<TokenSpan> spans = highlighter.highlight(text, request);
        assertEquals(40, spans.size());
        assertEquals(10_000, spans.getFirst().start());
        assertEquals(10_079, spans.getLast().end());
        assertEquals(List.of(), highlighter.highlight(text, new HighlightRequest(0, text.length(), 65_536, 4, 2_000_000)));
        assertEquals(List.of(), highlighter.highlight(text, new HighlightRequest(0, 80, 65_536, 64, 4)));
        assertEquals(List.of(), highlighter.highlight(text, new HighlightRequest(0, 80, 65_536, 0, 2_000_000)));
    }

    @Test
    void lexicalPrefixesKeepStringsAndCommentsWithoutInventingContextAfterATruncatedLine() {
        for (String opening : List.of("/*", "\"")) {
            String text = opening + "return 7; ".repeat(1000);
            SyntaxHighlighter highlighter = RegexHighlighter.forJava();
            assertEquals(List.of(new TokenSpan(4, 28, opening.equals("/*") ? COMMENT : STRING)),
                highlighter.highlight(text, new HighlightRequest(4, 28, 64, 8, 4096)));
            assertEquals(List.of(), highlighter.highlight(text, new HighlightRequest(100, 128, 64, 8, 4096)));
        }
        DocumentSnapshot document = new DocumentSnapshot(new DocumentId("prefix"), 1,
            List.of("/*", "return 7; ".repeat(1000) + "*/", "return 8;"));
        ContextualSyntaxHighlighter highlighter = (ContextualSyntaxHighlighter) RegexHighlighter.forJava();
        HighlightRequest request = new HighlightRequest(0, 24, 64, 8, 4096);
        assertEquals(List.of(new TokenSpan(0, 24, COMMENT)), highlighter.highlight(document, 1, document.line(1), request));
        assertEquals(List.of(), highlighter.highlight(document, 2, document.line(2), request));
    }

    @Test
    void distantEmptyLinesConsumeTheContextBudgetInsteadOfGrowingAnUnboundedCache() {
        for (String file : List.of("Main.java", "notes.md")) {
            AtomicInteger reads = new AtomicInteger();
            DocumentView document = new DocumentView() {
                @Override public DocumentId documentId() { return new DocumentId(file); }
                @Override public long revision() { return 1; }
                @Override public int lineCount() { return 10_000_000; }
                @Override public String line(int index) { reads.incrementAndGet(); return ""; }
            };
            ContextualSyntaxHighlighter highlighter = (ContextualSyntaxHighlighter) RegexHighlighter.forFileName(file);
            assertEquals(List.of(), highlighter.highlight(document, document.lineCount() - 1, "return 7;",
                new HighlightRequest(0, 9, 64, 16, 4096)));
            assertTrue(reads.get() <= 65, file + " traversed " + reads.get() + " lines");
        }
    }

    @Test
    void linearLexerSkipsCannotEstablishCachedContextWithoutPayingForTheCharacters() {
        for (String file : List.of("app.js", "data.yaml")) {
            String prior = (file.endsWith("js") ? "a" : " ").repeat(60_000);
            DocumentSnapshot document = new DocumentSnapshot(new DocumentId(file), 1, List.of(prior, "return 7;"));
            ContextualSyntaxHighlighter highlighter = (ContextualSyntaxHighlighter) RegexHighlighter.forFileName(file);
            assertEquals(List.of(), highlighter.highlight(document, 1, document.line(1),
                new HighlightRequest(0, 9, 65_536, 16, 3)));
            // If the first call incorrectly cached a complete predecessor state,
            // this smaller prefix limit would now permit coloring the target.
            assertEquals(List.of(), highlighter.highlight(document, 1, document.line(1),
                new HighlightRequest(0, 9, 16, 16, 4096)));
        }
    }

    @Test
    void markdownFenceContextKeepsTheActualEmbeddedStartAndInvalidatesAfterAnEdit() {
        List<String> preamble = List.of("Prose", "```python", "\"\"\"", "documentation )", "\"\"\"", "```", "Prose");
        DocumentId id = new DocumentId("markdown-offset");
        DocumentSnapshot original = new DocumentSnapshot(id, 1, preamble);
        ContextualSyntaxHighlighter highlighter = (ContextualSyntaxHighlighter) RegexHighlighter.forFileName("notes.md");
        HighlightRequest request = new HighlightRequest(0, 15, 256, 16, 8192);
        assertEquals(List.of(new TokenSpan(0, 15, STRING)),
            highlighter.highlight(original, 3, original.line(3), request));
        DocumentSnapshot edited = new DocumentSnapshot(id, 2,
            List.of("Prose", "```python", "pass", "return 7;", "pass", "```", "Prose"));
        List<TokenSpan> expected = RegexHighlighter.forPython().highlight(edited.line(3));
        assertEquals(expected, highlighter.highlight(edited, 3, edited.line(3), request));
    }

    @Test
    void largeUnknownFenceTagsCountTowardRetainedContextWeight() {
        DocumentSnapshot document = new DocumentSnapshot(new DocumentId("large-fence-tag"), 1,
            List.of("```" + "unknown".repeat(800), "return 7;", "```"));
        ContextualSyntaxHighlighter highlighter = (ContextualSyntaxHighlighter) RegexHighlighter.forFileName("notes.md");
        assertEquals(RegexHighlighter.withDefaults().highlight(document.line(1)),
            highlighter.highlight(document, 1, document.line(1), new HighlightRequest(0, 9, 8192, 16, 1_000_000)));
        // The large tag must not survive as a cheap checkpoint: reaching this
        // line again requires parsing its opening fence within the scan budget.
        assertEquals(List.of(), highlighter.highlight(document, 1, document.line(1),
            new HighlightRequest(0, 9, 64, 16, 4096)));
    }
}
