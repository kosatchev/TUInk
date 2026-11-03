package dev.tuink.render.markdown;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.cell.CellText;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.markdown.MarkdownBlock;
import dev.tuink.markdown.MarkdownInline;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownTableLayoutTest {
    private static final TextRange SOURCE = new TextRange(new TextPosition(0, 0), new TextPosition(4, 0));
    private static MarkdownBlock.Cell text(String value) { return new MarkdownBlock.Cell(List.of(new MarkdownInline.Text(value, SOURCE)), SOURCE); }
    private static MarkdownBlock.Row row(boolean header, String... values) {
        return new MarkdownBlock.Row(header, java.util.Arrays.stream(values).map(MarkdownTableLayoutTest::text).toList(), SOURCE);
    }
    private static final MarkdownLayout.EmbeddedRenderer PLAIN = new MarkdownLayout.EmbeddedRenderer() {};

    @Test void tableHasAlignedBordersAndSemanticCopyWithoutBorders() {
        MarkdownBlock.Table table = new MarkdownBlock.Table(List.of(row(true, "Name", "Value"), row(false, "one", "42")),
            List.of(MarkdownBlock.Alignment.LEFT, MarkdownBlock.Alignment.RIGHT), SOURCE);
        List<MarkdownLayout.Line> rows = MarkdownTableLayout.layout(table, 80, "", PLAIN);
        assertEquals("┌──────┬───────┐", rows.get(0).text());
        assertEquals("│ Name │ Value │", rows.get(1).text());
        assertEquals("│ one  │    42 │", rows.get(3).text());
        assertEquals("one\t42", rows.get(3).copyText());
        assertTrue(rows.get(4).text().startsWith("└"));
        int width = CellText.width(rows.get(0).text());
        assertTrue(rows.stream().allMatch(line -> CellText.width(line.text()) == width));
        assertTrue(rows.get(0).runs().stream().allMatch(run -> run.source() == null));
        assertTrue(rows.get(1).runs().stream().anyMatch(run -> run.role() == MarkdownLayout.Role.STRONG && SOURCE.equals(run.source())));
    }

    @Test void narrowTablesWrapCellsAndKeepEveryRowAtTheSameVisualWidth() {
        MarkdownBlock.Table table = new MarkdownBlock.Table(List.of(row(true, "Header", "Other"), row(false, "several words here", "x")), List.of(), SOURCE);
        List<MarkdownLayout.Line> rows = MarkdownTableLayout.layout(table, 17, "", PLAIN);
        assertTrue(rows.size() > 5);
        assertTrue(rows.stream().allMatch(line -> CellText.width(line.text()) == 17));
        assertTrue(rows.stream().anyMatch(line -> line.text().contains("words")));
        assertTrue(rows.stream().filter(line -> line.kind().equals("table")).allMatch(line -> !line.copyText().contains("│")));
    }

    @Test void unicodeWidthAndNestedPrefixDoNotBreakBorderColumns() {
        MarkdownBlock.Table table = new MarkdownBlock.Table(List.of(row(true, "字", "Emoji"), row(false, "é", "👩‍💻")),
            List.of(MarkdownBlock.Alignment.CENTER, MarkdownBlock.Alignment.CENTER), SOURCE);
        List<MarkdownLayout.Line> rows = MarkdownTableLayout.layout(table, 40, "│ ", PLAIN);
        int width = CellText.width(rows.get(0).text());
        assertTrue(rows.stream().allMatch(line -> CellText.width(line.text()) == width));
        assertTrue(rows.stream().allMatch(line -> line.text().startsWith("│ ")));
        assertTrue(rows.stream().anyMatch(line -> line.text().contains("👩‍💻")));
        for (MarkdownLayout.Line line : rows) for (MarkdownLayout.Run run : line.runs())
            assertTrue(run.start() >= 0 && run.end() <= line.text().length());
    }

    @Test void multilineMathKeepsItsBoxEvenWhenViewportIsTooNarrow() {
        MarkdownBlock.Cell math = new MarkdownBlock.Cell(List.of(new MarkdownInline.Math("fraction", SOURCE)), SOURCE);
        MarkdownBlock.Table table = new MarkdownBlock.Table(List.of(new MarkdownBlock.Row(false, List.of(math, text("x")), SOURCE)), List.of(), SOURCE);
        MarkdownLayout.EmbeddedRenderer renderer = new MarkdownLayout.EmbeddedRenderer() {
            @Override public MarkdownLayout.Box math(String expression) { return new MarkdownLayout.Box(List.of(" numerator ", "───────────", "    y      "), 1); }
        };
        List<MarkdownLayout.Line> rows = MarkdownTableLayout.layout(table, 8, "", renderer);
        assertTrue(CellText.width(rows.get(0).text()) > 8);
        assertTrue(rows.stream().anyMatch(line -> line.text().contains("numerator")));
        assertTrue(rows.stream().anyMatch(line -> line.text().contains("───────────")));
        assertEquals(5, rows.size());
    }

    @Test void emptyCellsAndMissingColumnsArePadded() {
        MarkdownBlock.Table table = new MarkdownBlock.Table(List.of(row(true, "a", "b"), row(false, "")), List.of(), SOURCE);
        List<MarkdownLayout.Line> rows = MarkdownTableLayout.layout(table, 80, "", PLAIN);
        assertEquals(CellText.width(rows.get(0).text()), CellText.width(rows.get(3).text()));
        assertEquals("\t", rows.get(3).copyText());
    }

    @Test void oversizedTablesHaveLocalDiagnostics() {
        List<MarkdownBlock.Cell> cells = new ArrayList<>();
        for (int i = 0; i < 65; i++) cells.add(text("x"));
        MarkdownBlock.Table table = new MarkdownBlock.Table(List.of(new MarkdownBlock.Row(false, cells, SOURCE)), List.of(), SOURCE);
        List<MarkdownLayout.Line> rows = MarkdownTableLayout.layout(table, 80, "", PLAIN);
        assertEquals(1, rows.size()); assertEquals("diagnostic", rows.get(0).kind());
    }

    @Test void tabsInCodeUseCellLocalStopsWithoutMovingTheFrame() {
        MarkdownBlock.Cell code = new MarkdownBlock.Cell(List.of(new MarkdownInline.Code("\tx", SOURCE)), SOURCE);
        MarkdownBlock.Table table = new MarkdownBlock.Table(List.of(new MarkdownBlock.Row(false, List.of(text("字"), code), SOURCE)), List.of(), SOURCE);
        List<MarkdownLayout.Line> rows = MarkdownTableLayout.layout(table, 80, "│ ", PLAIN);
        int width = CellText.width(rows.get(0).text());
        assertTrue(rows.stream().allMatch(line -> CellText.width(line.text()) == width));
        assertFalse(rows.get(1).text().contains("\t"));
        assertEquals("字\t\tx", rows.get(1).copyText());
    }
}
