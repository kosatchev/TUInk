package dev.tuink.tui.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.DisplayText;
import dev.tuink.core.EditorState;
import dev.tuink.core.GutterDiffMarker;
import dev.tuink.core.Position;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.render.Color;
import dev.tuink.render.LineVisual;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import dev.tuink.tui.style.CellStyle;
import dev.tuink.tui.style.UiStyles;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RenderRasterizerUnicodeTest {
    @Test
    void rasterizesControlsCjkEmojiAndCombiningClustersIntoSafeCells() {
        RenderPlan plan = plan("\u001B中😀e\u0301", new EditorState(), 5, 16);

        CellBuffer buffer = new RenderRasterizer().rasterize(plan, 5, 16);
        int start = plan.gutterWidth();

        assertEquals("␛", buffer.get(1, start).grapheme());
        assertEquals("中", buffer.get(1, start + 1).grapheme());
        assertTrue(buffer.get(1, start + 2).continuation());
        assertEquals("😀", buffer.get(1, start + 3).grapheme());
        assertTrue(buffer.get(1, start + 4).continuation());
        assertEquals("e\u0301", buffer.get(1, start + 5).grapheme());
        for (int row = 0; row < buffer.rows(); row++) {
            for (int col = 0; col < buffer.cols(); col++) {
                assertFalse(DisplayText.containsUnsafeControl(buffer.get(row, col).grapheme()));
            }
        }
    }

    @Test
    void horizontalViewportInsideTabKeepsRemainingCellsAndFollowingTextAligned() {
        EditorState state = new EditorState();
        state.setLeftVisualColumn(2);
        RenderPlan plan = plan("A\tB", state, 5, 8);

        CellBuffer buffer = new RenderRasterizer().rasterize(plan, 5, 8);
        int start = plan.gutterWidth();

        assertEquals(" ", buffer.get(1, start).grapheme());
        assertEquals(" ", buffer.get(1, start + 1).grapheme());
        assertEquals("B", buffer.get(1, start + 2).grapheme());
    }

    @Test
    void cellTextMeasuresAndClipsWithoutSplittingWideGraphemes() {
        assertEquals(6, CellText.width("A😀中e\u0301"));
        assertEquals("A😀 ", CellText.fit("A😀中", 4));
        assertEquals(" 中", CellText.clip("A😀中", 2, 3));
    }

    @Test
    void laterOverlayWinsWhenItsBoundaryFallsInsideACombiningCluster() {
        Style base = new Style(Color.RED, Color.DEFAULT, false, false);
        Style overlay = new Style(Color.BLUE, Color.YELLOW, true, true);
        String text = "Ae\u0301Z";
        LineVisual line = line(
            text,
            List.of(
                new StyleSpan(0, text.length(), base),
                new StyleSpan(2, 3, overlay)
            ),
            1,
            0,
            0
        );

        CellBuffer buffer = new RenderRasterizer().rasterize(directPlan(line, 5, 12), 5, 12);

        assertEquals(EditorStyleAdapter.toCell(base), buffer.get(1, 0).style());
        assertEquals("e\u0301", buffer.get(1, 1).grapheme());
        assertEquals(EditorStyleAdapter.toCell(overlay), buffer.get(1, 1).style());
        assertEquals(EditorStyleAdapter.toCell(base), buffer.get(1, 2).style());
    }

    @Test
    void preparedBaseLineIsReusedWhileDynamicOverlayChanges() {
        RenderRasterizer rasterizer = new RenderRasterizer();
        Style base = new Style(Color.RED, Color.DEFAULT, false, false);
        Style firstOverlay = new Style(Color.BLUE, Color.YELLOW, false, false);
        Style secondOverlay = new Style(Color.GREEN, Color.MAGENTA, true, false);
        LineVisual first = line(
            "abc",
            List.of(
                new StyleSpan(0, 3, base),
                new StyleSpan(0, 1, firstOverlay)
            ),
            1,
            0,
            0
        );
        CellBuffer firstFrame = rasterizer.rasterize(directPlan(first, 4, 8), 4, 8);
        long misses = rasterizer.preparedLineCacheMisses();

        LineVisual second = line(
            "abc",
            List.of(
                new StyleSpan(0, 3, base),
                new StyleSpan(1, 2, secondOverlay)
            ),
            1,
            0,
            0
        );
        CellBuffer secondFrame = rasterizer.rasterize(directPlan(second, 4, 8), 4, 8);

        assertEquals(EditorStyleAdapter.toCell(firstOverlay), firstFrame.get(1, 0).style());
        assertEquals(EditorStyleAdapter.toCell(base), secondFrame.get(1, 0).style());
        assertEquals(EditorStyleAdapter.toCell(secondOverlay), secondFrame.get(1, 1).style());
        assertEquals(EditorStyleAdapter.toCell(base), secondFrame.get(1, 2).style());
        assertEquals(misses, rasterizer.preparedLineCacheMisses());
        assertTrue(rasterizer.preparedLineCacheHits() > 0);
    }

    @Test
    void renderPlanBuilderKeepsSelectionOutOfPreparedBaseStyles() {
        SimpleLineBuffer lines = new SimpleLineBuffer();
        lines.setLine(0, "abcdef");
        TextModel model = new TextModel(lines);
        Style syntax = new Style(Color.RED, Color.DEFAULT, false, false);
        Style selection = new Style(Color.BLACK, Color.BRIGHT_CYAN, false, false);
        RenderPlanBuilder builder = new RenderPlanBuilder(
            text -> List.of(new StyleSpan(0, text.length(), syntax))
        );
        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        builder.setDecorationStyles(
            selection,
            Style.DEFAULT,
            Style.DEFAULT,
            Style.DEFAULT,
            Style.DEFAULT,
            Style.DEFAULT,
            List.of(Style.DEFAULT)
        );
        EditorState state = new EditorState();
        state.setSelectionAnchor(new Position(0, 0));
        state.setCursor(new Position(0, 2));
        RenderPlan firstPlan = builder.build(model, state, 4, 12, "", "");

        assertEquals(1, firstPlan.lines().get(0).baseSpanCount());
        assertEquals(2, firstPlan.lines().get(0).spans().size());

        RenderRasterizer rasterizer = new RenderRasterizer();
        rasterizer.rasterize(firstPlan, 4, 12);
        long misses = rasterizer.preparedLineCacheMisses();

        state.setCursor(new Position(0, 4));
        RenderPlan secondPlan = builder.build(model, state, 4, 12, "", "");
        rasterizer.rasterize(secondPlan, 4, 12);

        assertEquals(misses, rasterizer.preparedLineCacheMisses());
        assertTrue(rasterizer.preparedLineCacheHits() > 0);

        builder.setSyntaxHighlightingEnabled(false);
        RenderPlan syntaxDisabledPlan = builder.build(model, state, 4, 12, "", "");
        rasterizer.rasterize(syntaxDisabledPlan, 4, 12);
        assertTrue(rasterizer.preparedLineCacheMisses() > misses);
    }

    @Test
    void whitespaceMarkersKeepOverlayAttributesAcrossThemeAndVisibilityChanges() {
        RenderRasterizer rasterizer = new RenderRasterizer();
        Style overlay = new Style(Color.BLUE, Color.RED, true, true);
        String text = " \t x y  ";
        LineVisual line = line(
            text,
            List.of(new StyleSpan(0, text.length(), overlay)),
            0,
            0,
            0
        );
        RenderPlan plan = directPlan(line, 4, 14);

        CellBuffer cyan = rasterizer.rasterize(plan, 4, 14);
        CellStyle cyanMarker = cyan.get(1, 0).style();

        assertEquals("·", cyan.get(1, 0).grapheme());
        assertEquals("»", cyan.get(1, 1).grapheme());
        assertEquals(" ", cyan.get(1, 2).grapheme());
        assertEquals(" ", cyan.get(1, 3).grapheme());
        assertEquals("·", cyan.get(1, 4).grapheme());
        assertEquals(" ", cyan.get(1, 6).grapheme());
        assertEquals("·", cyan.get(1, 8).grapheme());
        assertEquals("·", cyan.get(1, 9).grapheme());
        assertEquals(EditorStyleAdapter.toCell(overlay).bg(), cyanMarker.bg());
        assertTrue(cyanMarker.bold());
        assertTrue(cyanMarker.underline());

        long hits = rasterizer.preparedLineCacheHits();
        rasterizer.setTheme(UiStyles.Theme.LIGHT);
        CellBuffer light = rasterizer.rasterize(plan, 4, 14);

        assertTrue(rasterizer.preparedLineCacheHits() > hits);
        assertEquals(
            UiStyles.forTheme(UiStyles.Theme.LIGHT).renderLeadingWhitespace().fg(),
            light.get(1, 0).style().fg()
        );
        assertEquals(EditorStyleAdapter.toCell(overlay).bg(), light.get(1, 0).style().bg());

        rasterizer.setVisibleWhitespace(false);
        CellBuffer hidden = rasterizer.rasterize(plan, 4, 14);
        assertEquals(" ", hidden.get(1, 0).grapheme());
        assertEquals(EditorStyleAdapter.toCell(overlay), hidden.get(1, 0).style());
    }

    @Test
    void tabPhaseTabWidthAndLeadingClipDoNotReuseStaleGeometry() {
        RenderRasterizer rasterizer = new RenderRasterizer();
        LineVisual phaseZero = line("\tX", List.of(), 0, 0, 0);
        CellBuffer first = rasterizer.rasterize(directPlan(phaseZero, 4, 10), 4, 10);

        LineVisual phaseTwo = line("\tX", List.of(), 0, 2, 0);
        CellBuffer second = rasterizer.rasterize(directPlan(phaseTwo, 4, 10), 4, 10);

        assertEquals("X", first.get(1, 4).grapheme());
        assertEquals("X", second.get(1, 2).grapheme());
        assertEquals(2, rasterizer.preparedLineCacheMisses());

        rasterizer.setTabWidth(2);
        assertEquals(0, rasterizer.preparedLineCacheSize());
        CellBuffer narrowTabs = rasterizer.rasterize(directPlan(phaseZero, 4, 10), 4, 10);
        assertEquals("X", narrowTabs.get(1, 2).grapheme());

        LineVisual clipped = line("AB", List.of(), 0, 0, 1);
        rasterizer.rasterize(directPlan(line("AB", List.of(), 0, 0, 0), 4, 10), 4, 10);
        long hits = rasterizer.preparedLineCacheHits();
        CellBuffer shifted = rasterizer.rasterize(directPlan(clipped, 4, 10), 4, 10);
        assertTrue(rasterizer.preparedLineCacheHits() > hits);
        assertEquals("B", shifted.get(1, 0).grapheme());
    }

    @Test
    void preparedLineCacheIsBoundedAndUsesAccessOrder() {
        RenderRasterizer rasterizer = new RenderRasterizer();
        for (int index = 0; index < 1024; index++) {
            rasterizer.rasterize(directPlan(line("line-" + index, List.of(), 0, 0, 0), 3, 16), 3, 16);
        }
        assertEquals(1024, rasterizer.preparedLineCacheSize());

        rasterizer.rasterize(directPlan(line("line-0", List.of(), 0, 0, 0), 3, 16), 3, 16);
        rasterizer.rasterize(directPlan(line("line-1024", List.of(), 0, 0, 0), 3, 16), 3, 16);
        long hits = rasterizer.preparedLineCacheHits();
        rasterizer.rasterize(directPlan(line("line-0", List.of(), 0, 0, 0), 3, 16), 3, 16);
        assertTrue(rasterizer.preparedLineCacheHits() > hits);

        long misses = rasterizer.preparedLineCacheMisses();
        rasterizer.rasterize(directPlan(line("line-1", List.of(), 0, 0, 0), 3, 16), 3, 16);
        assertTrue(rasterizer.preparedLineCacheMisses() > misses);
        assertEquals(1024, rasterizer.preparedLineCacheSize());
    }

    @Test
    void cachedRasterMatchesReferenceAcrossUnicodeTabsClippingAndOverlappingSpans() {
        Random random = new Random(0x5EED);
        String[] tokens = {"a", " ", "\t", "中", "😀", "e\u0301", "\u001B"};
        Style[] styles = {
            new Style(Color.RED, Color.DEFAULT, false, false),
            new Style(Color.BLUE, Color.YELLOW, true, false),
            new Style(Color.GREEN, Color.MAGENTA, false, true)
        };
        RenderRasterizer rasterizer = new RenderRasterizer();
        rasterizer.setVisibleWhitespace(false);

        for (int sample = 0; sample < 100; sample++) {
            StringBuilder text = new StringBuilder();
            int tokenCount = 4 + random.nextInt(20);
            for (int token = 0; token < tokenCount; token++) {
                text.append(tokens[random.nextInt(tokens.length)]);
            }
            List<StyleSpan> spans = new ArrayList<>();
            int spanCount = random.nextInt(8);
            for (int span = 0; span < spanCount; span++) {
                int start = random.nextInt(text.length() + 1);
                int end = start + random.nextInt(text.length() - start + 1);
                spans.add(new StyleSpan(start, end, styles[random.nextInt(styles.length)]));
            }
            int baseSpanCount = random.nextInt(spans.size() + 1);
            int sourceVisualColumn = random.nextInt(8);
            int leadingClippedCells = random.nextInt(4);
            LineVisual line = line(
                text.toString(),
                spans,
                baseSpanCount,
                sourceVisualColumn,
                leadingClippedCells
            );

            CellBuffer actual = rasterizer.rasterize(directPlan(line, 4, 24), 4, 24);
            CellBuffer expected = referenceLine(line, 24, 4);

            for (int col = 0; col < 24; col++) {
                assertEquals(
                    expected.get(1, col),
                    actual.get(1, col),
                    "sample=" + sample
                        + ", col=" + col
                        + ", text=" + text
                        + ", baseSpanCount=" + baseSpanCount
                        + ", sourceVisualColumn=" + sourceVisualColumn
                        + ", leadingClippedCells=" + leadingClippedCells
                        + ", spans=" + spans
                        + ", expectedRow=" + describeRow(expected, 1, 8)
                        + ", actualRow=" + describeRow(actual, 1, 8)
                );
            }
        }
    }

    private static CellBuffer referenceLine(LineVisual line, int cols, int tabWidth) {
        CellBuffer buffer = new CellBuffer(4, cols);
        int absoluteVisual = line.sourceVisualColumn();
        for (DisplayText.Grapheme grapheme : DisplayText.graphemes(line.text())) {
            Style style = Style.DEFAULT;
            for (StyleSpan span : line.spans()) {
                if (span.end() > grapheme.start() && span.start() < grapheme.end()) {
                    style = span.style();
                }
            }
            CellStyle cellStyle = EditorStyleAdapter.toCell(style);
            int advance = grapheme.tab()
                ? tabWidth - (absoluteVisual % tabWidth)
                : Math.max(1, grapheme.width());
            int screenCol = absoluteVisual
                - line.sourceVisualColumn()
                - line.leadingClippedCells();
            if (grapheme.tab()) {
                for (int cell = 0; cell < advance; cell++) {
                    int target = screenCol + cell;
                    if (target >= 0 && target < cols) {
                        buffer.set(1, target, " ", cellStyle);
                    }
                }
            } else if (screenCol >= 0 && screenCol + advance <= cols) {
                buffer.set(
                    1,
                    screenCol,
                    DisplayText.sanitizeGrapheme(grapheme.text()),
                    cellStyle
                );
            }
            absoluteVisual += advance;
        }
        return buffer;
    }

    private static String describeRow(CellBuffer buffer, int row, int cols) {
        StringBuilder out = new StringBuilder();
        for (int col = 0; col < cols; col++) {
            Cell cell = buffer.get(row, col);
            out.append(col)
                .append(':')
                .append(cell.grapheme())
                .append('/')
                .append(cell.style().fg())
                .append(' ');
        }
        return out.toString();
    }

    private static LineVisual line(
        String text,
        List<StyleSpan> spans,
        int baseSpanCount,
        int sourceVisualColumn,
        int leadingClippedCells
    ) {
        return new LineVisual(
            "",
            text,
            spans,
            GutterDiffMarker.NONE,
            sourceVisualColumn,
            leadingClippedCells,
            baseSpanCount
        );
    }

    private static RenderPlan directPlan(LineVisual line, int rows, int cols) {
        return new RenderPlan(
            List.of(line),
            "",
            "",
            null,
            null,
            null,
            null,
            0,
            0,
            true,
            false,
            false,
            1,
            0,
            1,
            0,
            0,
            cols,
            cols,
            0,
            0
        );
    }

    private static RenderPlan plan(String line, EditorState state, int rows, int cols) {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, line);
        TextModel model = new TextModel(buffer);
        RenderPlanBuilder builder = new RenderPlanBuilder(ignored -> List.of());
        builder.setSyntaxHighlightingEnabled(false);
        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        return builder.build(
            model,
            state,
            rows,
            cols,
            "top",
            "bottom",
            null,
            SearchOptions.DEFAULT,
            null,
            null,
            null,
            null,
            false,
            false
        );
    }
}
