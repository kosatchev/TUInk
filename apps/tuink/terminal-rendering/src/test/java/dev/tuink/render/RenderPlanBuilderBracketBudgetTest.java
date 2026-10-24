package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.CommentSyntaxProfile;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class RenderPlanBuilderBracketBudgetTest {
    private static final Style MATCH = Style.fg(Color.GREEN);
    private static final Style QUOTE = Style.fg(Color.YELLOW);
    private static final Style ERROR = Style.fg(Color.RED);
    private static final List<Style> RAINBOW = List.of(
        Style.fg(Color.BLUE), Style.fg(Color.CYAN), Style.fg(Color.MAGENTA));

    @Test
    void compactFactsPreserveMixedDepthsAndExcludeQuotedAndCommentedBrackets() {
        TextModel model = model(List.of("x({[]})", "\"[{}]\" /* [ */", "]"));
        RenderPlanBuilder builder = builder(model);
        RenderPlan plan = build(builder, model, new EditorState(), 80, false);

        assertEquals(List.of(
            new StyleSpan(1, 2, RAINBOW.get(0)), new StyleSpan(2, 3, RAINBOW.get(1)),
            new StyleSpan(3, 4, RAINBOW.get(2)), new StyleSpan(4, 5, RAINBOW.get(2)),
            new StyleSpan(5, 6, RAINBOW.get(1)), new StyleSpan(6, 7, RAINBOW.get(0))
        ), plan.lines().getFirst().spans());
        assertTrue(plan.lines().get(1).spans().isEmpty());
        assertEquals(List.of(new StyleSpan(0, 1, RAINBOW.getFirst()), new StyleSpan(0, 1, ERROR)),
            plan.lines().get(2).spans());
        assertEquals(7, builder.bracketFactCount());
    }

    @Test
    void togglesReleaseDisabledCachesAndPaletteChangesRestyleFactsAndCursorPairs() {
        TextModel model = model(List.of("x([])"));
        RenderPlanBuilder builder = builder(model);
        EditorState state = new EditorState();
        build(builder, model, state, 80, false);
        long revision = model.revision();
        long storage = builder.bracketFactsStorageBytes();

        builder.setBracketMatchingEnabled(false);
        assertEquals(4, build(builder, model, state, 80, false).lines().getFirst().spans().size());
        builder.setRainbowBracketsEnabled(false);
        assertEquals(0, builder.bracketFactCount());
        assertEquals(0, builder.bracketFactsStorageBytes());
        assertEquals(0, builder.lexicalCacheSize());
        assertTrue(build(builder, model, state, 80, false).lines().getFirst().spans().isEmpty());

        builder.setBracketMatchingEnabled(true);
        state.setCursor(new Position(0, 1));
        assertEquals(List.of(new StyleSpan(1, 2, MATCH), new StyleSpan(4, 5, MATCH)),
            build(builder, model, state, 80, false).lines().getFirst().spans());
        builder.setRainbowBracketsEnabled(true);
        List<Style> nextRainbow = List.of(Style.fg(Color.WHITE), Style.fg(Color.BRIGHT_MAGENTA));
        Style nextMatch = Style.fg(Color.BRIGHT_GREEN);
        builder.setDecorationStyles(Style.DEFAULT, Style.DEFAULT, Style.DEFAULT, nextMatch, QUOTE, ERROR, nextRainbow);
        RenderPlan changed = build(builder, model, state, 80, false);
        assertEquals(List.of(
            new StyleSpan(1, 2, nextRainbow.get(0)), new StyleSpan(2, 3, nextRainbow.get(1)),
            new StyleSpan(3, 4, nextRainbow.get(1)), new StyleSpan(4, 5, nextRainbow.get(0)),
            new StyleSpan(1, 2, nextMatch), new StyleSpan(4, 5, nextMatch)
        ), changed.lines().getFirst().spans());
        assertEquals(revision, model.revision());
        assertEquals(storage, builder.bracketFactsStorageBytes());
    }

    @Test
    void editsAndLexicalProfileChangesInvalidateFactsWithoutKeepingOldErrors() {
        TextModel model = model(List.of("x # ("));
        RenderPlanBuilder builder = builder(model);
        EditorState state = new EditorState();
        assertEquals(0, build(builder, model, state, 80, false).lines().getFirst().spans().size());
        builder.setCommentSyntaxProfile(CommentSyntaxProfile.NONE);
        assertEquals(List.of(new StyleSpan(4, 5, RAINBOW.getFirst()), new StyleSpan(4, 5, ERROR)),
            build(builder, model, state, 80, false).lines().getFirst().spans());

        model.insertChar(new Position(0, 5), ')');

        assertEquals(List.of(new StyleSpan(4, 5, RAINBOW.getFirst()), new StyleSpan(5, 6, RAINBOW.getFirst())),
            build(builder, model, state, 80, false).lines().getFirst().spans());
    }

    @Test
    void depthExhaustionLeavesUnseenPartnersUnknownInBothCursorSearchDirections() {
        int depth = RenderPlanBuilder.MAX_BRACKET_DEPTH + 1;
        String text = "(".repeat(depth) + ")".repeat(depth);
        TextModel model = model(List.of(text));
        RenderPlanBuilder builder = builder(model);
        EditorState state = new EditorState();

        RenderPlan forward = build(builder, model, state, 80, false);

        assertTrue(columns(forward, MATCH).isEmpty());
        assertTrue(columns(forward, ERROR).isEmpty(), "An incomplete scan cannot prove an open bracket unmatched");
        assertEquals(RenderPlanBuilder.MAX_BRACKET_DEPTH, builder.bracketFactCount());
        state.setCursor(new Position(0, text.length()));
        state.setLeftVisualColumn(text.length() - 40);
        RenderPlan backward = build(builder, model, state, 80, false);
        assertTrue(columns(backward, MATCH).isEmpty());
        assertTrue(columns(backward, ERROR).isEmpty());
    }

    @Test
    void resultExhaustionKeepsKnownMismatchesWithoutInventingUnclosedOuterBrackets() {
        TextModel model = model(List.of("x([)](" + "[]".repeat(RenderPlanBuilder.MAX_BRACKET_FACTS / 2) + ")"));
        RenderPlanBuilder builder = builder(model);
        RenderPlan plan = build(builder, model, new EditorState(), 80, false);

        assertEquals(List.of(1, 2, 3, 4), columns(plan, ERROR));
        assertEquals(RenderPlanBuilder.MAX_BRACKET_FACTS, builder.bracketFactCount());
        assertTrue(builder.bracketFactsStorageBytes()
            <= 13L * RenderPlanBuilder.MAX_BRACKET_FACTS + RenderPlanBuilder.MAX_BRACKET_FACTS / 8);
    }

    @Test
    void longWrappedLinesOnlyMaterializeDecorationsForTheVisibleSegments() {
        TextModel model = model(List.of("x" + "[]".repeat(200_000)));
        RenderPlanBuilder builder = builder(model);
        EditorState state = new EditorState();
        state.setTopWrapSegment(1000);

        RenderPlan plan = build(builder, model, state, 5, true);

        assertEquals(RenderPlanBuilder.MAX_BRACKET_FACTS, builder.bracketFactCount());
        assertTrue(plan.lines().stream().allMatch(line -> line.spans().size() <= 1));
        assertEquals(plan.contentRows(), plan.lines().stream().mapToInt(line -> line.spans().size()).sum());
    }

    private static List<Integer> columns(RenderPlan plan, Style style) {
        return plan.lines().getFirst().spans().stream().filter(span -> span.style().equals(style))
            .map(StyleSpan::start).distinct().sorted().toList();
    }

    private static TextModel model(List<String> lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        return model;
    }

    private static RenderPlanBuilder builder(TextModel model) {
        RenderPlanBuilder builder = RenderPlanBuilder.standalone(line -> List.of(), new TerminalEditorTextLayout(model));
        builder.setSyntaxHighlightingEnabled(false);
        builder.setDecorationStyles(Style.DEFAULT, Style.DEFAULT, Style.DEFAULT, MATCH, QUOTE, ERROR, RAINBOW);
        return builder;
    }

    private static RenderPlan build(RenderPlanBuilder builder, TextModel model, EditorState state, int columns, boolean wrap) {
        return builder.build(model, state, 8, columns, "top", "bottom", null, SearchOptions.DEFAULT,
            null, null, null, null, wrap, false);
    }
}
