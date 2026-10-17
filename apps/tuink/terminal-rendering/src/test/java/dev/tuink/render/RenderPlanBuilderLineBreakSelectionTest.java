package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class RenderPlanBuilderLineBreakSelectionTest {
    private static final Style SELECTION = new Style(Color.BLACK, Color.CYAN, false, false);

    @Test
    void forwardAndBackwardSelectionsIncludeRealLineBreaksButExcludeTheEndLine() {
        TextModel model = model("abc", "", "xyz");
        for (boolean reverse : List.of(false, true)) {
            EditorState state = selection(new Position(0, 3), new Position(2, 0), reverse);

            RenderPlan plan = build(model, state, 20, false, false);

            assertEquals(SELECTION, plan.lines().get(0).lineBreakSelectionStyle());
            assertEquals(SELECTION, plan.lines().get(1).lineBreakSelectionStyle());
            assertNull(plan.lines().get(2).lineBreakSelectionStyle());
            assertEquals(List.of("abc", "", "xyz"),
                plan.lines().subList(0, 3).stream().map(LineVisual::text).toList());
        }
    }

    @Test
    void singleLineSelectionAndTheFinalEofDoNotHaveLineBreakMarkers() {
        TextModel model = model("", "abc");
        RenderPlan sameLine = build(model,
            selection(new Position(1, 0), new Position(1, 3), false), 20, false, false);
        RenderPlan all = build(model,
            selection(new Position(0, 0), new Position(1, 3), false), 20, false, false);
        RenderPlan collapsed = build(model, new EditorState(), 20, false, false);

        assertNull(sameLine.lines().get(1).lineBreakSelectionStyle());
        assertEquals(SELECTION, all.lines().get(0).lineBreakSelectionStyle());
        assertNull(all.lines().get(1).lineBreakSelectionStyle());
        assertNull(collapsed.lines().get(0).lineBreakSelectionStyle());
    }

    @Test
    void clippingUsesPhysicalColumnsAndDoesNotMarkOffscreenLineBreaks() {
        TextModel model = model("中\tX", "", "tail"); // EOL is at physical column 5.
        EditorState state = selection(new Position(0, 3), new Position(2, 0), false);
        state.setLeftVisualColumn(2);

        RenderPlan visible = build(model, state, 8, false, false);
        assertEquals(SELECTION, visible.lines().get(0).lineBreakSelectionStyle());
        assertNull(visible.lines().get(1).lineBreakSelectionStyle(), "empty EOL is left of the viewport");

        state.setLeftVisualColumn(0);
        RenderPlan clippedRight = build(model, state, 8, false, false);
        assertNull(clippedRight.lines().get(0).lineBreakSelectionStyle(), "EOL is beyond the right edge");

        state.setLeftVisualColumn(6);
        RenderPlan clippedLeft = build(model, state, 8, false, false);
        assertNull(clippedLeft.lines().get(0).lineBreakSelectionStyle());
    }

    @Test
    void fullWrappedRowsPutTheSelectedLineBreakOnTheExistingEmptyInsertionRow() {
        for (String text : List.of("abcd", "界界", "\t")) {
            TextModel model = model(text, "", "x");
            EditorState state = selection(new Position(0, text.length()), new Position(2, 0), false);

            RenderPlan plan = build(model, state, 7, true, false);

            assertEquals(text, plan.lines().get(0).text());
            assertNull(plan.lines().get(0).lineBreakSelectionStyle());
            assertEquals("", plan.lines().get(1).text());
            assertEquals(SELECTION, plan.lines().get(1).lineBreakSelectionStyle());
            assertEquals(SELECTION, plan.lines().get(2).lineBreakSelectionStyle());
            assertNull(plan.lines().get(3).lineBreakSelectionStyle());
        }
    }

    @Test
    void wordWrappingAndVerticalScrollingKeepOneMarkerOnTheLastSegment() {
        TextModel model = model("one two three", "tail");
        EditorState state = selection(new Position(0, 0), new Position(1, 0), false);
        state.setTopWrapSegment(1);

        RenderPlan plan = build(model, state, 10, true, true);
        List<LineVisual> marked = plan.lines().stream()
            .filter(line -> line.lineBreakSelectionStyle() != null).toList();

        assertEquals(1, marked.size());
        assertEquals("three", marked.getFirst().text());
    }

    private static EditorState selection(Position start, Position end, boolean reverse) {
        EditorState state = new EditorState();
        state.setSelectionAnchor(reverse ? end : start);
        state.setCursor(reverse ? start : end);
        return state;
    }

    private static TextModel model(String... lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(lines));
        return model;
    }

    private static RenderPlan build(TextModel model, EditorState state, int cols, boolean wrap, boolean words) {
        RenderPlanBuilder builder = new RenderPlanBuilder(ignored -> List.of(), new TerminalEditorTextLayout(model));
        builder.setSyntaxHighlightingEnabled(false);
        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        builder.setDecorationStyles(SELECTION, null, null, null, null, null, null);
        return builder.build(model, state, 10, cols, "", "", null, SearchOptions.DEFAULT,
            null, null, null, null, wrap, words);
    }
}
