package dev.tuink.render.markdown;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.markdown.MarkdownBlock;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.MarkdownInline;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class MarkdownQuoteLayoutTest {
    private static final TextRange QUOTE = range(0, 30);

    @Test void wrappedQuoteStylesItsDrawingPrefixAndKeepsBodyAndProjectionOrdinary() {
        var document = document(quote(paragraph("one two three four", 1)));
        var result = new MarkdownLayout(10).layout(document);
        var content = result.lines().stream().filter(line -> line.kind().equals("paragraph")).toList();
        assertTrue(content.size() > 1);
        assertEquals("one two three four", document.projection().text());
        for (var line : content) {
            assertTrue(line.text().startsWith("│ "));
            assertMarker(line, 2);
            for (var run : line.runs().subList(1, line.runs().size())) {
                assertEquals(MarkdownLayout.Role.TEXT, run.role());
                assertTrue(run.start() >= 2);
                assertEquals(line.text().substring(run.start(), run.end()),
                    document.projection().text().substring(run.projectionStart(), run.projectionEnd()));
            }
        }
    }

    @Test void quotesContinueAcrossInternalBlankRowsAndStopAtTheOutsideParagraph() {
        var result = new MarkdownLayout(80).layout(document(
            quote(paragraph("first", 1), paragraph("second", 2)), paragraph("outside", 4)));
        assertEquals(List.of("│ first", "│", "│ second", "", "outside", ""),
            result.lines().stream().map(MarkdownLayout.Line::text).toList());
        assertMarker(result.lines().get(1), 1);
        assertTrue(result.lines().get(4).runs().stream().noneMatch(run -> run.role() == MarkdownLayout.Role.QUOTE_MARKER));
    }

    @Test void nestedQuoteInsideAListRetainsIndentationAndCodeCopyAndTokenCoordinates() {
        var code = new MarkdownBlock.Code("go\t😀\n", "java", range(3, 12));
        var item = new MarkdownBlock.Item(List.of(new MarkdownBlock.Quote(List.of(code), QUOTE)), false, false, QUOTE);
        var list = new MarkdownBlock.ListBlock(false, 1, List.of(item), QUOTE);
        var document = document(quote(list));
        var result = new MarkdownLayout(80, new MarkdownLayout.EmbeddedRenderer() {},
            (language, lines) -> List.of(List.of(new dev.tuink.editor.highlight.TokenSpan(0, 2,
                dev.tuink.editor.highlight.SemanticStyleKey.KEYWORD)))).layout(document);
        var line = result.lines().stream().filter(value -> value.kind().equals("code")
            && value.copyText().equals("go\t😀")).findFirst().orElseThrow();
        assertEquals("│   │ go\t😀", line.text());
        assertMarker(line, 6);
        var body = line.runs().stream().filter(run -> run.role() == MarkdownLayout.Role.CODE_BLOCK).findFirst().orElseThrow();
        assertEquals(6, body.start());
        assertEquals(line.copyText(), document.projection().text().substring(body.projectionStart(), body.projectionEnd()));
        assertEquals(6, line.syntax().get(0).start());
        assertEquals(8, line.syntax().get(0).end());
        var label = result.lines().stream().filter(value -> value.kind().equals("code-label")).findFirst().orElseThrow();
        assertEquals("│ • │ [java]", label.text());
        assertMarker(label, 6);
    }

    @Test void atomicMathAndDiagramRowsExcludeQuoteMarkersFromTheirSemanticRuns() {
        var document = document(quote(new MarkdownBlock.Math("fraction", range(1, 8)),
            new MarkdownBlock.Mermaid("flowchart LR", range(2, 12))));
        var embedded = new MarkdownLayout.EmbeddedRenderer() {
            @Override public MarkdownLayout.Box math(String text) { return new MarkdownLayout.Box(List.of(" a ", "───", " b "), 1); }
            @Override public List<String> mermaid(String text, int width) { return List.of("A → B", "└───┘"); }
        };
        var result = new MarkdownLayout(80, embedded).layout(document);
        for (var line : result.lines()) if (line.kind().equals("math") || line.kind().equals("mermaid")) {
            assertMarker(line, 2);
            var body = line.runs().get(1);
            assertEquals(2, body.start());
            String expected = line.kind().equals("math") ? "fraction" : "flowchart LR";
            assertEquals(expected, document.projection().text().substring(body.projectionStart(), body.projectionEnd()));
        }
    }

    @Test void quotedTablesAndTheirLocalFailuresKeepTheirQuotePrefix() {
        var cell = new MarkdownBlock.Cell(List.of(new MarkdownInline.Text("value", range(1, 5))), range(1, 5));
        var table = new MarkdownBlock.Table(List.of(new MarkdownBlock.Row(false, List.of(cell), range(1, 5))), List.of(), range(1, 5));
        var normal = new MarkdownLayout(80).layout(document(quote(table)));
        for (var line : normal.lines()) if (!line.text().isEmpty()) assertMarker(line, 2);
        var tooWide = new MarkdownBlock.Table(List.of(new MarkdownBlock.Row(false,
            IntStream.range(0, 65).mapToObj(index -> cell).toList(), range(1, 5))), List.of(), range(1, 5));
        var failure = new MarkdownLayout(80).layout(document(quote(tooWide))).lines().get(0);
        assertTrue(failure.text().startsWith("│ [!] "));
        assertMarker(failure, 2);
        assertEquals(MarkdownLayout.Role.DIAGNOSTIC, failure.runs().get(1).role());
    }

    @Test void literalBarsInUnquotedCodeAreNeverClassifiedAsQuoteMarkers() {
        var result = new MarkdownLayout(80).layout(document(new MarkdownBlock.Code("│ literal code", "", range(1, 15))));
        assertTrue(result.lines().stream().flatMap(line -> line.runs().stream())
            .noneMatch(run -> run.role() == MarkdownLayout.Role.QUOTE_MARKER));
        var empty = new MarkdownLayout(80).layout(document(quote()));
        assertEquals("│", empty.lines().get(0).text());
        assertMarker(empty.lines().get(0), 1);
    }

    private static void assertMarker(MarkdownLayout.Line line, int end) {
        var marker = line.runs().get(0);
        assertEquals(MarkdownLayout.Role.QUOTE_MARKER, marker.role());
        assertEquals(0, marker.start());
        assertEquals(end, marker.end());
        assertNull(marker.source());
        assertEquals(-1, marker.projectionStart());
        assertEquals(-1, marker.projectionEnd());
    }
    private static MarkdownBlock.Quote quote(MarkdownBlock... blocks) { return new MarkdownBlock.Quote(List.of(blocks), QUOTE); }
    private static MarkdownBlock.Paragraph paragraph(String text, int line) {
        return new MarkdownBlock.Paragraph(List.of(new MarkdownInline.Text(text, range(line, text.length()))), range(line, text.length()));
    }
    private static TextRange range(int line, int length) { return new TextRange(new TextPosition(line, 0), new TextPosition(line, length)); }
    private static MarkdownDocument document(MarkdownBlock... blocks) {
        return new MarkdownDocument(new DocumentId("quoted-layout"), 1, List.of(blocks), List.of());
    }
}
