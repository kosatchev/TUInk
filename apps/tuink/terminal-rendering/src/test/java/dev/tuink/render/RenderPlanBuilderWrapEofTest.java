package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class RenderPlanBuilderWrapEofTest {
    @Test
    void fullAsciiWideGlyphAndTabRowsKeepTheEofInsertionPointVisible() {
        for (String text : List.of("abcd", "界界", "\t")) {
            TextModel model = model(text);
            EditorState state = eofState(text);
            RenderPlan plan = build(builder(model), model, state, 10, 7);

            assertTrue(plan.cursorInViewport(), text);
            assertEquals(1, plan.cursorRow(), text);
            assertEquals(plan.gutterWidth(), plan.cursorCol(), text);
            assertEquals("", plan.lines().get(1).text(), text);
            assertEquals(" ".repeat(plan.gutterWidth()), plan.lines().get(1).gutter(), text);
        }
    }

    @Test
    void resizingRemovesAndRestoresTheEofRowWithoutHidingTheCursor() {
        TextModel model = model("abcd");
        EditorState state = eofState("abcd");
        RenderPlanBuilder builder = builder(model);

        RenderPlan narrow = build(builder, model, state, 10, 7);
        RenderPlan wider = build(builder, model, state, 10, 8);
        RenderPlan narrowAgain = build(builder, model, state, 10, 7);

        assertTrue(narrow.cursorInViewport());
        assertEquals(1, narrow.cursorRow());
        assertTrue(wider.cursorInViewport());
        assertEquals(0, wider.cursorRow());
        assertEquals(wider.gutterWidth() + 4, wider.cursorCol());
        assertTrue(narrowAgain.cursorInViewport());
        assertEquals(1, narrowAgain.cursorRow());
    }

    @Test
    void scrolledViewportIncludesTheEofRowInItsWrappedOffset() {
        TextModel model = model("abcdefghijkl");
        EditorState state = eofState("abcdefghijkl");
        state.setTopWrapSegment(2);

        RenderPlan plan = build(builder(model), model, state, 4, 8);

        assertEquals(4, plan.scrollLayout().contentBounds().width());
        assertEquals(2, plan.topLine());
        assertEquals(4, plan.totalLines());
        assertEquals("ijkl", plan.lines().getFirst().text());
        assertEquals("", plan.lines().get(1).text());
        assertTrue(plan.cursorInViewport());
        assertEquals(1, plan.cursorRow());
        assertEquals(plan.gutterWidth(), plan.cursorCol());
    }

    private static TextModel model(String text) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(text));
        return model;
    }

    private static EditorState eofState(String text) {
        EditorState state = new EditorState();
        state.setCursor(new Position(0, text.length()));
        return state;
    }

    private static RenderPlanBuilder builder(TextModel model) {
        RenderPlanBuilder builder = RenderPlanBuilder.standalone(line -> List.of(), new TerminalEditorTextLayout(model));
        builder.setSyntaxHighlightingEnabled(false);
        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        return builder;
    }

    private static RenderPlan build(
        RenderPlanBuilder builder, TextModel model, EditorState state, int rows, int cols
    ) {
        return builder.build(
            model, state, rows, cols, "", "", null, SearchOptions.DEFAULT,
            null, null, null, null, true, false
        );
    }
}
