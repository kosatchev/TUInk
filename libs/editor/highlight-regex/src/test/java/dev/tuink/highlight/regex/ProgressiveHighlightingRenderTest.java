package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorState;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.DocumentView;
import dev.tuink.render.Color;
import dev.tuink.render.ContextualLineHighlighter;
import dev.tuink.render.HighlightRequest;
import dev.tuink.render.LineHighlighter;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import dev.tuink.render.TerminalEditorTextLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProgressiveHighlightingRenderTest {
    @Test
    void coldJumpToTenThousandthLineFinishesAcrossUnchangedFrames() {
        String line = "public class Example { int value = 123; }";
        TextModel model = model(Collections.nCopies(10_000, line));
        RenderPlanBuilder builder = builder(model, RegexHighlighter.forJava());
        EditorState state = new EditorState();
        state.setTopLine(9990);
        long revision = model.revision();

        RenderPlan plan = build(builder, model, state);
        assertTrue(builder.hasPendingSyntaxWork());
        assertTrue(plan.lines().getFirst().spans().isEmpty());
        int retries = 0;
        while (builder.hasPendingSyntaxWork() && retries++ < 20) {
            plan = build(builder, model, state);
        }

        assertFalse(builder.hasPendingSyntaxWork(), "retained context must finish without an edit or scroll");
        assertEquals(revision, model.revision());
        List<StyleSpan> expected = RegexHighlighter.forJava().highlight(line);
        assertFalse(expected.isEmpty());
        for (var visual : plan.lines()) assertEquals(expected, visual.spans());
        assertEquals(plan, build(builder, model, state), "completed spans remain cacheable");
    }

    @Test
    void resumedContextPreservesACommentOpenedBeforeTheScanWindow() {
        List<String> lines = new ArrayList<>(Collections.nCopies(10_000, "documentation ".repeat(3)));
        lines.set(0, "/*");
        lines.set(9990, "return 7;");
        TextModel model = model(lines);
        RenderPlanBuilder builder = builder(model, RegexHighlighter.forJava());
        EditorState state = new EditorState();
        state.setTopLine(9990);
        RenderPlan plan = build(builder, model, state);
        assertTrue(plan.lines().getFirst().spans().isEmpty(), "unknown context must not guess Java tokens");
        for (int attempt = 0; builder.hasPendingSyntaxWork() && attempt < 20; attempt++) {
            plan = build(builder, model, state);
        }
        assertFalse(builder.hasPendingSyntaxWork());
        assertEquals(List.of(new StyleSpan(0, 9, Style.fg(Color.BRIGHT_BLACK))),
            plan.lines().getFirst().spans());
    }

    @Test
    void markdownAndItsEmbeddedLexerBothResumeBeforePublishingFinishedStyles() {
        String source = "template<class T> class Example { public: T value; };";
        List<String> lines = new ArrayList<>(Collections.nCopies(10_000, source));
        lines.set(0, "```cpp");
        TextModel model = model(lines);
        RenderPlanBuilder builder = builder(model, RegexHighlighter.forFileName("notes.md"));
        EditorState state = new EditorState();
        state.setTopLine(9990);
        RenderPlan plan = build(builder, model, state);
        assertTrue(builder.hasPendingSyntaxWork());
        for (int attempt = 0; builder.hasPendingSyntaxWork() && attempt < 30; attempt++) {
            plan = build(builder, model, state);
        }
        assertFalse(builder.hasPendingSyntaxWork());
        List<StyleSpan> expected = RegexHighlighter.forCpp().highlight(source);
        for (var visual : plan.lines()) assertEquals(expected, visual.spans());
    }

    @Test
    void workBudgetExhaustionRetainsOnlyProvenContextAndCanResumeIt() {
        List<String> lines = new ArrayList<>(Collections.nCopies(500, "documentation"));
        lines.set(0, "/*");
        lines.set(499, "return 7;");
        DocumentSnapshot document = new DocumentSnapshot(new DocumentId("work-budget"), 1, lines);
        ContextualLineHighlighter highlighter = (ContextualLineHighlighter) RegexHighlighter.forJava();
        HighlightRequest request = new HighlightRequest(0, 9, 65_536, 16, 1024);
        var result = highlighter.highlightResult(document, 499, document.line(499), request);
        assertTrue(result.retryPending());
        assertTrue(result.spans().isEmpty());
        for (int attempt = 0; result.retryPending() && attempt < 40; attempt++) {
            result = highlighter.highlightResult(document, 499, document.line(499), request);
        }
        assertFalse(result.retryPending());
        assertEquals(List.of(new StyleSpan(0, 9, Style.fg(Color.BRIGHT_BLACK))), result.spans());
    }

    @Test
    void jumpingAboveAPopulatedCheckpointCacheCanStillRetainProgress() {
        DocumentSnapshot document = new DocumentSnapshot(new DocumentId("backward-jump"), 1,
            Collections.nCopies(500, "return 7;"));
        ContextualLineHighlighter highlighter = (ContextualLineHighlighter) RegexHighlighter.forJava();
        highlighter.highlight(document, 499, document.line(499));
        HighlightRequest request = new HighlightRequest(0, 9, 64, 16, 8192);
        var result = highlighter.highlightResult(document, 100, document.line(100), request);
        assertTrue(result.retryPending());
        for (int attempt = 0; result.retryPending() && attempt < 30; attempt++) {
            result = highlighter.highlightResult(document, 100, document.line(100), request);
        }
        assertFalse(result.retryPending());
        assertEquals(RegexHighlighter.forJava().highlight(document.line(100)), result.spans());
    }

    @Test
    void contextThatUsesTheLastScanUnitCanRetryTheVisibleLineWithAFreshBudget() {
        DocumentSnapshot document = new DocumentSnapshot(new DocumentId("context-budget"), 1,
            List.of(" ".repeat(15), " ".repeat(15), " ".repeat(15), "return 7;"));
        ContextualLineHighlighter highlighter = (ContextualLineHighlighter) RegexHighlighter.forJava();
        HighlightRequest request = new HighlightRequest(0, 9, 48, 16, 8192);
        var first = highlighter.highlightResult(document, 3, document.line(3), request);
        assertTrue(first.retryPending());
        assertTrue(first.spans().isEmpty());
        var second = highlighter.highlightResult(document, 3, document.line(3), request);
        assertFalse(second.retryPending());
        assertEquals(RegexHighlighter.forJava().highlight(document.line(3)), second.spans());
    }

    @Test
    void completedPlainTextAndAnUnscannablePredecessorDoNotScheduleEndlessRetries() {
        AtomicInteger calls = new AtomicInteger();
        ContextualLineHighlighter plain = new ContextualLineHighlighter() {
            @Override public List<StyleSpan> highlight(String line) { return List.of(); }
            @Override public List<StyleSpan> highlight(DocumentView document, int line, String text) {
                calls.incrementAndGet();
                return List.of();
            }
        };
        TextModel model = model(List.of("ordinary text"));
        RenderPlanBuilder builder = builder(model, plain);
        build(builder, model, new EditorState());
        build(builder, model, new EditorState());
        assertEquals(1, calls.get());
        assertFalse(builder.hasPendingSyntaxWork());

        DocumentSnapshot blocked = new DocumentSnapshot(new DocumentId("unscannable"), 1,
            List.of("x".repeat(100), "return 7;"));
        var highlighter = (ContextualLineHighlighter) RegexHighlighter.forJava();
        var result = highlighter.highlightResult(blocked, 1, blocked.line(1),
            new HighlightRequest(0, 9, 64, 16, 8192));
        assertFalse(result.retryPending(), "no retained progress means another identical request cannot help");
        assertTrue(result.spans().isEmpty());
    }

    private static TextModel model(List<String> lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        return model;
    }

    private static RenderPlanBuilder builder(TextModel model, LineHighlighter highlighter) {
        RenderPlanBuilder builder = new RenderPlanBuilder(highlighter, new TerminalEditorTextLayout(model));
        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        return builder;
    }

    private static RenderPlan build(RenderPlanBuilder builder, TextModel model, EditorState state) {
        return builder.build(model, state, 8, 80, "top", "bottom", null, SearchOptions.DEFAULT,
            null, null, null, null, false, false, null);
    }
}
