package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.DocumentView;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class RenderPlanBuilderLexicalBudgetTest {
    private static final Style MATCH = Style.fg(Color.GREEN);
    private static final Style ERROR = Style.fg(Color.RED);

    @Test
    void crossedNestingIsRejectedFromEitherSide() {
        TextModel model = model(List.of("([)]"));
        RenderPlanBuilder builder = builder(model, line -> List.of());
        for (int column = 0; column <= 4; column++) {
            EditorState state = new EditorState();
            state.setCursor(new Position(0, column));

            RenderPlan plan = builder.build(model, state, 6, 40, "", "");

            assertEquals(List.of(0, 1, 2, 3), columns(plan, ERROR));
            assertTrue(columns(plan, MATCH).isEmpty());
        }
    }

    @Test
    void ordinaryMixedNestingStillFindsItsPartnerInBothDirections() {
        TextModel model = model(List.of("({[]})"));
        RenderPlanBuilder builder = builder(model, line -> List.of());
        for (int column : new int[] {0, 5, 6}) {
            EditorState state = new EditorState();
            state.setCursor(new Position(0, column));
            RenderPlan plan = builder.build(model, state, 6, 40, "", "");

            assertEquals(List.of(0, 5), columns(plan, MATCH));
            assertTrue(columns(plan, ERROR).isEmpty());
        }
    }

    @Test
    void longLinesLimitSemanticWorkAndDoNotTurnAnUnseenPartnerIntoAnError() {
        TextModel model = model(List.of("(" + "a".repeat(1_100_000) + ")"));
        RecordingHighlighter highlighter = new RecordingHighlighter();
        RenderPlanBuilder builder = builder(model, highlighter);
        EditorState state = new EditorState();

        RenderPlan plan = builder.build(model, state, 6, 40, "", "");

        assertEquals(1_000_000, highlighter.scannedUnits);
        assertEquals(1, highlighter.calls);
        assertTrue(columns(plan, ERROR).isEmpty());
        assertTrue(columns(plan, MATCH).isEmpty());

        state.setCursor(new Position(0, 1_100_001));
        builder.build(model, state, 6, 40, "", "");
        assertEquals(1, highlighter.calls, "cursor-only frames reuse the bounded lexical cache");

        model.insertChar(new Position(0, 1), 'x');
        builder.build(model, state, 6, 40, "", "");
        assertEquals(2, highlighter.calls);
        assertEquals(2_000_000, highlighter.scannedUnits);
    }

    @Test
    void quotedContentConsumesTheSameBudgetAsCode() {
        TextModel model = model(List.of("(\"" + "a".repeat(250_000), "\")"));
        RecordingHighlighter highlighter = new RecordingHighlighter() {
            @Override
            public LineLexicalScanner lexicalScanner() {
                return (line, limit, previous) -> new LineLexicalScanner.Result(
                    limit, null,
                    List.of(line.startsWith("(\"")
                        ? new LineLexicalScanner.Range(1, limit)
                        : new LineLexicalScanner.Range(0, 1)), List.of()
                );
            }
        };
        RenderPlanBuilder builder = builder(model, highlighter);

        RenderPlan plan = builder.build(model, new EditorState(), 6, 40, "", "");

        // The 200k cursor-pair search may not jump over a huge masked string for free.
        assertTrue(columns(plan, MATCH).isEmpty());
    }

    @Test
    void emptyLinesCannotCreateAnUnboundedLexicalCache() {
        TextModel model = model(Collections.nCopies(40_000, ""));
        RecordingHighlighter highlighter = new RecordingHighlighter();
        RenderPlanBuilder builder = builder(model, highlighter);

        builder.build(model, new EditorState(), 6, 40, "", "");

        assertEquals(32_768, highlighter.calls);
        assertEquals(0, highlighter.scannedUnits);
    }

    @Test
    void fallbackQuoteParsingAlsoStopsAtThePrefixBudget() {
        TextModel model = model(List.of("(\"" + "a".repeat(1_100_000) + "\")"));
        RenderPlanBuilder builder = builder(model, line -> List.of());

        RenderPlan plan = builder.build(model, new EditorState(), 6, 40, "", "");

        assertTrue(columns(plan, MATCH).isEmpty());
        assertTrue(columns(plan, ERROR).isEmpty());
    }

    private static RenderPlanBuilder builder(TextModel model, LineHighlighter highlighter) {
        RenderPlanBuilder builder = RenderPlanBuilder.standalone(highlighter, new TerminalEditorTextLayout(model));
        builder.setSyntaxHighlightingEnabled(false);
        builder.setDecorationStyles(
            Style.DEFAULT, Style.DEFAULT, Style.DEFAULT, MATCH, MATCH, ERROR,
            List.of(Style.fg(Color.MAGENTA))
        );
        return builder;
    }

    private static List<Integer> columns(RenderPlan plan, Style style) {
        return plan.lines().getFirst().spans().stream()
            .filter(span -> span.style().equals(style)).map(StyleSpan::start).distinct().sorted().toList();
    }

    private static TextModel model(List<String> lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        return model;
    }

    private static class RecordingHighlighter implements ContextualLineHighlighter {
        int calls;
        int scannedUnits;

        @Override
        public LineLexicalScanner lexicalScanner() {
            return (line, limit, previous) -> {
                calls++;
                scannedUnits += limit;
                return new LineLexicalScanner.Result(limit, null, List.of(), List.of());
            };
        }

        @Override
        public List<StyleSpan> highlight(String line) {
            return List.of();
        }

        @Override
        public List<StyleSpan> highlight(DocumentView document, int lineIndex, String line) {
            return List.of();
        }
    }
}
