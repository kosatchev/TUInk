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

class RenderPlanBuilderSearchUnicodeTest {
    @Test
    void horizontalSearchClippingKeepsMatchesThatStartBeforeTheViewport() {
        TextModel model = model("a".repeat(100));
        EditorState state = new EditorState();
        state.setLeftVisualColumn(5);
        RenderPlanBuilder builder = searchBuilder(model);

        RenderPlan plan = builder.build(model, state, 4, 20, "top", "bottom", "aa", SearchOptions.DEFAULT);

        assertTrue(plan.lines().getFirst().spans().contains(new StyleSpan(0, 1, Style.fg(Color.RED))));
    }

    @Test
    void denseSearchHighlightsCoverOnlyVisibleRowsWithAndWithoutWrapping() {
        TextModel model = model("a".repeat(2_000_000));
        RenderPlanBuilder builder = searchBuilder(model);
        for (boolean wrapped : List.of(false, true)) {
            EditorState state = new EditorState();
            if (wrapped) {
                state.setTopWrapSegment(100);
            } else {
                state.setLeftVisualColumn(100);
            }
            RenderPlan plan = builder.build(
                model, state, 8, 20, "top", "bottom", "a", SearchOptions.DEFAULT,
                null, null, null, null, wrapped, false
            );

            for (LineVisual row : plan.lines()) {
                assertEquals(row.text().length(), row.spans().size());
                for (int index = 0; index < row.text().length(); index++) {
                    assertEquals(new StyleSpan(index, index + 1, Style.fg(Color.RED)), row.spans().get(index));
                }
            }
        }
    }

    @Test
    void regularAndCurrentSearchHighlightsUseOriginalUtf16Ranges() {
        TextModel model = model("İx");
        EditorState state = new EditorState();
        RenderPlanBuilder builder = RenderPlanBuilder.standalone(
            line -> List.of(),
            new TerminalEditorTextLayout(model)
        );
        builder.setSyntaxHighlightingEnabled(false);
        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        Style searchStyle = Style.fg(Color.RED);
        Style currentStyle = Style.fg(Color.BLUE);
        builder.setDecorationStyles(
            Style.DEFAULT,
            searchStyle,
            currentStyle,
            Style.DEFAULT,
            Style.DEFAULT,
            Style.DEFAULT,
            List.of(Style.DEFAULT)
        );
        SearchOptions options = new SearchOptions(true, SearchOptions.MatchMode.SUBSTRING);

        RenderPlan regular = builder.build(model, state, 4, 20, "top", "bottom", "x", options);
        assertTrue(regular.lines().get(0).spans().contains(new StyleSpan(1, 2, searchStyle)));

        state.setSelectionAnchor(new Position(0, 1));
        state.setCursor(new Position(0, 2));
        RenderPlan current = builder.build(model, state, 4, 20, "top", "bottom", "x", options);
        assertTrue(current.lines().get(0).spans().contains(new StyleSpan(1, 2, currentStyle)));
    }

    private static TextModel model(String text) {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, text);
        return new TextModel(buffer);
    }

    private static RenderPlanBuilder searchBuilder(TextModel model) {
        RenderPlanBuilder builder = RenderPlanBuilder.standalone(line -> List.of(), new TerminalEditorTextLayout(model));
        builder.setSyntaxHighlightingEnabled(false);
        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        builder.setDecorationStyles(
            Style.DEFAULT, Style.fg(Color.RED), Style.fg(Color.BLUE),
            Style.DEFAULT, Style.DEFAULT, Style.DEFAULT, List.of(Style.DEFAULT)
        );
        return builder;
    }
}
