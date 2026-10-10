package dev.tuink.highlight.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.render.Color;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.Style;
import dev.tuink.render.StyleSpan;
import dev.tuink.render.TerminalEditorTextLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

class LexicalRenderIntegrationTest {
    private static final Style MATCH = new Style(Color.GREEN, Color.RED, true, true);
    private static final Style QUOTE = new Style(Color.BLUE, Color.RED, true, true);
    private static final Style UNMATCHED = new Style(Color.RED, Color.BLUE, true, true);

    @Test
    void actualLanguageScannersProtectBracketMatchingWhenSyntaxColorsAreDisabledOrEnabled() {
        for (LexicalScannerTest.LiteralCase example : LexicalScannerTest.literalCases()) {
            for (boolean syntax : List.of(false, true)) {
                TextModel model = model(example.lines());
                RenderPlanBuilder builder = builder(model, example.fileName(), syntax);
                EditorState state = new EditorState();
                state.setCursor(new Position(1, 0));
                RenderPlan inside = builder.build(model, state, 12, 80, "", "");
                assertTrue(inside.lines().stream().flatMap(line -> line.spans().stream())
                    .noneMatch(span -> decoration(span.style())), example.fileName());

                state.setCursor(new Position(example.lines().size() - 1, 0));
                RenderPlan outside = builder.build(model, state, 12, 80, "", "");
                assertTrue(outside.lines().get(example.lines().size() - 1).spans().stream()
                    .anyMatch(span -> span.style().equals(MATCH)), example.fileName());
            }
        }
    }

    @Test
    void ordinaryQuoteMatchingStillUsesTheRealLanguageScannerWithoutSyntaxColors() {
        TextModel model = model(List.of("\"a\\\"b\""));
        RenderPlanBuilder builder = builder(model, "test.java", false);
        RenderPlan plan = builder.build(model, new EditorState(), 8, 80, "", "");
        assertEquals(List.of(new StyleSpan(0, 1, QUOTE), new StyleSpan(5, 6, QUOTE)),
            plan.lines().getFirst().spans());
    }

    private static boolean decoration(Style style) {
        return style.equals(MATCH) || style.equals(QUOTE) || style.equals(UNMATCHED);
    }

    private static TextModel model(List<String> lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        return model;
    }

    private static RenderPlanBuilder builder(TextModel model, String fileName, boolean syntax) {
        RenderPlanBuilder builder = new RenderPlanBuilder(
            RegexHighlighter.forFileName(fileName), new TerminalEditorTextLayout(model));
        builder.setSyntaxHighlightingEnabled(syntax);
        builder.setRainbowBracketsEnabled(false);
        builder.setDecorationStyles(Style.DEFAULT, Style.DEFAULT, Style.DEFAULT, MATCH, QUOTE, UNMATCHED,
            List.of(Style.DEFAULT));
        return builder;
    }
}
