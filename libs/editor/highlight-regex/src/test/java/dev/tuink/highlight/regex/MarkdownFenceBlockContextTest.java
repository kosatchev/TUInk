package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.DocumentView;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.LexicalScanner;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.TokenSpan;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownFenceBlockContextTest {
    private static final SemanticStyleKey KEYWORD = SemanticStyleKey.KEYWORD;

    @Test
    void renderingAndScanningRecognizeTheSameNestedFenceBoundaries() {
        record Example(List<String> prefix, String indent, boolean code) {}
        for (Example example : List.of(
            new Example(List.of("- parent", "    ```python"), "    ", true),
            new Example(List.of("- parent", "", "    ~~~python"), "    ", true),
            new Example(List.of("- ```python"), "  ", true),
            new Example(List.of("123. ~~~python"), "     ", true),
            new Example(List.of("- parent", "    - child", "        ```python"), "        ", true),
            new Example(List.of("- parent", "    ````python", "    ```"), "    ", true),
            new Example(List.of("- parent", "    ```python", "    ~~~"), "    ", true),
            new Example(List.of("- parent", "    ```python", "    ```not-a-close"), "    ", true),
            new Example(List.of("- parent", "    ```python", "    ```"), "    ", false),
            new Example(List.of("- parent", "    ```python"), "", false),
            new Example(List.of("- - -", "    ```python"), "    ", false),
            new Example(List.of("```python`invalid"), "", false)
        )) {
            ContextualSyntaxHighlighter highlighter = highlighter();
            String body = example.indent() + "1. return";
            List<String> lines = new ArrayList<>(example.prefix());
            lines.add(body);
            DocumentSnapshot document = new DocumentSnapshot(new DocumentId("nested-block-test"), 0, lines);
            assertEquals(example.code(), highlighter.highlight(document, lines.size() - 1, body)
                .contains(new TokenSpan(body.indexOf("return"), body.length(), KEYWORD)), example.toString());

            LexicalScanner scanner = highlighter.lexicalScanner();
            LexicalScanner.State state = scanPrefix(scanner, example.prefix());
            String comment = example.indent() + "# ([)]";
            LexicalScanner.Result result = scanner.scan(comment, comment.length(), state);
            assertEquals(example.code(), excluded(result, comment.indexOf('(')), example.toString());
        }
    }

    @Test
    void blanksPreserveEmbeddedStringsButClosersAndContainerDedentsReleaseThem() {
        for (String ending : List.of("    ```", "1. outside")) {
            LexicalScanner scanner = highlighter().lexicalScanner();
            LexicalScanner.State state = scanPrefix(scanner,
                List.of("- parent", "    ```python", "    \"\"\"", ""));
            String content = "    ([)]";
            LexicalScanner.Result body = scanner.scan(content, content.length(), state);
            assertTrue(excluded(body, content.indexOf('(')));
            LexicalScanner.Result end = scanner.scan(ending, ending.length(), body.endState());
            String prose = "1. ([)]";
            assertFalse(excluded(scanner.scan(prose, prose.length(), end.endState()), prose.indexOf('(')));
        }
    }

    @Test
    void artificialPrefixEndCannotCloseAFenceOrConsumeItsUnscannedTail() {
        LexicalScanner scanner = highlighter().lexicalScanner();
        LexicalScanner.State state = scanPrefix(scanner, List.of("- parent", "    ```python"));
        String incompleteCloser = "    ```" + " ".repeat(2_000_000) + "invalid";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            LexicalScanner.Result partial = scanner.scan(incompleteCloser, 32, state);
            assertEquals(32, partial.scannedCodeUnits());
            assertTrue(partial.excludedRanges().stream().allMatch(range -> range.end() <= 32));
            assertFalse(partial.contextKnown());
            String next = "    # ([)]";
            LexicalScanner.Result afterPartial = scanner.scan(next, next.length(), partial.endState());
            assertFalse(afterPartial.contextKnown());
            assertEquals(0, afterPartial.scannedCodeUnits());
            assertTrue(afterPartial.excludedRanges().isEmpty());
            LexicalScanner.Result zero = scanner.scan(incompleteCloser, 0, state);
            assertEquals(0, zero.scannedCodeUnits());
            assertFalse(zero.contextKnown());
            assertFalse(scanner.scan(next, next.length(), zero.endState()).contextKnown());
        });
    }

    @Test
    void editingTheEndOfANestedFenceKeepsTheUnchangedContainerAndEmbeddedPrefix() {
        List<String> lines = new ArrayList<>(Collections.nCopies(20_000, "    return 7;"));
        lines.set(0, "- parent");
        lines.set(1, "    ```java");
        lines.set(2, "    /*");
        CountingDocument document = new CountingDocument(lines);
        ContextualSyntaxHighlighter highlighter = highlighter();
        int last = document.lineCount() - 1;
        highlighter.highlight(document, last, document.model.getLine(last));
        document.model.insertChar(new Position(last, 0), ' ');
        document.firstReadable = last;

        assertEquals(List.of(new TokenSpan(0, document.model.getLine(last).length(), SemanticStyleKey.COMMENT)),
            highlighter.highlight(document, last, document.model.getLine(last)));
    }

    @Test
    void cacheRetainsListStateBeforeAnOpeningAndInvalidatesChangedContainers() {
        CountingDocument document = new CountingDocument(List.of("- parent", "    text", "    ```python", "    return 7"));
        ContextualSyntaxHighlighter highlighter = highlighter();
        highlighter.highlight(document, 1, document.model.getLine(1));
        document.model.insertChar(new Position(2, document.model.getLine(2).length()), ' ');
        document.firstReadable = 2;
        String body = document.model.getLine(3);
        TokenSpan keyword = new TokenSpan(4, 10, KEYWORD);
        assertTrue(highlighter.highlight(document, 3, body).contains(keyword));

        document.firstReadable = 0;
        document.model.deleteRange(new Position(0, 0), new Position(0, 2));
        assertFalse(highlighter.highlight(document, 3, body).contains(keyword));
    }

    private static ContextualSyntaxHighlighter highlighter() {
        return (ContextualSyntaxHighlighter) RegexHighlighter.forFileName("notes.md");
    }

    private static boolean excluded(LexicalScanner.Result result, int offset) {
        return result.excludedRanges().stream().anyMatch(range -> range.start() <= offset && range.end() > offset);
    }

    private static LexicalScanner.State scanPrefix(LexicalScanner scanner, List<String> lines) {
        LexicalScanner.State state = null;
        for (String line : lines) {
            state = scanner.scan(line, line.length(), state).endState();
        }
        return state;
    }

    private static final class CountingDocument implements DocumentView {
        private final TextModel model = new TextModel(new SimpleLineBuffer());
        private int firstReadable;
        private CountingDocument(List<String> lines) { model.replaceLines(lines); }
        @Override public DocumentId documentId() { return new DocumentId("nested-incremental"); }
        @Override public long revision() { return model.revision(); }
        @Override public int lineCount() { return model.lineCount(); }
        @Override public int firstChangedLineSince(long earlierRevision) { return model.firstChangedLineSince(earlierRevision); }
        @Override public String line(int line) {
            assertTrue(line >= firstReadable, "Unchanged Markdown prefix was read at " + line);
            return model.getLine(line);
        }
    }
}
