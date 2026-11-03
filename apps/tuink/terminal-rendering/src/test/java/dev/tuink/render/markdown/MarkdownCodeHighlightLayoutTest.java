package dev.tuink.render.markdown;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.TokenSpan;
import dev.tuink.markdown.MarkdownBlock;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.MarkdownInline;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class MarkdownCodeHighlightLayoutTest {
    private static final TextRange SOURCE = new TextRange(new TextPosition(0, 0), new TextPosition(3, 20));
    private static final MarkdownLayout.EmbeddedRenderer EMBEDDED = new MarkdownLayout.EmbeddedRenderer() {};

    @Test void detachedBlockStylesPreserveTextGeometryAndSemanticProjection() {
        var document = document(new MarkdownBlock.Code("public\t\"😀\"  \nreturn 7;\n", "java", SOURCE));
        var plain = new MarkdownLayout(7).layout(document);
        var calls = new AtomicInteger();
        var styled = new MarkdownLayout(7, EMBEDDED, (language, lines) -> {
            calls.incrementAndGet();
            assertEquals("java", language);
            assertEquals(List.of("public\t\"😀\"  ", "return 7;", ""), lines);
            return List.of(List.of(token(0, 6), new TokenSpan(7, 11, SemanticStyleKey.STRING)),
                List.of(token(0, 6), new TokenSpan(7, 8, SemanticStyleKey.NUMBER)));
        }).layout(document);

        assertEquals(1, calls.get());
        assertEquals(plain.columns(), styled.columns());
        assertEquals(plain.extent(), styled.extent());
        assertSame(document.projection(), styled.document().projection());
        assertEquals(plain.lines().size(), styled.lines().size());
        for (int row = 0; row < plain.lines().size(); row++) {
            var expected = plain.lines().get(row);
            var actual = styled.lines().get(row);
            assertEquals(expected, new MarkdownLayout.Line(actual.text(), actual.runs(), actual.copyText(),
                actual.source(), actual.kind()));
        }
        assertEquals(List.of(token(0, 6), new TokenSpan(7, 11, SemanticStyleKey.STRING)), code(styled).get(0).syntax());
        assertEquals(List.of(), code(styled).get(2).syntax(), "Absent provider rows remain ordinary code");
        assertTrue(styled.lines().stream().filter(line -> !line.kind().equals("code")).allMatch(line -> line.syntax().isEmpty()));
    }

    @Test void quoteAndListPrefixesShiftStylesWithoutChangingTheirCopyOffsets() {
        var item = new MarkdownBlock.Item(List.of(new MarkdownBlock.Code("go\t😀", "", SOURCE)), false, false, SOURCE);
        var document = document(new MarkdownBlock.Quote(List.of(new MarkdownBlock.ListBlock(false, 1, List.of(item), SOURCE)), SOURCE));
        var result = layout((language, lines) -> List.of(List.of(token(0, 2)))).layout(document);
        var line = code(result).get(0);

        assertEquals("│ • go\t😀", line.text());
        assertEquals("go\t😀", line.copyText());
        assertEquals(List.of(token(4, 6)), line.syntax());
        assertEquals(4, line.runs().get(0).start());
        assertEquals(0, line.runs().get(0).projectionStart());
        assertEquals(line.copyText().length(), line.runs().get(0).projectionEnd());
    }

    @Test void eachBlockIsPresentedOnceAndInlineCodeAndRawTextAreNotTokenized() {
        var calls = new ArrayList<String>();
        var result = layout((language, lines) -> {
            calls.add(language + ":" + String.join("|", lines));
            return List.of(List.of(token(0, 1)));
        }).layout(document(
            new MarkdownBlock.Code("a", "java", SOURCE),
            new MarkdownBlock.Paragraph(List.of(new MarkdownInline.Code("public", SOURCE)), SOURCE),
            new MarkdownBlock.Raw("return 7;", SOURCE),
            new MarkdownBlock.Code("b", "python", SOURCE)));

        assertEquals(List.of("java:a", "python:b"), calls);
        assertTrue(result.lines().stream().filter(line -> !line.kind().equals("code")).allMatch(line -> line.syntax().isEmpty()));
    }

    @Test void syntaxIsDetachedFromMutableProviderListsAndReadOnlyAfterProjection() {
        var tokens = new ArrayList<>(List.of(token(0, 1)));
        var rows = new ArrayList<List<TokenSpan>>(List.of(tokens));
        var result = layout((language, lines) -> rows).layout(document(new MarkdownBlock.Code("a", "java", SOURCE)));
        tokens.clear();
        rows.clear();

        var line = code(result).get(0);
        assertEquals(List.of(token(0, 1)), line.syntax());
        assertThrows(UnsupportedOperationException.class, () -> line.syntax().clear());
        assertEquals(List.of(), new MarkdownLayout.Line("a", List.of(), "a", SOURCE, "code").syntax());
    }

    @Test void malformedOrOverlappingProviderSpansFallBackToUnstyledCode() {
        List<List<TokenSpan>> invalid = Arrays.asList(
            null,
            Arrays.asList((TokenSpan) null),
            List.of(token(0, 4)),
            List.of(token(1, 1)),
            List.of(token(0, 2), token(1, 3)),
            List.of(token(2, 3), token(0, 1)));
        var document = document(new MarkdownBlock.Code("abc", "java", SOURCE));
        for (List<TokenSpan> tokens : invalid) {
            var result = layout((language, lines) -> Arrays.asList(tokens)).layout(document);
            assertEquals(new MarkdownLayout(80).layout(document), result);
        }
        assertEquals(new MarkdownLayout(80).layout(document), layout((language, lines) -> null).layout(document));
    }

    @Test void excessiveLineStylesDoNotSuppressFollowingRowsOrAnyCodeText() {
        String text = "a".repeat(1025);
        List<TokenSpan> excess = IntStream.range(0, text.length()).mapToObj(i -> token(i, i + 1)).toList();
        var result = layout((language, lines) -> List.of(excess, List.of(token(0, 1))))
            .layout(document(new MarkdownBlock.Code(text + "\nb", "java", SOURCE)));

        assertEquals(text, code(result).get(0).copyText());
        assertTrue(code(result).get(0).syntax().isEmpty());
        assertEquals(List.of(token(0, 1)), code(result).get(1).syntax());
    }

    @Test void totalStyleBudgetFallsBackWithoutLosingRowsAndResetsForANewLayout() {
        String text = "x".repeat(1024);
        String body = String.join("\n", java.util.Collections.nCopies(33, text));
        var layout = layout((language, lines) -> lines.stream().map(line ->
            IntStream.range(0, line.length()).mapToObj(i -> token(i, i + 1)).toList()).toList());
        var result = layout.layout(document(new MarkdownBlock.Code(body, "java", SOURCE)));

        assertEquals(33, code(result).size());
        assertEquals(32_768, code(result).stream().mapToInt(line -> line.syntax().size()).sum());
        assertTrue(code(result).get(32).syntax().isEmpty());
        assertEquals(body, String.join("\n", code(result).stream().map(MarkdownLayout.Line::copyText).toList()));
        assertEquals(List.of(token(0, 1)), code(layout.layout(document(new MarkdownBlock.Code("x", "java", SOURCE)))).get(0).syntax());
    }

    @Test void cancellationFromTheProviderPreventsPublishingPartialStyles() {
        try {
            assertThrows(CancellationException.class, () -> layout((language, lines) -> {
                Thread.currentThread().interrupt();
                return List.of(List.of(token(0, 1)));
            }).layout(document(new MarkdownBlock.Code("a", "java", SOURCE))));
        } finally {
            Thread.interrupted();
        }
    }

    private static MarkdownLayout layout(MarkdownLayout.CodeHighlighter highlighter) {
        return new MarkdownLayout(80, EMBEDDED, highlighter);
    }
    private static MarkdownDocument document(MarkdownBlock... blocks) {
        return new MarkdownDocument(new DocumentId("code-highlight-layout"), 1, List.of(blocks), List.of());
    }
    private static List<MarkdownLayout.Line> code(MarkdownLayout.Result result) {
        return result.lines().stream().filter(line -> line.kind().equals("code")).toList();
    }
    private static TokenSpan token(int start, int end) {
        return new TokenSpan(start, end, SemanticStyleKey.KEYWORD);
    }
}
