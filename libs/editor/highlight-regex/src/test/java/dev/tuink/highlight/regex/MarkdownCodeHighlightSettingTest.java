package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.HighlightRequest;
import dev.tuink.editor.highlight.LexicalScanner;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.TokenSpan;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownCodeHighlightSettingTest {
    private static final DocumentId DOCUMENT_ID = new DocumentId("markdown-code-setting");

    @Test
    void disablingCodeStylesPreservesHeadingsAndInlineMarkdown() {
        for (String language : List.of("java", "unknown-language", "")) {
            var document = new DocumentSnapshot(DOCUMENT_ID, 1, List.of(
                "# Heading", "```" + language, "public int value = 7;", "```", "Use `code` and **bold**."
            ));
            var enabled = highlighter(true);
            var disabled = highlighter(false);
            assertFalse(enabled.highlight(document, 2, document.line(2)).isEmpty(), language);
            assertEquals(uniformCode(document, 2), disabled.highlight(document, 2, document.line(2)), language);
            for (int row : List.of(0, 4)) {
                var markup = disabled.highlight(document, row, document.line(row));
                assertFalse(markup.isEmpty(), language);
                assertEquals(enabled.highlight(document, row, document.line(row)), markup, language);
            }
        }
    }

    @Test
    void literalMarkdownInsideADisabledFenceDoesNotBecomeProseHighlighting() {
        var document = new DocumentSnapshot(DOCUMENT_ID, 1, List.of(
            "```markdown", "# Literal heading with `code`", "```", "# Actual heading"
        ));
        var disabled = highlighter(false);
        assertEquals(uniformCode(document, 1), disabled.highlight(document, 1, document.line(1)));
        assertTrue(disabled.highlight(document, 3, document.line(3)).stream()
            .anyMatch(span -> span.styleKey().equals(SemanticStyleKey.MARKUP)));
    }

    @Test
    void codeDisplaySettingDoesNotChangeLexicalStringsCommentsOrFenceBoundaries() {
        for (List<String> lines : List.of(
            List.of("# Heading", "```java", "/* comment ([", "still }) comment", "*/ String value = \"(\";", "```", "text (outside)"),
            List.of("```python", "\"\"\"opened string ([", "still }) string", "\"\"\"; return 7", "```", "text (outside)")
        )) {
            var enabled = highlighter(true).lexicalScanner();
            var disabled = highlighter(false).lexicalScanner();
            LexicalScanner.State enabledState = null;
            LexicalScanner.State disabledState = null;
            for (String line : lines) {
                var expected = enabled.scan(line, line.length(), enabledState);
                var actual = disabled.scan(line, line.length(), disabledState);
                assertEquals(expected.scannedCodeUnits(), actual.scannedCodeUnits(), line);
                assertEquals(expected.contextKnown(), actual.contextKnown(), line);
                assertEquals(expected.excludedRanges(), actual.excludedRanges(), line);
                assertEquals(expected.quotes(), actual.quotes(), line);
                enabledState = expected.endState();
                disabledState = actual.endState();
            }
        }
    }

    @Test
    void recreatingTheDocumentHighlighterRestoresCodeStylesAtTheSameRevision() {
        var document = new DocumentSnapshot(DOCUMENT_ID, 1,
            List.of("```python", "return 7", "```"));
        assertEquals(uniformCode(document, 1), highlighter(false).highlight(document, 1, document.line(1)));
        var restored = highlighter(true).highlight(document, 1, document.line(1));
        assertTrue(restored.stream().anyMatch(span -> span.styleKey().equals(SemanticStyleKey.KEYWORD)));
        var defaultFileHighlighter = assertInstanceOf(ContextualSyntaxHighlighter.class,
            RegexHighlighter.forFileName("notes.md"));
        assertEquals(restored, defaultFileHighlighter.highlight(document, 1, document.line(1)));
    }

    @Test
    void uniformCodeRoleRespectsBoundsAndLeavesFenceMarkersUnstyled() {
        var document = new DocumentSnapshot(DOCUMENT_ID, 1,
            List.of("```java", "public int value = 7;", "```"));
        var disabled = highlighter(false);
        assertTrue(disabled.highlight(document, 0, document.line(0)).isEmpty());
        assertTrue(disabled.highlight(document, 2, document.line(2)).isEmpty());
        assertEquals(List.of(new TokenSpan(7, 10, SemanticStyleKey.CODE)),
            disabled.highlight(document, 1, document.line(1), new HighlightRequest(7, 10, 65_536, 1, 1_000_000)));
        assertTrue(disabled.highlight(document, 1, document.line(1),
            new HighlightRequest(0, document.line(1).length(), 65_536, 0, 1_000_000)).isEmpty());
        assertTrue(disabled.highlight(document, 1, document.line(1),
            new HighlightRequest(0, document.line(1).length(), 65_536, 1, 0)).isEmpty());
    }

    private static List<TokenSpan> uniformCode(DocumentSnapshot document, int row) {
        return List.of(new TokenSpan(0, document.line(row).length(), SemanticStyleKey.CODE));
    }

    private static ContextualSyntaxHighlighter highlighter(boolean enabled) {
        return assertInstanceOf(ContextualSyntaxHighlighter.class,
            RegexHighlighter.forMarkdownDocument(enabled));
    }
}
