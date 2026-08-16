package dev.tuink.render;

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
    void regularAndCurrentSearchHighlightsUseOriginalUtf16Ranges() {
        TextModel model = model("İx");
        EditorState state = new EditorState();
        RenderPlanBuilder builder = new RenderPlanBuilder(
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
}
