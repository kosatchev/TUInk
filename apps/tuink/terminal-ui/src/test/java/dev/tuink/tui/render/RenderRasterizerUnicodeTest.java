package dev.tuink.tui.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.DisplayText;
import dev.tuink.core.EditorState;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import java.util.List;
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
