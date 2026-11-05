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
import dev.tuink.render.Color;
import dev.tuink.render.Style;
import dev.tuink.render.markdown.MarkdownGeometry;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuink.tui.style.UiStyles;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarkdownPreviewPalettePainterTest {
    @Test void independentPreviewAndSyntaxSnapshotsInvalidateStylesAndRoundTripWithoutRebuildingGeometry() {
        var result = fixture();
        var original = palette(Color.MAGENTA, Color.YELLOW, Color.GREEN);
        var previewOnly = new UiStyles.Palette(original.windows(),
            preview(Color.CYAN, Color.RED), original.syntax());
        var syntaxOnly = new UiStyles.Palette(original.windows(), previewOnly.preview(), syntax(Color.BRIGHT_BLUE));
        var budget = RenderMemoryBudget.bounded(1_000_000);
        try (var geometry = new MarkdownGeometry(result, budget)) {
            for (var current : List.of(original, previewOnly, syntaxOnly, original)) {
                for (boolean prepared : new boolean[] {false, true}) {
                    var cells = paint(result, prepared ? geometry : null, current, true);
                    boolean originalPreview = current == original;
                    assertEquals(new CellStyle(originalPreview ? CellColor.MAGENTA : CellColor.CYAN,
                        CellColor.BLUE, true, false), cells.get(1, 2).style());
                    assertEquals(originalPreview ? CellColor.YELLOW : CellColor.RED, cells.get(2, 8).style().fg());
                    assertEquals(new CellStyle(current == syntaxOnly ? CellColor.BRIGHT_BLUE : CellColor.GREEN,
                        CellColor.DARK_GRAY, true, false), cells.get(3, 2).style());
                    assertEquals(new CellStyle(CellColor.BLACK, CellColor.MAGENTA, false, true),
                        cells.get(3, 5).style(), "An explicit syntax background overrides the inherited code background");
                    assertEquals(new CellStyle(CellColor.BLACK, CellColor.DARK_GRAY, false, false),
                        cells.get(3, 7).style(), "DEFAULT token channels inherit the code body");
                    assertEquals(new CellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE, false, false),
                        cells.get(2, 2).style(), "Preview body is independent of window chrome");
                    assertEquals(CellColor.DARK_GRAY, cells.get(3, 21).style().bg());
                }
            }
            assertEquals(result.lines().size() * 3, geometry.rows().misses(),
                "Each palette snapshot gets its own rows even when body and code body are unchanged");
            assertEquals(result.lines().size(), geometry.rows().hits(), "The original palette is reused on return");
            assertEquals(result.lines().size(), geometry.misses(), "Palette changes reuse the accepted geometry");
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void noColorSuppressesPaletteChannelsAndKeepsRoleAndTokenTypography() {
        var palette = palette(Color.MAGENTA, Color.YELLOW, Color.GREEN);
        var result = fixture();
        var budget = RenderMemoryBudget.bounded(1_000_000);
        try (var geometry = new MarkdownGeometry(result, budget)) {
            for (boolean prepared : new boolean[] {false, true}) {
                var cells = paint(result, prepared ? geometry : null, palette, false);
                for (int row = 1; row <= 3; row++) for (int column = 2; column < 22; column++) {
                    assertEquals(CellColor.DEFAULT, cells.get(row, column).style().fg());
                    assertEquals(CellColor.DEFAULT, cells.get(row, column).style().bg());
                }
                assertTrue(cells.get(1, 2).style().bold());
                assertTrue(cells.get(3, 2).style().bold());
                assertTrue(cells.get(3, 5).style().underline());
                assertFalse(cells.get(2, 2).style().bold());
            }
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void monochromeThemePaintsGrayRolesBoldKeywordsAndTheFullCodeBackground() {
        var result = fixture();
        var palette = UiStyles.forTheme(UiStyles.Theme.MONOCHROME);
        var budget = RenderMemoryBudget.bounded(1_000_000);
        try (var geometry = new MarkdownGeometry(result, budget)) {
            for (boolean prepared : new boolean[] {false, true}) {
                var cells = paint(result, prepared ? geometry : null, palette, true);
                assertEquals(new CellStyle(CellColor.GRAY_238, CellColor.GRAY_028, true, false), cells.get(1, 2).style());
                assertEquals(CellColor.GRAY_188, cells.get(2, 2).style().fg());
                assertEquals(CellColor.GRAY_218, cells.get(2, 8).style().fg());
                assertEquals(new CellStyle(CellColor.GRAY_218, CellColor.DARK_GRAY, true, false), cells.get(3, 2).style());
                assertEquals(CellColor.GRAY_158, cells.get(3, 7).style().fg());
                for (int column = 2; column < 22; column++)
                    assertEquals(CellColor.DARK_GRAY, cells.get(3, column).style().bg());
            }
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void aMatchingCodeBackgroundDoesNotReplaceTheParagraphPaddingForeground() {
        var palette = palette(Color.MAGENTA, Color.YELLOW, Color.GREEN);
        var preview = new UiStyles.PreviewStyles(palette.preview().body(),
            new Style(Color.BLACK, Color.BLUE, false, false), palette.preview().roles());
        var sameBackground = new UiStyles.Palette(palette.windows(), preview, palette.syntax());
        var cells = paint(fixture(), null, sameBackground, true);
        assertEquals(CellColor.BLACK, cells.get(3, 7).style().fg());
        assertEquals(new CellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE, false, false), cells.get(3, 21).style());
    }

    private static UiStyles.Palette palette(Color heading, Color inlineCode, Color keyword) {
        return new UiStyles.Palette(UiStyles.forTheme(UiStyles.Theme.CYAN).windows(),
            preview(heading, inlineCode), syntax(keyword));
    }

    private static UiStyles.PreviewStyles preview(Color heading, Color inlineCode) {
        return new UiStyles.PreviewStyles(new Style(Color.BRIGHT_WHITE, Color.BLUE, false, false),
            new Style(Color.BLACK, Color.DARK_GRAY, false, false), Map.of(
                MarkdownLayout.Role.HEADING, new Style(heading, Color.DEFAULT, true, false),
                MarkdownLayout.Role.CODE, Style.fg(inlineCode)));
    }

    private static UiStyles.SyntaxStyles syntax(Color keyword) {
        var base = UiStyles.forTheme(UiStyles.Theme.CYAN).syntax();
        return new UiStyles.SyntaxStyles(Map.of(
            SemanticStyleKey.KEYWORD, new Style(keyword, Color.DEFAULT, true, false),
            SemanticStyleKey.NUMBER, new Style(Color.DEFAULT, Color.MAGENTA, false, true),
            SemanticStyleKey.COMMENT, Style.DEFAULT), Style.DEFAULT,
            base.bracketMatch(), base.quoteMatch(), base.unmatchedBracket(), base.bracketDepths());
    }

    private static MarkdownLayout.Result fixture() {
        return result("Title\nplain inline\nif 7 x",
            new MarkdownLayout.Line("Title", List.of(new MarkdownLayout.Run(0, 5,
                MarkdownLayout.Role.HEADING, SOURCE)), "Title", SOURCE, "heading"),
            new MarkdownLayout.Line("plain inline", List.of(
                new MarkdownLayout.Run(0, 6, MarkdownLayout.Role.TEXT, SOURCE),
                new MarkdownLayout.Run(6, 12, MarkdownLayout.Role.CODE, SOURCE)), "plain inline", SOURCE, "paragraph"),
            new MarkdownLayout.Line("if 7 x", List.of(new MarkdownLayout.Run(0, 6,
                MarkdownLayout.Role.CODE_BLOCK, SOURCE)), "if 7 x", SOURCE, "code", List.of(
                    new TokenSpan(0, 2, SemanticStyleKey.KEYWORD), new TokenSpan(3, 4, SemanticStyleKey.NUMBER),
                    new TokenSpan(5, 6, SemanticStyleKey.COMMENT))));
    }

    private static CellBuffer paint(MarkdownLayout.Result result, MarkdownGeometry geometry,
        UiStyles.Palette palette, boolean color) {
        var cells = new CellBuffer(7, 25);
        var placement = new MarkdownPreviewState.Placement(new Rect(2, 1, 20, 5), result,
            0, 0, false, "", PreviewSelection.Snapshot.EMPTY, List.of(), -1, "", false, null, null, geometry,
            MarkdownPreviewState.Placement.Focus.CONTENT, MarkdownPreviewState.SearchFailure.NONE);
        MarkdownPreviewPainter.paint(new Canvas(cells), placement, palette, color);
        return cells;
    }
}
