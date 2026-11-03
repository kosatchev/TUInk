package dev.tuink.tui;

import static dev.tuink.tui.PreviewSelectionTest.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.memory.RenderMemoryBudget;
import dev.tuink.render.markdown.MarkdownGeometry;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuink.tui.render.EditorStyleAdapter;
import dev.tuink.tui.style.UiStyles;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownPreparedPainterTest {
    @Test void preparedAndStreamingPreviewAgreeOnStylesProjectionOverlaysAndUnicodeClipping() {
        var result = fixture();
        var budget = RenderMemoryBudget.bounded(1_000_000);
        try (var geometry = new MarkdownGeometry(result, budget)) {
            for (boolean color : new boolean[] {false, true}) {
                for (int width = 2; width <= 12; width++) {
                    for (int left = 0; left <= 14; left++) {
                        var bounds = new Rect(3, 1, width, 4);
                        var selection = new PreviewSelection.Snapshot(3, 7);
                        var matches = List.of(new PreviewSelection.Range(0, 2), new PreviewSelection.Range(5, 7));
                        var caret = new PreviewSelection.VisualPosition(0, left + 1);
                        assertCellsEqual(paint(placement(result, bounds, left, selection, matches, caret, null), color),
                            paint(placement(result, bounds, left, selection, matches, caret, geometry), color));
                    }
                }
            }
            assertTrue(geometry.rows().hits() > 0);
            assertEquals(result.lines().size(), geometry.misses(), "Prepared slices must reuse the full-row index");
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void selectionSearchCaretAndOverlayPaletteReuseRowsWhileColorInvalidatesBaseStyles() {
        var result = fixture();
        var budget = RenderMemoryBudget.bounded(1_000_000);
        try (var geometry = new MarkdownGeometry(result, budget)) {
            var bounds = new Rect(2, 1, 16, 4);
            paint(placement(result, bounds, 0, PreviewSelection.Snapshot.EMPTY, List.of(), null, geometry), true);
            long misses = geometry.rows().misses(), hits = geometry.rows().hits();
            var next = placement(result, bounds, 0, new PreviewSelection.Snapshot(3, 7),
                List.of(new PreviewSelection.Range(0, 2)), new PreviewSelection.VisualPosition(0, 4), geometry);
            var painted = paint(next, true);
            assertEquals(misses, geometry.rows().misses());
            assertEquals(hits + result.lines().size(), geometry.rows().hits());
            assertEquals(EditorStyleAdapter.toCell(UiStyles.forTheme(UiStyles.Theme.CYAN).renderSelection()),
                painted.get(2, 5).style());
            paint(next, false);
            assertEquals(misses + result.lines().size(), geometry.rows().misses());
            var otherPalette = new CellBuffer(7, 24);
            MarkdownPreviewPainter.paint(new Canvas(otherPalette), next, UiStyles.forTheme(UiStyles.Theme.LIGHT), false);
            assertEquals(misses + result.lines().size(), geometry.rows().misses(),
                "Themes currently share the same body style; transient overlay palettes do not invalidate base rows");
            assertEquals(EditorStyleAdapter.toCell(UiStyles.forTheme(UiStyles.Theme.LIGHT).renderSelection()),
                otherPalette.get(2, 5).style());
            assertEquals(result.lines().size(), geometry.misses());
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void refusedPreparedArraysPaintFromTheAdmittedIndexWithoutAnotherGeometryBuild() {
        var result = fixture();
        var budget = RenderMemoryBudget.bounded(1_000_000);
        var bounds = new Rect(3, 1, 10, 4);
        var selection = new PreviewSelection.Snapshot(3, 7);
        var matches = List.of(new PreviewSelection.Range(0, 2));
        var caret = new PreviewSelection.VisualPosition(0, 4);
        try (var geometry = new MarkdownGeometry(result, budget)) {
            for (int row = 0; row < result.lines().size(); row++)
                try (var admitted = geometry.acquire(row)) { assertNotNull(admitted); }
            long admittedBytes = budget.usedBytes();
            try (var blocker = budget.tryReserve(budget.limitBytes() - admittedBytes)) {
                assertNotNull(blocker);
                assertCellsEqual(paint(placement(result, bounds, 2, selection, matches, caret, null), true),
                    paint(placement(result, bounds, 2, selection, matches, caret, geometry), true));
                assertEquals(0, geometry.rows().size());
                assertEquals(result.lines().size(), geometry.misses());
            }
            assertEquals(admittedBytes, budget.usedBytes());
            paint(placement(result, bounds, 2, selection, matches, caret, geometry), true);
            assertEquals(result.lines().size(), geometry.rows().size());
        }
        assertEquals(0, budget.usedBytes(), "Geometry disposal must release prepared rows and their index dependencies");
    }

    private static MarkdownLayout.Result fixture() {
        String text = "e\u0301😀\t界Z";
        var runs = List.of(new MarkdownLayout.Run(0, 1, MarkdownLayout.Role.TEXT, SOURCE, 0, 1),
            new MarkdownLayout.Run(1, 4, MarkdownLayout.Role.STRONG, SOURCE, 1, 4),
            new MarkdownLayout.Run(4, text.length(), MarkdownLayout.Role.CODE, SOURCE, 4, text.length()));
        return result(text, new MarkdownLayout.Line(text, runs, text, SOURCE, "code"),
            new MarkdownLayout.Line(" │ x² ─ ", List.of(new MarkdownLayout.Run(3, 5,
                MarkdownLayout.Role.MATH, SOURCE, 4, 7)), "", SOURCE, "math"),
            new MarkdownLayout.Line("[!] note", List.of(new MarkdownLayout.Run(0, 8,
                MarkdownLayout.Role.DIAGNOSTIC, SOURCE)), "", SOURCE, "diagnostic"));
    }

    private static MarkdownPreviewState.Placement placement(MarkdownLayout.Result result, Rect bounds, int left,
        PreviewSelection.Snapshot selection, List<PreviewSelection.Range> matches,
        PreviewSelection.VisualPosition caret, MarkdownGeometry geometry) {
        return new MarkdownPreviewState.Placement(bounds, result, 0, left, false, "", selection, matches,
            matches.isEmpty() ? -1 : 0, matches.isEmpty() ? "" : "find", false, caret, null, geometry);
    }

    private static CellBuffer paint(MarkdownPreviewState.Placement placement, boolean color) {
        var result = new CellBuffer(7, 24);
        MarkdownPreviewPainter.paint(new Canvas(result), placement, UiStyles.forTheme(UiStyles.Theme.CYAN), color);
        return result;
    }

    private static void assertCellsEqual(CellBuffer expected, CellBuffer actual) {
        for (int row = 0; row < expected.rows(); row++)
            for (int col = 0; col < expected.cols(); col++)
                assertEquals(expected.get(row, col), actual.get(row, col), "row=" + row + ", col=" + col);
    }
}
