package dev.tuink.markdown.commonmark;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.markdown.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class CommonmarkMarkdownParserTest {
    private final MarkdownParser parser = new CommonmarkMarkdownParser();
    private static DocumentSnapshot source(String text) {
        return new DocumentSnapshot(new DocumentId("markdown-test"), 42, List.of(text.split("\n", -1)));
    }
    private MarkdownDocument parse(String text) { return parser.parse(source(text)); }

    @Test void convertsBlockAndInlineSemanticsWithoutExportingEngineNodes() {
        MarkdownDocument doc = parse("# Title\n\nA **bold** and *soft* ~~gone~~ `code` [link](https://example.org).\n\n> quote\n>\n> - nested\n\n3. first\n4. second\n\n---");
        assertEquals(42, doc.revision());
        assertInstanceOf(MarkdownBlock.Heading.class, doc.blocks().get(0));
        MarkdownBlock.Paragraph paragraph = assertInstanceOf(MarkdownBlock.Paragraph.class, doc.blocks().get(1));
        assertEquals(3, paragraph.content().stream().filter(MarkdownInline.Styled.class::isInstance).count());
        assertTrue(paragraph.content().stream().anyMatch(MarkdownInline.Link.class::isInstance));
        MarkdownBlock.Quote quote = assertInstanceOf(MarkdownBlock.Quote.class, doc.blocks().get(2));
        assertInstanceOf(MarkdownBlock.ListBlock.class, quote.blocks().get(1));
        MarkdownBlock.ListBlock ordered = assertInstanceOf(MarkdownBlock.ListBlock.class, doc.blocks().get(3));
        assertTrue(ordered.ordered()); assertEquals(3, ordered.start());
        assertEquals(2, ordered.items().size());
        assertInstanceOf(MarkdownBlock.ThematicBreak.class, doc.blocks().get(4));
        assertTrue(doc.projection().text().contains("A bold and soft gone code link."));
        assertFalse(doc.projection().text().contains("https://example.org"));
    }

    @Test void keepsUtf16CoordinatesAndExposesCoarseMappingsForDecodedEntities() {
        MarkdownDocument doc = parse("😀 **bold** &amp;");
        MarkdownBlock.Paragraph paragraph = (MarkdownBlock.Paragraph) doc.blocks().get(0);
        MarkdownInline.Styled styled = (MarkdownInline.Styled) paragraph.content().get(1);
        assertEquals(new TextRange(new TextPosition(0, 3), new TextPosition(0, 11)), styled.source());
        MarkdownInline.Text bold = (MarkdownInline.Text) styled.content().get(0);
        assertEquals(new TextRange(new TextPosition(0, 5), new TextPosition(0, 9)), bold.source());
        assertEquals("😀 bold &", doc.projection().text());
        assertEquals(bold.source(), doc.projection().sourceAt(3));
        assertFalse(doc.projection().mappings().get(doc.projection().mappings().size() - 1).exact());
    }

    @Test void tablesKeepAlignmentEscapedPipesAndCodeSpans() {
        MarkdownBlock.Table table = assertInstanceOf(MarkdownBlock.Table.class,
            parse("| Left | Center | Right |\n| :--- | :---: | ---: |\n| a\\|b | `c` | | ").blocks().get(0));
        assertEquals(List.of(MarkdownBlock.Alignment.LEFT, MarkdownBlock.Alignment.CENTER, MarkdownBlock.Alignment.RIGHT), table.alignments());
        assertTrue(table.rows().get(0).header());
        assertEquals(3, table.rows().get(1).cells().size());
        assertEquals("a|b", ((MarkdownInline.Text)table.rows().get(1).cells().get(0).content().get(0)).text());
        assertInstanceOf(MarkdownInline.Code.class, table.rows().get(1).cells().get(1).content().get(0));
        assertTrue(table.rows().get(1).cells().get(2).content().isEmpty());
    }

    @Test void taskListsAndReferenceLinksAreResolvedButRemainReadOnlyData() {
        MarkdownDocument doc = parse("- [x] done\n- [ ] todo\n\n[ref][id]\n\n[id]: target.md \"Title\"");
        MarkdownBlock.ListBlock list = (MarkdownBlock.ListBlock)doc.blocks().get(0);
        assertTrue(list.items().get(0).task()); assertTrue(list.items().get(0).checked());
        assertTrue(list.items().get(1).task()); assertFalse(list.items().get(1).checked());
        MarkdownBlock.Paragraph paragraph = (MarkdownBlock.Paragraph)doc.blocks().get(1);
        MarkdownInline.Link link = (MarkdownInline.Link)paragraph.content().get(0);
        assertEquals("target.md", link.destination()); assertEquals("Title", link.title());
        assertEquals("done\ntodo\nref", doc.projection().text());
    }

    @Test void mathIsParsedBeforeMarkdownEmphasisAndKeepsNestedGroups() {
        MarkdownBlock.Paragraph paragraph = (MarkdownBlock.Paragraph)parse("Value $x_i^{a+b}$ and $5+2=7$.").blocks().get(0);
        List<MarkdownInline.Math> expressions = paragraph.content().stream().filter(MarkdownInline.Math.class::isInstance).map(MarkdownInline.Math.class::cast).toList();
        assertEquals(List.of("x_i^{a+b}", "5+2=7"), expressions.stream().map(MarkdownInline.Math::expression).toList());
        assertEquals("$x_i^{a+b}$", MarkdownSource.text(source("Value $x_i^{a+b}$ and $5+2=7$."), expressions.get(0).source()));
    }

    @Test void currencyEscapedDollarsAndCodeAreNotMath() {
        MarkdownDocument doc = parse("Цена $5 и $10. Экранирование \\$5. `$x_i$`\n\n```text\n$$code$$\n```\n\n````markdown\n```mermaid\nA-->B\n```\n$$$$\n````");
        MarkdownBlock.Paragraph paragraph = (MarkdownBlock.Paragraph)doc.blocks().get(0);
        assertFalse(paragraph.content().stream().anyMatch(MarkdownInline.Math.class::isInstance));
        assertTrue(paragraph.content().stream().anyMatch(MarkdownInline.Code.class::isInstance));
        assertEquals("$$code$$\n", ((MarkdownBlock.Code)doc.blocks().get(1)).text());
        assertInstanceOf(MarkdownBlock.Code.class, doc.blocks().get(2));
    }

    @Test void displayMathAndSpecialFencesRetainTheirRawEmbeddedSource() {
        MarkdownDocument doc = parse("$$\n\\frac{x^2}{y}\n$$\n\n```math\n\\sqrt{x}\n```\n\n```mermaid\nflowchart LR\n A-->B\n```");
        assertEquals(3, doc.blocks().size());
        MarkdownBlock.Math display = assertInstanceOf(MarkdownBlock.Math.class, doc.blocks().get(0));
        assertEquals("\\frac{x^2}{y}", display.expression());
        assertEquals("$$\n\\frac{x^2}{y}\n$$", MarkdownSource.text(source("$$\n\\frac{x^2}{y}\n$$"), display.source()));
        assertEquals("\\sqrt{x}\n", ((MarkdownBlock.Math)doc.blocks().get(1)).expression());
        assertEquals("flowchart LR\n A-->B\n", ((MarkdownBlock.Mermaid)doc.blocks().get(2)).code());
        assertInstanceOf(MarkdownBlock.Math.class, parse("$$x^2$$").blocks().get(0));
    }

    @Test void unclosedDisplayDelimiterDegradesLocallyAndPreservesItsSource() {
        MarkdownDocument doc = parse("before\n\n$$\nx + 1");
        assertInstanceOf(MarkdownBlock.Paragraph.class, doc.blocks().get(0));
        MarkdownBlock.Raw raw = assertInstanceOf(MarkdownBlock.Raw.class, doc.blocks().get(1));
        assertEquals("$$\nx + 1", raw.text());
        assertEquals(1, doc.diagnostics().size());
    }

    @Test void mathCopyPreservesEmptyLinesInsideDelimiters() {
        MarkdownDocument doc = parse("$$\n\nx + 1\n\n$$");
        MarkdownBlock.Math formula = assertInstanceOf(MarkdownBlock.Math.class, doc.blocks().get(0));
        assertEquals("\nx + 1\n", formula.expression());
    }

    @Test void htmlCodeAndImagesKeepReadableContent() {
        MarkdownDocument doc = parse("![Alt](pic.png) <b>literal</b>\n\n<div>block</div>\n\n    code\n");
        MarkdownInline.Link image = assertInstanceOf(MarkdownInline.Link.class, ((MarkdownBlock.Paragraph)doc.blocks().get(0)).content().get(0));
        assertTrue(image.image()); assertEquals("pic.png", image.destination());
        assertEquals("<div>block</div>", ((MarkdownBlock.Raw)doc.blocks().get(1)).text());
        assertEquals("code\n", ((MarkdownBlock.Code)doc.blocks().get(2)).text());
    }

    @Test void admissionStructureAndCancellationAreExplicitAndLeaveSnapshotUntouched() {
        DocumentSnapshot input = source("# hello");
        assertThrows(MarkdownParseException.class, () -> parser.parse(input, new ParseLimits(3, 20, 8, 20), ParseControl.NONE));
        assertThrows(MarkdownParseException.class, () -> parser.parse(source("> > > > deep"), new ParseLimits(100, 100, 2, 20), ParseControl.NONE));
        assertThrows(MarkdownParseException.class, () -> parser.parse(source("a **b** c"), new ParseLimits(100, 2, 8, 20), ParseControl.NONE));
        assertThrows(MarkdownParseException.class, () -> parser.parse(source("|a|b|\n|-|-|\n|c|d|"), new ParseLimits(100, 100, 8, 1), ParseControl.NONE));
        assertThrows(CancellationException.class, () -> parser.parse(input, ParseLimits.DEFAULT, () -> true));
        assertEquals("# hello", input.line(0)); assertEquals(42, input.revision());
    }
}
