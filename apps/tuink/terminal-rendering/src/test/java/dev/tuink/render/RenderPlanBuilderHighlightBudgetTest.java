package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RenderPlanBuilderHighlightBudgetTest {
    private static final Style COLOR = Style.fg(Color.GREEN);

    @Test
    void legacyHighlightersAreNeverInvokedForAnOversizedLineInEitherLayout() {
        TextModel model = model("1,".repeat(1_500_000));
        RenderPlanBuilder builder = builder(model, line -> {
            throw new AssertionError("A legacy full-line API must not receive this line");
        });
        EditorState state = new EditorState();

        assertTrue(build(builder, model, state, 8, 80, false).lines().getFirst().spans().isEmpty());
        state.setLeftVisualColumn(100_000);
        assertTrue(build(builder, model, state, 8, 80, false).lines().getFirst().spans().isEmpty());
        state.setTopWrapSegment(1000);
        assertTrue(build(builder, model, state, 8, 80, true).lines().getFirst().spans().isEmpty());
    }

    @Test
    void boundedRequestsCoverVisibleWindowsAndHorizontalScrollDoesNotReuseAnotherRange() {
        TextModel model = model("1,".repeat(100_000));
        RecordingHighlighter highlighter = new RecordingHighlighter();
        RenderPlanBuilder builder = builder(model, highlighter);
        EditorState state = new EditorState();

        RenderPlan first = build(builder, model, state, 8, 80, false);
        HighlightRequest firstRequest = highlighter.requests.getFirst();
        assertEquals(0, firstRequest.startUtf16());
        assertEquals(first.lines().getFirst().text().length(), firstRequest.endUtf16());
        assertFalse(first.lines().getFirst().spans().isEmpty());
        build(builder, model, state, 8, 80, false);
        assertEquals(1, highlighter.requests.size());

        state.setLeftVisualColumn(200);
        RenderPlan scrolled = build(builder, model, state, 8, 80, false);
        assertEquals(200, highlighter.requests.getLast().startUtf16());
        assertEquals(first.lines().getFirst().spans(), scrolled.lines().getFirst().spans());
        state.setLeftVisualColumn(0);
        build(builder, model, state, 8, 80, false);
        assertEquals(2, highlighter.requests.size(), "returning to a retained range should reuse its styles");

        state.setTopWrapSegment(3);
        RenderPlan wrapped = build(builder, model, state, 8, 80, true);
        HighlightRequest wrappedRequest = highlighter.requests.getLast();
        assertTrue(wrappedRequest.startUtf16() > 0);
        assertTrue(wrappedRequest.endUtf16() - wrappedRequest.startUtf16() <= wrapped.contentRows() * 80);
        assertFalse(wrapped.lines().getFirst().spans().isEmpty());
    }

    @Test
    void cacheWeightsBoundDenseStylesAcrossManyViewportRangesAndResetAfterEdits() {
        TextModel model = model("a".repeat(100_000));
        RecordingHighlighter highlighter = new RecordingHighlighter();
        RenderPlanBuilder builder = builder(model, highlighter);
        EditorState state = new EditorState();

        for (int window = 0; window < 100; window++) {
            state.setLeftVisualColumn(window * 100);
            build(builder, model, state, 4, 2048, false);
            assertTrue(builder.syntaxCacheWeight() <= RenderPlanBuilder.MAX_SYNTAX_CACHE_WEIGHT);
        }
        assertTrue(builder.syntaxCacheSize() < 100, "a line-count limit alone would retain all dense ranges");
        state.setLeftVisualColumn(0);
        int calls = highlighter.requests.size();
        build(builder, model, state, 4, 2048, false);
        assertEquals(calls + 1, highlighter.requests.size(), "the oldest range should have been evicted");

        model.insertChar(new Position(0, 0), 'b');
        build(builder, model, state, 4, 2048, false);
        assertEquals(1, builder.syntaxCacheSize());
        assertTrue(builder.syntaxCacheWeight() > 0);
    }

    private static final class RecordingHighlighter implements LineHighlighter {
        private final List<HighlightRequest> requests = new ArrayList<>();

        @Override public List<StyleSpan> highlight(String line) {
            throw new AssertionError("Render must use the bounded API");
        }

        @Override public List<StyleSpan> highlight(String line, HighlightRequest request) {
            requests.add(request);
            List<StyleSpan> spans = new ArrayList<>();
            for (int offset = request.startUtf16(); offset < Math.min(request.endUtf16(), request.scanEnd(line)); offset++) {
                spans.add(new StyleSpan(offset, offset + 1, COLOR));
            }
            return request.clipSpans(spans);
        }
    }

    private static TextModel model(String line) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(line));
        return model;
    }

    private static RenderPlanBuilder builder(TextModel model, LineHighlighter highlighter) {
        RenderPlanBuilder builder = new RenderPlanBuilder(highlighter, new TerminalEditorTextLayout(model));
        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        return builder;
    }

    private static RenderPlan build(RenderPlanBuilder builder, TextModel model, EditorState state,
                                   int rows, int cols, boolean wrap) {
        return builder.build(model, state, rows, cols, "top", "bottom", null, SearchOptions.DEFAULT,
            null, null, null, null, wrap, false);
    }
}
