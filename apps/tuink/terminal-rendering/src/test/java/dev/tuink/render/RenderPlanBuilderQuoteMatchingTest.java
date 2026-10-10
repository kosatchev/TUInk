package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.CommentSyntaxProfile;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class RenderPlanBuilderQuoteMatchingTest {
    private static final Style QUOTE = Style.fg(Color.BLUE);
    private static final Style UNMATCHED = Style.fg(Color.RED);

    @Test
    void singleAndDoubleQuotesMatchFromEitherDelimiterAndAfterTheClosingDelimiter() {
        for (String text : List.of("'abc'", "\"abc\"")) {
            for (int cursor : new int[] {0, 1, 4, 5}) {
                assertEquals(List.of(0, 4), quoteColumns(render(List.of(text), 0, cursor)));
            }
        }
    }

    @Test
    void escapedDelimitersAndQuotesInsideOtherStringsDoNotBecomePartners() {
        String escaped = "\"a\\\"b\"";
        assertEquals(List.of(0, 5), quoteColumns(render(List.of(escaped), 0, 0)));
        assertTrue(render(List.of(escaped), 0, 3).lines().getFirst().spans().isEmpty());

        String embedded = "\"a ' b\"";
        assertEquals(List.of(0, 6), quoteColumns(render(List.of(embedded), 0, 0)));
        assertTrue(render(List.of(embedded), 0, 3).lines().getFirst().spans().isEmpty());

        String escapedOutside = "\\\"abc\\\"";
        assertTrue(render(List.of(escapedOutside), 0, 1).lines().getFirst().spans().isEmpty());
    }

    @Test
    void evenBackslashesAllowAClosingDelimiter() {
        String text = "\"a\\\\\"";
        assertEquals(List.of(0, 4), quoteColumns(render(List.of(text), 0, 4)));
    }

    @Test
    void apostrophesInWordsAndQuotesInCommentsDoNotMatch() {
        for (String text : List.of("don't", "𐐀'𐐀")) {
            assertTrue(render(List.of(text), 0, text.indexOf('\'')).lines().getFirst().spans().isEmpty());
        }
        for (String text : List.of("// \"abc\"", "/* 'abc' */", "# \"abc\"", "-- 'abc'")) {
            int quote = Math.max(text.indexOf('"'), text.indexOf('\''));
            assertTrue(render(List.of(text), 0, quote).lines().getFirst().spans().isEmpty());
        }
        RenderPlan blockComment = render(List.of("/*", "\"abc\"", "*/"), 1, 0);
        assertTrue(blockComment.lines().get(1).spans().isEmpty());
    }

    @Test
    void htmlCommentsAndTheirContinuationDoNotCreateQuotePairs() {
        TextModel model = model(List.of("<!-- '", "\"abc\"", "--> \"ok\""));
        EditorState state = new EditorState();
        state.setCursor(new Position(1, 0));
        RenderPlanBuilder builder = builder(model);
        builder.setCommentSyntaxProfile(CommentSyntaxProfile.HTML);

        assertTrue(builder.build(model, state, 10, 80, "", "").lines().get(1).spans().isEmpty());
        state.setCursor(new Position(2, 4));
        assertEquals(List.of(4, 7), quoteColumns(builder.build(model, state, 10, 80, "", "")));
    }

    @Test
    void adjacentStringsKeepTheirOwnPartnersAndUnclosedQuotesRemainUnmatched() {
        String text = "\"a\" '\"b'";
        assertEquals(List.of(0, 2), quoteColumns(render(List.of(text), 0, 2)));
        assertEquals(List.of(4, 7), quoteColumns(render(List.of(text), 0, 4)));

        RenderPlan unmatched = render(List.of("\"abc // '"), 0, 0);
        assertEquals(List.of(new StyleSpan(0, 1, UNMATCHED)), unmatched.lines().getFirst().spans());
    }

    @Test
    void quoteMatchingDoesNotExposeBracketsInsideStrings() {
        RenderPlan plan = render(List.of("\"x(abc]x\""), 0, 2);
        assertTrue(plan.lines().getFirst().spans().isEmpty());
    }

    @Test
    void editingTheClosingQuoteInvalidatesItsCachedPartner() {
        TextModel model = model(List.of("\"abc\""));
        EditorState state = new EditorState();
        RenderPlanBuilder builder = builder(model);
        assertEquals(List.of(0, 4), quoteColumns(builder.build(model, state, 10, 80, "", "")));

        model.deleteForward(new Position(0, 4));

        RenderPlan changed = builder.build(model, state, 10, 80, "", "");
        assertEquals(List.of(new StyleSpan(0, 1, UNMATCHED)), changed.lines().getFirst().spans());
    }

    private static List<Integer> quoteColumns(RenderPlan plan) {
        return plan.lines().stream()
            .flatMap(line -> line.spans().stream())
            .filter(span -> span.style().equals(QUOTE))
            .map(StyleSpan::start)
            .sorted()
            .toList();
    }

    private static RenderPlan render(List<String> lines, int line, int col) {
        TextModel model = model(lines);
        EditorState state = new EditorState();
        state.setCursor(new Position(line, col));
        return builder(model).build(model, state, 10, 80, "", "");
    }

    private static TextModel model(List<String> lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        return model;
    }

    private static RenderPlanBuilder builder(TextModel model) {
        RenderPlanBuilder builder = new RenderPlanBuilder(line -> List.of(), new TerminalEditorTextLayout(model));
        builder.setSyntaxHighlightingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        builder.setCommentSyntaxProfile(CommentSyntaxProfile.GENERIC);
        builder.setDecorationStyles(
            Style.DEFAULT, Style.DEFAULT, Style.DEFAULT, Style.fg(Color.GREEN),
            QUOTE, UNMATCHED, List.of(Style.DEFAULT)
        );
        return builder;
    }
}
