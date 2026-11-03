package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.TokenSpan;
import dev.tuink.highlight.regex.RegexHighlighter;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MarkdownCodeHighlighterTest {
    private static final long STYLE_BYTES = 1L << 20;

    @Test
    void javaCommentsContinueAcrossRowsAndFinishBeforeFollowingCode() {
        List<String> code = List.of("/* comment", "return 7;", "*/ int value = 8;");
        var rows = new MarkdownCodeHighlighter(STYLE_BYTES).highlight("java", code);
        assertEquals(code.size(), rows.size());
        assertEquals(List.of(new TokenSpan(0, code.get(1).length(), SemanticStyleKey.COMMENT)), rows.get(1));
        assertTrue(rows.get(2).contains(new TokenSpan(3, 6, SemanticStyleKey.TYPE)));
        assertTrue(rows.get(2).contains(new TokenSpan(15, 16, SemanticStyleKey.NUMBER)));
    }

    @Test
    void pythonStringsAndJavaScriptTemplatesContinueWithinTheirOwnBlock() {
        record Example(String language, String opening) {}
        for (Example example : List.of(
            new Example("python", "\"\"\"opened string"),
            new Example("js", "`opened template")
        )) {
            var rows = new MarkdownCodeHighlighter(STYLE_BYTES)
                .highlight(example.language(), List.of(example.opening(), "return 7;"));
            assertEquals(List.of(new TokenSpan(0, 9, SemanticStyleKey.STRING)), rows.get(1), example.language());
        }
    }

    @Test
    void aliasesAndUnknownLanguagesDoNotInheritThePreviousBlockContext() {
        var highlighter = new MarkdownCodeHighlighter(STYLE_BYTES);
        highlighter.highlight("java", List.of("/* unfinished", "return 7;"));
        String code = "return 7;";
        for (String language : List.of("{.language-java}", "JAVA", "unknown-language", "")) {
            assertEquals(RegexHighlighter.withDefaults().highlight(code),
                highlighter.highlight(language, List.of(code)).get(0), language);
        }
    }

    @Test
    void spansUseUtf16BodyCoordinatesWithoutChangingUnicodeOrIndentation() {
        String code = "\t🚀 return 7;";
        var row = new MarkdownCodeHighlighter(STYLE_BYTES).highlight("java", List.of(code)).get(0);
        assertTrue(row.contains(new TokenSpan(4, 10, SemanticStyleKey.KEYWORD)));
        assertTrue(row.contains(new TokenSpan(11, 12, SemanticStyleKey.NUMBER)));
        assertEquals("\t🚀 return 7;", code);
    }

    @Test
    void styleByteAllowanceDegradesTheRemainderToPlainCodeAcrossBlocks() {
        long bytes = MarkdownCodeHighlighter.STYLED_ROW_BYTES + MarkdownCodeHighlighter.TOKEN_BYTES;
        var highlighter = new MarkdownCodeHighlighter(bytes);
        var rows = highlighter.highlight("java", List.of("return", "return"));
        assertEquals(List.of(new TokenSpan(0, 6, SemanticStyleKey.KEYWORD)), rows.get(0));
        assertTrue(rows.get(1).isEmpty());
        assertTrue(highlighter.highlight("python", List.of("return")).get(0).isEmpty());
        assertTrue(new MarkdownCodeHighlighter(0).highlight("java", List.of("return")).get(0).isEmpty());
    }

    @Test
    void tokenAndWorkAllowancesAreSharedByAllBlocksInOneLayout() {
        var tokens = new MarkdownCodeHighlighter(STYLE_BYTES, MarkdownCodeHighlighter.MAX_WORK, 3);
        var first = tokens.highlight("java", List.of("return", "return"));
        var second = tokens.highlight("java", List.of("return", "return"));
        assertEquals(3, first.stream().mapToInt(List::size).sum() + second.stream().mapToInt(List::size).sum());
        assertTrue(second.get(1).isEmpty());

        var work = new MarkdownCodeHighlighter(STYLE_BYTES, 6000, MarkdownCodeHighlighter.MAX_TOKENS);
        assertFalse(work.highlight("java", List.of("return")).get(0).isEmpty());
        assertTrue(work.highlight("java", List.of("return")).get(0).isEmpty());
    }

    @Test
    void denseRowsAndOversizedLinesStayBoundedWithoutPoisoningAnotherBlock() {
        var highlighter = new MarkdownCodeHighlighter(STYLE_BYTES);
        var dense = highlighter.highlight("java", List.of("return ".repeat(2000)));
        assertTrue(dense.get(0).size() <= MarkdownCodeHighlighter.MAX_LINE_TOKENS);

        var huge = highlighter.highlight("java",
            List.of("x".repeat(MarkdownCodeHighlighter.MAX_SCAN_CODE_UNITS + 1), "return"));
        assertEquals(List.of(List.of(), List.of()), huge);
        assertFalse(highlighter.highlight("java", List.of("return")).get(0).isEmpty());
    }

    @Test
    void factoryLimitBoundsManyTinyBlocksAndLongTagsBypassFactoryWork() {
        var highlighter = new MarkdownCodeHighlighter(STYLE_BYTES);
        assertTrue(highlighter.highlight("java".repeat(1025), List.of("return")).get(0).isEmpty());
        for (int block = 0; block < MarkdownCodeHighlighter.MAX_BLOCKS; block++) {
            assertFalse(highlighter.highlight("java", List.of("return")).get(0).isEmpty(), "block " + block);
        }
        assertTrue(highlighter.highlight("java", List.of("return")).get(0).isEmpty());
    }

    @Test
    void emptyBlocksKeepAlignedRowsWithoutSpendingTheFactoryAllowance() {
        var highlighter = new MarkdownCodeHighlighter(STYLE_BYTES);
        for (int block = 0; block <= MarkdownCodeHighlighter.MAX_BLOCKS; block++) {
            assertEquals(List.of(List.of(), List.of()), highlighter.highlight("java", List.of("", "")));
        }
        assertFalse(highlighter.highlight("java", List.of("return")).get(0).isEmpty());
    }

    @Test
    void cancellationIsPropagatedBeforeAnyLanguageRulesAreBuilt() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class,
                () -> new MarkdownCodeHighlighter(STYLE_BYTES).highlight("java", List.of("return")));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void previewWorkerIncludesRealLanguageTokensInItsDetachedLayout() throws Exception {
        var source = new DocumentSnapshot(new DocumentId("preview-code-test"), 1,
            List.of("```java", "public int value = 7;", "```"));
        try (var service = new MarkdownPreviewService(() -> {})) {
            var result = service.submit(source, 80).get(3, TimeUnit.SECONDS);
            var code = result.lines().stream().filter(line -> line.kind().equals("code")
                && line.text().contains("public")).findFirst().orElseThrow();
            assertTrue(code.syntax().stream().anyMatch(span -> span.styleKey().equals(SemanticStyleKey.KEYWORD)));
            assertTrue(code.syntax().stream().anyMatch(span -> span.styleKey().equals(SemanticStyleKey.TYPE)));
            assertTrue(code.syntax().stream().anyMatch(span -> span.styleKey().equals(SemanticStyleKey.NUMBER)));
        }
    }
}
