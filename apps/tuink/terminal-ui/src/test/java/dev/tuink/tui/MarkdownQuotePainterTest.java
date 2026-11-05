package dev.tuink.tui;

import static dev.tuink.tui.PreviewSelectionTest.SOURCE;
import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.memory.RenderMemoryBudget;
import dev.tuiframe.style.CellColor;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.markdown.MarkdownBlock;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.MarkdownInline;
import dev.tuink.render.markdown.MarkdownGeometry;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.tui.render.EditorStyleAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownQuotePainterTest {
    @Test void quoteMarkersHaveTheirOwnColorAndInheritTheCodeBackground() {
        var document = new MarkdownDocument(new DocumentId("quote-colors"), 1, List.of(
            new MarkdownBlock.Raw("│ literal", SOURCE),
            new MarkdownBlock.Quote(List.of(
                new MarkdownBlock.Paragraph(List.of(new MarkdownInline.Text("quoted", SOURCE)), SOURCE),
                new MarkdownBlock.Code("return 7;", "java", SOURCE)), SOURCE)), List.of());
        var result = new MarkdownLayout(80, new MarkdownLayout.EmbeddedRenderer() {},
            new MarkdownCodeHighlighter(1_000_000)).layout(document);
        for (var theme : UiStyles.Theme.values()) {
            var palette = UiStyles.forTheme(theme);
            for (boolean color : new boolean[] {false, true}) for (boolean cached : new boolean[] {false, true}) {
                var budget = RenderMemoryBudget.bounded(1_000_000);
                try (var geometry = cached ? new MarkdownGeometry(result, budget) : null) {
                    var bounds = new Rect(1, 1, 30, result.lines().size());
                    var view = new MarkdownPreviewState.Placement(bounds, result, 0, 0, false, "",
                        PreviewSelection.Snapshot.EMPTY, List.of(), -1, "", false, null, null, geometry,
                        MarkdownPreviewState.Placement.Focus.CONTENT, MarkdownPreviewState.SearchFailure.NONE);
                    var cells = new CellBuffer(bounds.bottomExclusive() + 1, bounds.rightExclusive() + 1);
                    for (int repaint = 0; repaint < 2; repaint++) {
                        MarkdownPreviewPainter.paint(new Canvas(cells), view, palette, color);
                        for (int row = 0; row < result.lines().size(); row++) {
                            var line = result.lines().get(row);
                            if (line.text().equals("│ literal"))
                                assertEquals(color ? EditorStyleAdapter.toCell(palette.preview().body()).fg() : CellColor.DEFAULT,
                                    cells.get(1 + row, 1).style().fg());
                            for (var run : line.runs()) if (run.role() == MarkdownLayout.Role.QUOTE_MARKER) {
                                var marker = cells.get(1 + row, 1).style();
                                assertEquals(color ? EditorStyleAdapter.toCell(palette.preview().role(MarkdownLayout.Role.QUOTE_MARKER)).fg() : CellColor.DEFAULT, marker.fg());
                                assertEquals(color ? EditorStyleAdapter.toCell(
                                    line.kind().equals("code") || line.kind().equals("code-label")
                                        ? palette.preview().codeBody() : palette.preview().body()).bg()
                                    : CellColor.DEFAULT, marker.bg());
                            }
                            if (line.copyText().equals("quoted"))
                                assertEquals(color ? EditorStyleAdapter.toCell(palette.preview().body()).fg() : CellColor.DEFAULT,
                                    cells.get(1 + row, 3).style().fg());
                        }
                    }
                    if (cached) assertTrue(geometry.rows().hits() > 0);
                }
                assertEquals(0, budget.usedBytes());
            }
        }
    }
}
