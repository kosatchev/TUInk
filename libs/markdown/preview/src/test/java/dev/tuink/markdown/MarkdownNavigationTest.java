package dev.tuink.markdown;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class MarkdownNavigationTest {
    @Test void manyRepeatedHeadingsHaveDistinctOffsetsAndSuffixes() {
        List<MarkdownBlock> blocks = new ArrayList<>();
        for (int line = 0; line < 2000; line++) blocks.add(heading("Repeated", line));
        var document = document(blocks);
        var headings = MarkdownNavigation.headings(document);
        assertEquals(2000, headings.size());
        assertEquals("repeated-1999", headings.get(1999).anchor());
        assertEquals(document.projection().text().length() - "Repeated".length(), headings.get(1999).projectionOffset());
    }

    @Test void headingsUseSemanticLabelsAndDeterministicUniqueUnicodeSlugs() {
        var document = document(List.of(heading("Hello **world**", 0), heading("Hello **world**", 1),
            heading("Hello world-1", 2), heading("Привет, мир!", 3), heading("Hello **world**", 4)));
        var headings = MarkdownNavigation.headings(document);
        assertEquals(List.of("hello-world", "hello-world-1", "hello-world-1-1", "привет-мир", "hello-world-2"),
            headings.stream().map(MarkdownNavigation.Heading::anchor).toList());
        assertEquals("Hello world", headings.get(0).label());
        assertEquals(0, headings.get(0).projectionOffset());
        assertTrue(headings.get(3).projectionOffset() > headings.get(2).projectionOffset());
    }

    @Test void lowercaseDoesNotDependOnTheProcessLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("title", MarkdownNavigation.headings(document(List.of(heading("TITLE", 0)))).get(0).anchor());
        } finally { Locale.setDefault(original); }
    }

    @Test void headingsInsideListsAndQuotesRemainInDocumentOrder() {
        var quote = new MarkdownBlock.Quote(List.of(heading("Quoted", 1)), range(1, 0, 20));
        var list = new MarkdownBlock.ListBlock(false, 1,
            List.of(new MarkdownBlock.Item(List.of(heading("Listed", 2)), false, false, range(2, 0, 20))), range(2, 0, 20));
        assertEquals(List.of("quoted", "listed"), MarkdownNavigation.headings(document(List.of(quote, list)))
            .stream().map(MarkdownNavigation.Heading::anchor).toList());
    }

    @Test void anchorResolutionDecodesUtf8ButDoesNotOpenPathsOrRewritePlusSigns() {
        var document = document(List.of(heading("Привет", 0), heading("Hello world", 1)));
        assertEquals("привет", MarkdownNavigation.resolveAnchor(document, "#%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82").anchor());
        assertEquals("hello-world", MarkdownNavigation.resolveAnchor(document, "#hello-world").anchor());
        for (String destination : List.of("other.md#hello-world", "https://example.org/#hello-world", "#hello+world",
            "#%FF", "#%E2%28%A1", "#bad%", "#%00", "#missing", "#"))
            assertNull(MarkdownNavigation.resolveAnchor(document, destination), destination);
    }

    @Test void linkAtFindsNestedStyledLabelsAndTableCellsWithoutMatchingPlainText() {
        var link = new MarkdownInline.Link(List.of(new MarkdownInline.Text("jump", range(0, 2, 6))),
            "#target", "", false, range(0, 1, 16));
        var paragraph = new MarkdownBlock.Paragraph(List.of(
            new MarkdownInline.Text(" ", range(0, 0, 1)),
            new MarkdownInline.Styled(MarkdownInline.Style.STRONG, List.of(link), range(0, 1, 16))), range(0, 0, 16));
        var tableLink = new MarkdownInline.Link(List.of(new MarkdownInline.Text("cell", range(1, 2, 6))),
            "#target", "", false, range(1, 1, 16));
        var table = new MarkdownBlock.Table(List.of(new MarkdownBlock.Row(true,
            List.of(new MarkdownBlock.Cell(List.of(tableLink), range(1, 0, 17))), range(1, 0, 17))),
            List.of(MarkdownBlock.Alignment.LEFT), range(1, 0, 17));
        var document = document(List.of(paragraph, table));
        assertNull(MarkdownNavigation.linkAt(document, 0));
        assertEquals(link, MarkdownNavigation.linkAt(document, 2));
        int cell = document.projection().text().indexOf("cell");
        assertEquals(tableLink, MarkdownNavigation.linkAt(document, cell));
    }

    @Test void codeAndMermaidTextAreNotInterpretedAsLinks() {
        var document = document(List.of(new MarkdownBlock.Code("[x](#target)", "", range(0, 0, 13)),
            new MarkdownBlock.Mermaid("A[#target]", range(1, 0, 10))));
        assertNull(MarkdownNavigation.linkAt(document, 2));
        assertTrue(MarkdownNavigation.headings(document).isEmpty());
        assertNull(MarkdownNavigation.linkAt(document(List.of()), 0));
    }

    private static MarkdownDocument document(List<MarkdownBlock> blocks) {
        return new MarkdownDocument(new DocumentId("navigation"), 0, blocks, List.of());
    }
    private static MarkdownBlock.Heading heading(String value, int line) {
        if (value.equals("Hello **world**")) return new MarkdownBlock.Heading(1,
            List.of(new MarkdownInline.Text("Hello ", range(line, 2, 8)),
                new MarkdownInline.Styled(MarkdownInline.Style.STRONG,
                    List.of(new MarkdownInline.Text("world", range(line, 10, 15))), range(line, 8, 17))), range(line, 0, 17));
        return new MarkdownBlock.Heading(1, List.of(new MarkdownInline.Text(value, range(line, 2, value.length() + 2))),
            range(line, 0, value.length() + 2));
    }
    private static TextRange range(int line, int from, int to) {
        return new TextRange(new TextPosition(line, from), new TextPosition(line, to));
    }
}
