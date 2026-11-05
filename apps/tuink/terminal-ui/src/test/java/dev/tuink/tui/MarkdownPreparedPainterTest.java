package dev.tuink.tui;

import static dev.tuink.tui.PreviewSelectionTest.*;
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
import dev.tuink.editor.highlight.TokenSpan;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.tui.render.EditorStyleAdapter;
import dev.tuink.tui.style.UiStyles;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownPreparedPainterTest {
    @Test void codeBlocksUseReadableBaseBetweenTokensAndOnEntireUntokenizedRows() {
        var document = new MarkdownDocument(new DocumentId("code-base-colors"), 1, List.of(
            new MarkdownBlock.Code("int value = 7;\nvalue = other;", "java", SOURCE),
            new MarkdownBlock.Code("other = value;", "", SOURCE),
            new MarkdownBlock.Paragraph(List.of(new MarkdownInline.Code("inline", SOURCE)), SOURCE)), List.of());
        for (long styleBytes : new long[] {0, 1_000_000}) {
            var result = new MarkdownLayout(80, new MarkdownLayout.EmbeddedRenderer() {},
                new MarkdownCodeHighlighter(styleBytes)).layout(document);
            for (var theme : UiStyles.Theme.values()) for (boolean color : new boolean[] {false, true}) {
                for (int path = 0; path < 3; path++) {
                    var budget = RenderMemoryBudget.bounded(1_000_000);
                    try (var geometry = path == 0 ? null : new MarkdownGeometry(result, budget)) {
                        if (path == 2) for (int row = 0; row < result.lines().size(); row++)
                            try (var admitted = geometry.acquire(row)) { assertNotNull(admitted); }
                        try (var blocker = path == 2
                            ? budget.tryReserve(budget.limitBytes() - budget.usedBytes()) : null) {
                            var palette = UiStyles.forTheme(theme);
                            var view = placement(result, new Rect(2, 1, 20, result.lines().size()), 0,
                                PreviewSelection.Snapshot.EMPTY, List.of(), null, geometry);
                            var cells = new CellBuffer(result.lines().size() + 2, 24);
                            // The second paint also checks admitted rows on a cache hit.
                            for (int repaint = 0; repaint < 2; repaint++) {
                                MarkdownPreviewPainter.paint(new Canvas(cells), view, palette, color);
                                for (int row = 0; row < result.lines().size(); row++) {
                                    var line = result.lines().get(row);
                                    if (line.kind().equals("code")) for (int column = 0; column < line.text().length(); column++) {
                                        var expected = color ? EditorStyleAdapter.toCell(palette.preview().codeBody()).fg() : CellColor.DEFAULT;
                                        if (color) for (var token : line.syntax())
                                            if (token.start() <= column && column < token.end()) {
                                                var mapped = EditorStyleAdapter.toCell(palette.syntax().resolve(token.styleKey())).fg();
                                                if (mapped != CellColor.DEFAULT) expected = mapped;
                                            }
                                        assertEquals(expected, cells.get(1 + row, 2 + column).style().fg(),
                                            "theme=" + theme + ", color=" + color + ", path=" + path
                                                + ", row=" + row + ", column=" + column);
                                    }
                                    if (line.copyText().equals("inline"))
                                        assertEquals(color ? EditorStyleAdapter.toCell(palette.preview().role(MarkdownLayout.Role.CODE)).fg() : CellColor.DEFAULT,
                                            cells.get(1 + row, 2).style().fg(), "inline code keeps its Markdown color");
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
    }

    @Test void narrowSlicesAdmitOnlyVisibleSyntaxSpans() {
        String text = "if ".repeat(1000);
        var tokens = java.util.stream.IntStream.range(0, 1000)
            .mapToObj(index -> new TokenSpan(index * 3, index * 3 + 2, SemanticStyleKey.KEYWORD)).toList();
        var line = new MarkdownLayout.Line(text, List.of(new MarkdownLayout.Run(0, text.length(),
            MarkdownLayout.Role.CODE_BLOCK, SOURCE, 0, text.length())), text, SOURCE, "code", tokens);
        var result = result(text, line);
        var budget = RenderMemoryBudget.bounded(1_000_000);
        try (var geometry = new MarkdownGeometry(result, budget)) {
            try (var admitted = geometry.acquire(0)) { assertNotNull(admitted); }
            try (var blocker = budget.tryReserve(budget.limitBytes() - budget.usedBytes() - 8192)) {
                assertNotNull(blocker);
                paint(placement(result, new Rect(2, 1, 4, 3), 0, PreviewSelection.Snapshot.EMPTY,
                    List.of(), null, geometry), true);
                assertEquals(1, geometry.rows().size(), "Invisible tokens must not prevent a small slice from being cached");
            }
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void codeTokensUseTheSourceSyntaxPaletteAndRespectMonochrome() {
        String text = "return 7; // hi";
        var line = new MarkdownLayout.Line(text,
            List.of(new MarkdownLayout.Run(0, text.length(), MarkdownLayout.Role.CODE_BLOCK, SOURCE, 0, text.length())),
            text, SOURCE, "code", List.of(new TokenSpan(0, 6, SemanticStyleKey.KEYWORD),
                new TokenSpan(7, 8, SemanticStyleKey.NUMBER), new TokenSpan(10, text.length(), SemanticStyleKey.COMMENT)));
        var result = result(text, line);
        var bounds = new Rect(2, 1, 20, 1);
        var placement = placement(result, bounds, 0, PreviewSelection.Snapshot.EMPTY, List.of(), null, null);
        var colored = paint(placement, true);
        var palette = UiStyles.forTheme(UiStyles.Theme.CYAN).syntax();
        for (var token : line.syntax()) {
            var expected = EditorStyleAdapter.toCell(palette.resolve(token.styleKey()));
            var actual = colored.get(1, 2 + token.start()).style();
            assertEquals(expected.fg(), actual.fg());
            assertEquals(expected.bold(), actual.bold());
        }
        var monochrome = paint(placement, false);
        for (int column = 0; column < text.length(); column++)
            assertEquals(CellColor.DEFAULT,
                monochrome.get(1, 2 + column).style().fg());
    }

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

    @Test void selectionSearchAndCaretReuseRowsWhileCapabilityAndPaletteIdentityInvalidateBaseStyles() {
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
            assertEquals(EditorStyleAdapter.toCell(UiStyles.forTheme(UiStyles.Theme.CYAN).windows().renderSelection()),
                painted.get(2, 5).style());
            paint(next, false);
            assertEquals(misses + result.lines().size(), geometry.rows().misses());
            var otherPalette = new CellBuffer(7, 24);
            MarkdownPreviewPainter.paint(new Canvas(otherPalette), next, UiStyles.forTheme(UiStyles.Theme.MONOCHROME), false);
            assertEquals(misses + result.lines().size() * 2, geometry.rows().misses(),
                "Distinct immutable palette snapshots have distinct prepared styles even when their body colors agree");
            assertEquals(EditorStyleAdapter.toCell(UiStyles.forTheme(UiStyles.Theme.MONOCHROME).windows().renderSelection()),
                otherPalette.get(2, 5).style());
            paint(next, true);
            assertEquals(misses + result.lines().size() * 2, geometry.rows().misses(),
                "Returning to the original palette reuses its admitted rows");
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
        return result(text, new MarkdownLayout.Line(text, runs, text, SOURCE, "code", List.of(
            new TokenSpan(1, 4, SemanticStyleKey.COMMENT), new TokenSpan(4, 5, SemanticStyleKey.NUMBER),
            new TokenSpan(5, text.length(), SemanticStyleKey.KEYWORD))),
            new MarkdownLayout.Line(" │ x² ─ ", List.of(new MarkdownLayout.Run(3, 5,
                MarkdownLayout.Role.MATH, SOURCE, 4, 7)), "", SOURCE, "math"),
            new MarkdownLayout.Line("[!] note", List.of(new MarkdownLayout.Run(0, 8,
                MarkdownLayout.Role.DIAGNOSTIC, SOURCE)), "", SOURCE, "diagnostic"));
    }

    private static MarkdownPreviewState.Placement placement(MarkdownLayout.Result result, Rect bounds, int left,
        PreviewSelection.Snapshot selection, List<PreviewSelection.Range> matches,
        PreviewSelection.VisualPosition caret, MarkdownGeometry geometry) {
        return new MarkdownPreviewState.Placement(bounds, result, 0, left, false, "", selection, matches,
            matches.isEmpty() ? -1 : 0, matches.isEmpty() ? "" : "find", false, caret, null, geometry,
            MarkdownPreviewState.Placement.Focus.CONTENT, MarkdownPreviewState.SearchFailure.NONE);
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
