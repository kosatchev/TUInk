package dev.tuink.render.markdown;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.markdown.MarkdownBlock;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.MarkdownInline;
import dev.tuiframe.cell.CellText;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownLayoutTest {
    private static final TextRange SOURCE = new TextRange(new TextPosition(0, 0), new TextPosition(0, 20));
    private static MarkdownDocument document(MarkdownBlock... blocks) {
        return new MarkdownDocument(new DocumentId("layout"), 1, List.of(blocks), List.of());
    }
    @Test void preservesCodeAndReadyMadeDiagramGeometryWithHorizontalOverflow() {
        String code = "┌──────────┐\n│ preview  │\n└──────────┘";
        var layout = new MarkdownLayout(5).layout(document(new MarkdownBlock.Code(code, "text", SOURCE)));
        List<MarkdownLayout.Line> content = layout.lines().stream().filter(line -> line.kind().equals("code")).toList();
        assertEquals(List.of(code.split("\n")), content.stream().map(MarkdownLayout.Line::text).toList());
        assertEquals(code, String.join("\n", content.stream().map(MarkdownLayout.Line::copyText).toList()));
        assertTrue(layout.extent() > layout.columns());
    }
    @Test void wrapsAtGraphemeBoundariesAndRetainsSemanticStyles() {
        var paragraph = new MarkdownBlock.Paragraph(List.of(new MarkdownInline.Text("界界界 ", SOURCE),
            new MarkdownInline.Styled(MarkdownInline.Style.STRONG,
                List.of(new MarkdownInline.Text("bold", SOURCE)), SOURCE)), SOURCE);
        var layout = new MarkdownLayout(4).layout(document(paragraph));
        assertTrue(layout.lines().stream().allMatch(line -> CellText.width(line.text()) <= 4));
        assertTrue(layout.lines().stream().flatMap(line -> line.runs().stream()).anyMatch(run -> run.role() == MarkdownLayout.Role.STRONG));
        assertTrue(layout.lines().stream().filter(line -> !line.text().isEmpty()).allMatch(line -> line.source().equals(SOURCE)));
    }
    @Test void inlineMathBoxesIncreaseParagraphHeightAndAlignWithSurroundingBaseline() {
        var math = new MarkdownLayout.EmbeddedRenderer() {
            @Override public MarkdownLayout.Box math(String source) { return new MarkdownLayout.Box(List.of(" a ", "───", " b "), 1); }
        };
        var paragraph = new MarkdownBlock.Paragraph(List.of(new MarkdownInline.Text("x = ", SOURCE),
            new MarkdownInline.Math("\\frac{a}{b}", SOURCE)), SOURCE);
        var result = new MarkdownLayout(20, math).layout(document(paragraph));
        assertEquals("x = ───", result.lines().get(1).text());
        assertTrue(result.lines().get(0).text().contains("a"));
        assertTrue(result.lines().get(2).text().contains("b"));
    }
}
