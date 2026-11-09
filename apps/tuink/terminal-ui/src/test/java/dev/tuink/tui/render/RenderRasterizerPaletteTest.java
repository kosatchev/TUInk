package dev.tuink.tui.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.memory.RenderMemoryBudget;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.render.Color;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.style.UiStyles;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RenderRasterizerPaletteTest {
    private static final int ROWS = 6, COLS = 24;
    private static final Style SELECTION = new Style(Color.GRAY_028, Color.GRAY_218, true, false);
    private static final CellStyle SELECTED = EditorStyleAdapter.toCell(SELECTION);

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void plainGlyphsAndDefaultStyleChannelsInheritTheMonochromeBodyInPreparedAndStreamingPaths(boolean refusePreparation) {
        RenderPlan plan = plan();
        var budget = RenderMemoryBudget.bounded(refusePreparation ? 1 : 1 << 20);
        try (var rasterizer = new RenderRasterizer(1, UiStyles.Theme.MONOCHROME, budget)) {
            rasterizer.setVisibleWhitespace(false);
            CellBuffer frame = rasterizer.rasterize(plan, ROWS, COLS);
            assertDocumentStyles(frame, plan, UiStyles.forTheme(UiStyles.Theme.MONOCHROME).windows().renderBody());
            assertEquals(refusePreparation, rasterizer.preparedLineCacheSize() == 0);
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test
    void changingTheThemeRestylesTheSamePreparedPlanAndItsDefaultChannels() {
        RenderPlan plan = plan();
        try (var rasterizer = new RenderRasterizer(1, UiStyles.Theme.CYAN)) {
            rasterizer.setVisibleWhitespace(false);
            CellBuffer original = rasterizer.rasterize(plan, ROWS, COLS);
            assertDocumentStyles(original, plan, CellStyle.DEFAULT);
            rasterizer.rasterize(plan, ROWS, COLS);
            assertTrue(rasterizer.preparedRowsReused() > 0);
            long misses = rasterizer.preparedRowsMisses();

            rasterizer.setTheme(UiStyles.Theme.MONOCHROME);
            assertEquals(0, rasterizer.preparedLineCacheSize(), "body changes invalidate physical rows, even for the same plan");
            CellBuffer monochrome = rasterizer.rasterize(plan, ROWS, COLS);
            assertDocumentStyles(monochrome, plan, UiStyles.forTheme(UiStyles.Theme.MONOCHROME).windows().renderBody());
            assertTrue(rasterizer.preparedRowsMisses() > misses);
            long builds = rasterizer.metricsBuilds();
            rasterizer.setTheme(UiStyles.Theme.MONOCHROME);
            rasterizer.rasterize(plan, ROWS, COLS);
            assertEquals(builds, rasterizer.metricsBuilds(), "applying the same body does not rebuild prepared geometry");

            rasterizer.setTheme(UiStyles.Theme.CYAN);
            CellBuffer restored = rasterizer.rasterize(plan, ROWS, COLS);
            for (int row = 0; row < ROWS; row++) for (int column = 0; column < COLS; column++)
                assertEquals(original.get(row, column), restored.get(row, column), row + ":" + column);
        }
    }

    @Test
    void disablingColorsUsesTheDefaultBodyWithoutLosingExplicitSelectionAndRestoresGrayOnReturn() {
        RenderPlan plan = plan();
        try (var rasterizer = new RenderRasterizer(1, UiStyles.Theme.MONOCHROME)) {
            rasterizer.setVisibleWhitespace(false);
            rasterizer.rasterize(plan, ROWS, COLS);
            rasterizer.setColorEnabled(false);
            assertEquals(0, rasterizer.preparedLineCacheSize());
            CellBuffer monochrome = rasterizer.rasterize(plan, ROWS, COLS);
            assertDocumentStyles(monochrome, plan, CellStyle.DEFAULT);
            assertEquals(CellColor.DEFAULT, monochrome.get(2, COLS - 1).style().bg(), "the scrollbar shares the effective body");
            long builds = rasterizer.metricsBuilds();
            rasterizer.setColorEnabled(false);
            rasterizer.rasterize(plan, ROWS, COLS);
            assertEquals(builds, rasterizer.metricsBuilds());

            rasterizer.setColorEnabled(true);
            CellBuffer restored = rasterizer.rasterize(plan, ROWS, COLS);
            assertDocumentStyles(restored, plan, UiStyles.forTheme(UiStyles.Theme.MONOCHROME).windows().renderBody());
        }
    }

    @Test
    void theFixedGrayDocumentAndCodeSurfacesProvideContrastIndependentlyOfTerminalDefaults() {
        var palette = UiStyles.forTheme(UiStyles.Theme.MONOCHROME);
        CellStyle body = palette.windows().renderBody();
        assertEquals(CellColor.GRAY_188, body.fg());
        assertEquals(CellColor.GRAY_028, body.bg());
        assertEquals(body, EditorStyleAdapter.toCell(palette.preview().body()), "both document views share a surface");
        assertTrue(contrast(188, 28) >= 4.5);
        var code = EditorStyleAdapter.toCell(palette.preview().codeBody());
        assertEquals(CellColor.GRAY_218, code.fg());
        assertEquals(CellColor.DARK_GRAY, code.bg());
        assertNotEquals(body.bg(), code.bg(), "code retains a separate neutral background");
        assertTrue(contrast(218, 48) >= 4.5);
        assertTrue(contrast(158, 48) >= 4.5, "the dimmest code comments must remain readable");
    }

    @Test
    void inverseAttributeSurvivesPreparedAndStreamingRasterizationWithoutCacheCollisions() {
        var model = new TextModel(new SimpleLineBuffer()); model.replaceLines(List.of("ab"));
        var builder = RenderPlanBuilder.standalone(line -> List.of(
            new StyleSpan(0, 1, new Style(Color.DEFAULT, Color.DEFAULT, true, false)),
            new StyleSpan(1, 2, new Style(Color.DEFAULT, Color.DEFAULT, true, false, true))), new TerminalEditorTextLayout(model));
        builder.setBracketMatchingEnabled(false); builder.setRainbowBracketsEnabled(false);
        var state = new EditorState();
        var plan = builder.build(model, state, ROWS, COLS, "", "");
        for (int bytes : new int[] {1, 1 << 20}) try (var rasterizer = new RenderRasterizer(1, UiStyles.Theme.CYAN,
                RenderMemoryBudget.bounded(bytes))) {
            rasterizer.setVisibleWhitespace(false);
            var cells = rasterizer.rasterize(plan, ROWS, COLS);
            assertFalse(cells.get(1, plan.gutterWidth()).style().inverse());
            assertTrue(cells.get(1, plan.gutterWidth() + 1).style().inverse());
        }
    }

    private static void assertDocumentStyles(CellBuffer frame, RenderPlan plan, CellStyle body) {
        int text = plan.gutterWidth();
        assertEquals(body, frame.get(1, text).style(), "an unstyled glyph inherits the body");
        assertEquals(body, frame.get(1, text + 1).style(), "an explicit DEFAULT span inherits the body");
        assertEquals(new CellStyle(body.fg(), body.bg(), true, false), frame.get(1, text + 2).style());
        assertEquals(new CellStyle(body.fg(), body.bg(), false, true), frame.get(1, text + 3).style());
        assertEquals(new CellStyle(CellColor.GRAY_238, body.bg(), false, false), frame.get(1, text + 4).style());
        assertEquals(new CellStyle(body.fg(), CellColor.DARK_GRAY, false, false), frame.get(1, text + 5).style());
        assertEquals(SELECTED, frame.get(1, text + 6).style(), "explicit transient selection keeps both channels");
        assertEquals(body, frame.get(1, text + 7).style(), "selection does not leak into the following glyph");
        assertEquals(body, frame.get(1, text + 11).style(), "unoccupied document cells keep the surface");
        assertEquals(body, frame.get(2, text).style());
    }

    private static RenderPlan plan() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("abcdefghi"));
        var builder = RenderPlanBuilder.standalone(line -> List.of(
            new StyleSpan(1, 2, Style.DEFAULT),
            new StyleSpan(2, 3, new Style(Color.DEFAULT, Color.DEFAULT, true, false)),
            new StyleSpan(3, 4, new Style(Color.DEFAULT, Color.DEFAULT, false, true)),
            new StyleSpan(4, 5, Style.fg(Color.GRAY_238)),
            new StyleSpan(5, 6, new Style(Color.DEFAULT, Color.DARK_GRAY, false, false))
        ), new TerminalEditorTextLayout(model));
        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        builder.setDecorationStyles(SELECTION, Style.DEFAULT, Style.DEFAULT, Style.DEFAULT,
            Style.DEFAULT, Style.DEFAULT, List.of(Style.DEFAULT));
        var state = new EditorState();
        state.setSelectionAnchor(new Position(0, 6));
        state.setCursor(new Position(0, 7));
        return builder.build(model, state, ROWS, COLS, "", "");
    }

    private static double contrast(int first, int second) {
        double high = Math.max(luminance(first), luminance(second));
        double low = Math.min(luminance(first), luminance(second));
        return (high + 0.05) / (low + 0.05);
    }

    private static double luminance(int gray) {
        double value = gray / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
