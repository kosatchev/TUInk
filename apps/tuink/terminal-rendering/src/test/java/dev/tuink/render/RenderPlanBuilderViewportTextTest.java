package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorState;
import dev.tuink.core.LineText;
import dev.tuink.core.Position;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class RenderPlanBuilderViewportTextTest {
    @Test
    void ordinaryRowsReuseStringsAndLongRowsCopyOnlyVisibleTextWithoutSplittingStorage() {
        String shortRow = new String("ordinary row: я😀");
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(shortRow, "a".repeat(100_000)));
        model.insertChar(new Position(1, 50_000), 'x');
        RenderPlanBuilder builder = RenderPlanBuilder.standalone(ignored -> List.of(), new TerminalEditorTextLayout(model));
        builder.setSyntaxHighlightingEnabled(false);
        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        for (boolean wrap : new boolean[] {false, true}) {
            LineText.Work before = LineText.work();

            RenderPlan plan = builder.build(model, new EditorState(), 8, 80, "", "", null,
                SearchOptions.DEFAULT, null, null, null, null, wrap, false);

            LineText.Work after = LineText.work();
            assertSame(shortRow, plan.lines().getFirst().sequence(), "An unchanged complete short row already has a String");
            assertInstanceOf(String.class, plan.lines().get(1).sequence());
            assertEquals(before.copiedCodeUnits(), after.copiedCodeUnits(),
                "Preparing normal viewport rows must not split rope storage blocks");
            assertEquals(before.allocatedNodes(), after.allocatedNodes(),
                "Display-only ranges must not allocate editing-tree nodes");
            assertTrue(after.materializedCodeUnits() - before.materializedCodeUnits() < 4096,
                "Only the visible rows may become contiguous display strings");
        }
    }
}
