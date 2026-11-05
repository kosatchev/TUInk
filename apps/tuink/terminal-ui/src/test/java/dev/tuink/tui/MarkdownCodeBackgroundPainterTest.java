package dev.tuink.tui;

import static dev.tuink.tui.PreviewSelectionTest.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.memory.RenderMemoryBudget;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.TokenSpan;
import dev.tuink.render.markdown.MarkdownGeometry;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuink.tui.render.EditorStyleAdapter;
import dev.tuink.tui.style.UiStyles;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownCodeBackgroundPainterTest {
    private static final String CODE = "return 7; // note";

    @Test void fullWidthBackgroundIncludesEmptyRowsLabelsAndHorizontalPanWithoutAddingPadding() {
        var result = fixture();
        for (var theme : UiStyles.Theme.values()) for (boolean color : new boolean[] {false, true}) {
            for (int path = 0; path < 3; path++) for (int width : new int[] {7, 22}) {
                var budget = RenderMemoryBudget.bounded(1_000_000);
                try (var geometry = path == 0 ? null : new MarkdownGeometry(result, budget)) {
                    if (path == 2) for (int row = 0; row < result.lines().size(); row++)
                        try (var admitted = geometry.acquire(row)) { assertNotNull(admitted); }
                    try (var blocker = path == 2
                        ? budget.tryReserve(budget.limitBytes() - budget.usedBytes()) : null) {
                        if (path == 2) assertNotNull(blocker);
                        var palette = UiStyles.forTheme(theme);
                        var body = color ? EditorStyleAdapter.toCell(palette.preview().body()) : CellStyle.DEFAULT;
                        var codeBody = color ? EditorStyleAdapter.toCell(palette.preview().codeBody()) : body;
                        var expectedBackground = codeBody.bg();
                        for (int left : new int[] {0, 4, 80}) {
                            var placement = placement(result, geometry, width, left, PreviewSelection.Snapshot.EMPTY,
                                List.of(), -1, new PreviewSelection.VisualPosition(1, left + 2));
                            var content = placement.contentBounds();
                            for (int repaint = 0; repaint < 2; repaint++) {
                                var cells = paint(placement, palette, color);
                                for (int row = 0; row < 3; row++) for (int column = 0; column < content.width(); column++)
                                    assertEquals(expectedBackground, cells.get(content.y() + row, content.x() + column).style().bg(),
                                        "theme=" + theme + ", color=" + color + ", path=" + path
                                            + ", left=" + left + ", row=" + row + ", column=" + column);
                                for (int row = 3; row < 5; row++) for (int column = 0; column < content.width(); column++)
                                    assertEquals(body.bg(), cells.get(content.y() + row, content.x() + column).style().bg(),
                                        "Inline code and ordinary paragraphs keep the body background");
                                assertEquals(CellColor.DEFAULT, cells.get(content.y(), content.x() - 1).style().bg(),
                                    "The background is clipped to content, outside the window");
                                if (left == 0) {
                                    assertEquals("r", cells.get(content.y(), content.x()).grapheme(), "Code gains no padding");
                                    assertEquals(color ? EditorStyleAdapter.toCell(palette.syntax().resolve(SemanticStyleKey.KEYWORD)).fg() : CellColor.DEFAULT,
                                        cells.get(content.y(), content.x()).style().fg());
                                    if (content.width() > 8) {
                                        assertEquals(codeBody.fg(),
                                            cells.get(content.y(), content.x() + 8).style().fg(), "Punctuation keeps a readable base");
                                        assertEquals(color ? EditorStyleAdapter.toCell(palette.syntax().resolve(SemanticStyleKey.COMMENT)).fg() : CellColor.DEFAULT,
                                            cells.get(content.y(), content.x() + 10).style().fg(), "Gray comments retain their syntax color");
                                    }
                                    assertEquals(color ? EditorStyleAdapter.toCell(palette.preview().role(MarkdownLayout.Role.CODE)).fg() : CellColor.DEFAULT,
                                        cells.get(content.y() + 3, content.x()).style().fg(), "Inline code keeps its foreground");
                                }
                            }
                        }
                        if (path == 1) assertTrue(geometry.rows().hits() > 0);
                        if (path == 2) assertEquals(0, geometry.rows().size());
                    }
                }
                assertEquals(0, budget.usedBytes());
            }
        }
    }

    @Test void searchAndSelectionOverrideCodeBackgroundAndReuseThePreparedRows() {
        var result = fixture();
        var palette = UiStyles.forTheme(UiStyles.Theme.CYAN);
        for (int path = 0; path < 3; path++) {
            var budget = RenderMemoryBudget.bounded(1_000_000);
            try (var geometry = path == 0 ? null : new MarkdownGeometry(result, budget)) {
                if (path == 2) for (int row = 0; row < result.lines().size(); row++)
                    try (var admitted = geometry.acquire(row)) { assertNotNull(admitted); }
                try (var blocker = path == 2
                    ? budget.tryReserve(budget.limitBytes() - budget.usedBytes()) : null) {
                    var initial = placement(result, geometry, 22, 0, PreviewSelection.Snapshot.EMPTY, List.of(), -1, null);
                    paint(initial, palette, true);
                    long misses = geometry == null ? 0 : geometry.rows().misses();
                    var placement = placement(result, geometry, 22, 0, new PreviewSelection.Snapshot(0, 6),
                        List.of(new PreviewSelection.Range(0, 6), new PreviewSelection.Range(7, 8)), 1, null);
                    var cells = paint(placement, palette, true);
                    var content = placement.contentBounds();
                    assertEquals(EditorStyleAdapter.toCell(palette.windows().renderSelection()), cells.get(content.y(), content.x()).style());
                    assertEquals(EditorStyleAdapter.toCell(palette.windows().renderCurrentSearchMatch()), cells.get(content.y(), content.x() + 7).style());
                    assertEquals(CellColor.DARK_GRAY, cells.get(content.y(), content.x() + 8).style().bg());
                    assertEquals(CellColor.DARK_GRAY, cells.get(content.y(), content.rightExclusive() - 1).style().bg());
                    if (path == 1) assertEquals(misses, geometry.rows().misses(), "Overlays do not invalidate base rows");
                }
            }
            assertEquals(0, budget.usedBytes());
        }
    }

    private static MarkdownLayout.Result fixture() {
        return result(CODE + "\n\ninline\nplain",
            new MarkdownLayout.Line(CODE, List.of(new MarkdownLayout.Run(0, CODE.length(), MarkdownLayout.Role.CODE_BLOCK,
                SOURCE, 0, CODE.length())), CODE, SOURCE, "code", List.of(
                    new TokenSpan(0, 6, SemanticStyleKey.KEYWORD), new TokenSpan(7, 8, SemanticStyleKey.NUMBER),
                    new TokenSpan(10, CODE.length(), SemanticStyleKey.COMMENT))),
            new MarkdownLayout.Line("", List.of(new MarkdownLayout.Run(0, 0, MarkdownLayout.Role.CODE_BLOCK, SOURCE)), "", SOURCE, "code"),
            new MarkdownLayout.Line("[java]", List.of(new MarkdownLayout.Run(0, 6, MarkdownLayout.Role.QUOTE, SOURCE)), "", SOURCE, "code-label"),
            new MarkdownLayout.Line("inline", List.of(new MarkdownLayout.Run(0, 6, MarkdownLayout.Role.CODE, SOURCE,
                CODE.length() + 2, CODE.length() + 8)), "inline", SOURCE, "paragraph"),
            new MarkdownLayout.Line("plain", List.of(new MarkdownLayout.Run(0, 5, MarkdownLayout.Role.TEXT, SOURCE)), "plain", SOURCE, "paragraph"));
    }

    private static MarkdownPreviewState.Placement placement(MarkdownLayout.Result result, MarkdownGeometry geometry,
        int width, int left, PreviewSelection.Snapshot selection, List<PreviewSelection.Range> matches,
        int activeMatch, PreviewSelection.VisualPosition caret) {
        return new MarkdownPreviewState.Placement(new Rect(2, 1, width, result.lines().size() + 2), result,
            0, left, false, "", selection, matches, activeMatch, matches.isEmpty() ? "" : "return", false,
            caret, null, geometry, MarkdownPreviewState.Placement.Focus.CONTENT, MarkdownPreviewState.SearchFailure.NONE);
    }

    private static CellBuffer paint(MarkdownPreviewState.Placement placement, UiStyles.Palette palette, boolean color) {
        var cells = new CellBuffer(placement.bounds().bottomExclusive() + 1, placement.bounds().rightExclusive() + 1);
        MarkdownPreviewPainter.paint(new Canvas(cells), placement, palette, color);
        return cells;
    }
}
