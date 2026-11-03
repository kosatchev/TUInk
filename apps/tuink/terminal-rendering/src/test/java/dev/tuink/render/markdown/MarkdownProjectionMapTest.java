package dev.tuink.render.markdown;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.markdown.MarkdownBlock;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.MarkdownInline;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownProjectionMapTest {
    private static TextRange range(int start, int end) { return new TextRange(new TextPosition(0, start), new TextPosition(0, end)); }
    private static MarkdownDocument doc(List<MarkdownBlock> blocks) { return new MarkdownDocument(new DocumentId("projection"), 7, blocks, List.of()); }
    private static MarkdownBlock.Paragraph paragraph(String value) { return new MarkdownBlock.Paragraph(List.of(new MarkdownInline.Text(value, range(0, value.length()))), range(0, value.length())); }

    @Test void wordChunksKeepTheirSemanticCoordinatesAcrossResize() {
        MarkdownDocument doc = doc(List.of(paragraph("one two three")));
        MarkdownLayout.Result wide = new MarkdownLayout(80).layout(doc), narrow = new MarkdownLayout(5).layout(doc);
        assertEquals(4, run(wide, "two").projectionStart());
        assertEquals(4, run(narrow, "two").projectionStart());
        assertEquals(7, run(narrow, "two").projectionEnd());
        assertEquals(8, run(narrow, "three").projectionStart());
        assertEquals(13, run(narrow, "three").projectionEnd());
        assertEquals(0, wide.rowForProjectionOffset(8));
        assertEquals(2, narrow.rowForProjectionOffset(8));
    }

    @Test void linksFormattingAndUnicodeUseUtf16SemanticOffsets() {
        MarkdownInline.Text emoji = new MarkdownInline.Text("😀 ", range(0, 3));
        MarkdownInline.Text strong = new MarkdownInline.Text("bold", range(5, 9));
        MarkdownInline.Text label = new MarkdownInline.Text("link", range(13, 17));
        MarkdownDocument doc = doc(List.of(new MarkdownBlock.Paragraph(List.of(emoji,
            new MarkdownInline.Styled(MarkdownInline.Style.STRONG, List.of(strong), range(3, 11)),
            new MarkdownInline.Link(List.of(label), "target", "", false, range(12, 26))), range(0, 26))));
        MarkdownLayout.Result result = new MarkdownLayout(80).layout(doc);
        assertEquals("😀 boldlink", doc.projection().text());
        assertEquals(3, run(result, "bold").projectionStart());
        assertEquals(7, run(result, "link").projectionStart());
        assertEquals(11, run(result, "link").projectionEnd());
    }

    @Test void tableCellsMapIndependentlyWhenTheirWrappedRowsInterleave() {
        MarkdownBlock.Cell left = new MarkdownBlock.Cell(List.of(new MarkdownInline.Text("left longer", range(0, 11))), range(0, 11));
        MarkdownBlock.Cell right = new MarkdownBlock.Cell(List.of(new MarkdownInline.Text("right tail", range(12, 22))), range(12, 22));
        MarkdownBlock.Table table = new MarkdownBlock.Table(List.of(new MarkdownBlock.Row(false, List.of(left, right), range(0, 22))), List.of(), range(0, 22));
        MarkdownDocument doc = doc(List.of(table));
        MarkdownLayout.Result result = new MarkdownLayout(19).layout(doc);
        assertEquals("left longer\tright tail", doc.projection().text());
        assertEquals(12, run(result, "right").projectionStart());
        assertEquals(18, run(result, "tail").projectionStart());
        assertTrue(result.lines().stream().filter(line -> line.kind().equals("table-border")).flatMap(line -> line.runs().stream()).allMatch(run -> run.projectionStart() == -1));
    }

    @Test void multilineMathIsOneSemanticAtomAndDecorativeRowsDoNotCopyGlyphs() {
        MarkdownDocument doc = doc(List.of(new MarkdownBlock.Math("\\frac{a}{b}", range(0, 15))));
        MarkdownLayout.EmbeddedRenderer embedded = new MarkdownLayout.EmbeddedRenderer() {
            @Override public MarkdownLayout.Box math(String expression) { return new MarkdownLayout.Box(List.of(" a ", "───", " b "), 1); }
        };
        MarkdownLayout.Result result = new MarkdownLayout(10, embedded).layout(doc);
        List<MarkdownLayout.Run> runs = result.lines().stream().filter(line -> line.kind().equals("math")).flatMap(line -> line.runs().stream()).toList();
        assertEquals(3, runs.size());
        assertTrue(runs.stream().allMatch(run -> run.projectionStart() == 0 && run.projectionEnd() == doc.projection().text().length()));
    }

    @Test void codeInQuoteDoesNotMapTheDrawingPrefixIntoCodeContent() {
        MarkdownDocument doc = doc(List.of(new MarkdownBlock.Quote(List.of(new MarkdownBlock.Code("one\ntwo", "java", range(0, 20))), range(0, 20))));
        MarkdownLayout.Result result = new MarkdownLayout(80).layout(doc);
        MarkdownLayout.Line line = result.lines().stream().filter(value -> value.kind().equals("code") && value.copyText().equals("one")).findFirst().orElseThrow();
        assertEquals("│ one", line.text());
        assertEquals(2, line.runs().get(0).start());
        assertEquals(0, line.runs().get(0).projectionStart());
        assertEquals(3, line.runs().get(0).projectionEnd());
        assertTrue(result.lines().stream().filter(value -> value.kind().equals("code-label")).flatMap(value -> value.runs().stream()).allMatch(run -> run.projectionStart() == -1));
    }

    @Test void rawAndCodeLinesOverBudgetFailExplicitlyInsteadOfSilentTruncation() {
        String longLine = "x".repeat(4097);
        assertThrows(IllegalArgumentException.class, () -> new MarkdownLayout(80).layout(doc(List.of(new MarkdownBlock.Code(longLine, "", range(0, longLine.length()))))));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownLayout(80).layout(doc(List.of(new MarkdownBlock.Raw(longLine, range(0, longLine.length()))))));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownLayout(80).layout(doc(List.of(new MarkdownBlock.Paragraph(List.of(new MarkdownInline.Code(longLine, range(0, longLine.length()))), range(0, longLine.length()))))));
    }

    @Test void skippedSoftBreakDoesNotStealTheNextVisibleBreakOffset() {
        TextRange paragraphRange = new TextRange(new TextPosition(0, 0), new TextPosition(2, 1));
        MarkdownDocument document = doc(List.of(new MarkdownBlock.Paragraph(List.of(
            new MarkdownInline.Text("aa", range(0, 2)), new MarkdownInline.Break(false, paragraphRange),
            new MarkdownInline.Text("b", new TextRange(new TextPosition(1, 0), new TextPosition(1, 1))),
            new MarkdownInline.Break(false, paragraphRange),
            new MarkdownInline.Text("c", new TextRange(new TextPosition(2, 0), new TextPosition(2, 1)))), paragraphRange)));
        MarkdownLayout.Result result = new MarkdownLayout(3).layout(document);
        assertEquals("aa b c", document.projection().text());
        MarkdownLayout.Line line = result.lines().stream().filter(value -> value.text().equals("b c")).findFirst().orElseThrow();
        MarkdownLayout.Run space = line.runs().stream().filter(value -> line.text().substring(value.start(), value.end()).equals(" ")).findFirst().orElseThrow();
        assertEquals(4, space.projectionStart());
        assertEquals(5, space.projectionEnd());
    }

    private static MarkdownLayout.Run run(MarkdownLayout.Result result, String value) {
        for (MarkdownLayout.Line line : result.lines()) for (MarkdownLayout.Run run : line.runs()) {
            if (line.text().substring(run.start(), run.end()).equals(value)) return run;
        }
        throw new AssertionError("No run for " + value + " in " + result.lines());
    }
}
