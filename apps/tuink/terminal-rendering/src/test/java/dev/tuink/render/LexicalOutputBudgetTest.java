package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.DocumentView;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LexicalOutputBudgetTest {
    private static final Style MATCH = Style.fg(Color.GREEN);
    private static final Style ERROR = Style.fg(Color.RED);

    @Test
    void fallbackScannerBoundsDenseQuotesAndDisjointCommentRanges() {
        for (boolean markdown : List.of(false, true)) {
            LineLexicalScanner scanner = markdown ? LineLexicalScanner.markdownProse() : LineLexicalScanner.generic();
            for (String text : List.of("\"\"".repeat(LineLexicalScanner.MAX_QUOTES + 1),
                (markdown ? "<!--x--> " : "/*x*/ ").repeat(LineLexicalScanner.MAX_EXCLUDED_RANGES + 1))) {
                var result = scanner.scan(text, text.length(), null);
                assertFalse(result.contextKnown());
                assertSame(LineLexicalScanner.State.UNKNOWN, result.endState());
                assertTrue(result.scannedCodeUnits() < text.length());
                assertTrue(result.excludedRanges().size() <= LineLexicalScanner.MAX_EXCLUDED_RANGES);
                assertTrue(result.quotes().size() <= LineLexicalScanner.MAX_QUOTES);
                for (var range : result.excludedRanges()) assertTrue(range.end() <= result.scannedCodeUnits());
                for (var quote : result.quotes()) assertTrue(quote.end() >= 0 && quote.end() < result.scannedCodeUnits());
            }
            assertFalse(scanner.scan("", 0, LineLexicalScanner.State.UNKNOWN).contextKnown());
            assertTrue(scanner.scan("", 0, null).contextKnown());
            assertFalse(scanner.scan("\"x\"", 0, null).contextKnown());
        }
    }

    @Test
    void unknownEmptyLineStopsCursorMatchingAndCannotProveAnOpenBracketUnmatched() {
        AtomicInteger calls = new AtomicInteger();
        LineLexicalScanner scanner = (line, limit, previous) -> {
            calls.incrementAndGet();
            return new LineLexicalScanner.Result(limit, null, List.of(), List.of(), !line.isEmpty());
        };
        TextModel model = model(List.of("(", ""));
        RenderPlanBuilder builder = builder(model, scanner);

        RenderPlan plan = builder.build(model, new EditorState(), 6, 40, "", "");

        assertEquals(2, calls.get());
        assertEquals(1, builder.bracketFactCount());
        assertTrue(plan.lines().stream().allMatch(line -> line.spans().isEmpty()));
        builder.build(model, new EditorState(), 6, 40, "", "");
        assertEquals(2, calls.get(), "A permanent lexical stop is cached until the document changes");
        model.insertText(new Position(1, 0), ")");
        RenderPlan complete = builder.build(model, new EditorState(), 6, 40, "", "");
        assertTrue(complete.lines().getFirst().spans().stream().anyMatch(span -> span.style().equals(MATCH)));
    }

    @Test
    void incompleteFullLengthResultCannotDeclareAQuoteUnclosedOrContinueToAnotherLine() {
        AtomicInteger calls = new AtomicInteger();
        LineLexicalScanner scanner = (line, limit, previous) -> {
            calls.incrementAndGet();
            return new LineLexicalScanner.Result(line.length(), null,
                List.of(new LineLexicalScanner.Range(0, line.length())),
                List.of(new LineLexicalScanner.Quote(0, -1)), false);
        };
        TextModel model = model(List.of("\"x", "([)]"));
        RenderPlanBuilder builder = builder(model, scanner);

        RenderPlan plan = builder.build(model, new EditorState(), 6, 40, "", "");

        assertEquals(1, calls.get());
        assertTrue(plan.lines().stream().allMatch(line -> line.spans().isEmpty()));
    }

    @Test
    void manyShortLinesShareAnOutputCacheBudgetAndDisabledDecorationsReleaseIt() {
        TextModel model = model(Collections.nCopies(40, "\"x\" ".repeat(1024)));
        RenderPlanBuilder builder = builder(model, LineLexicalScanner.generic());
        builder.build(model, new EditorState(), 6, 40, "", "");
        assertEquals(RenderPlanBuilder.MAX_LEXICAL_CACHE_OUTPUTS, builder.lexicalOutputCount());
        assertTrue(builder.lexicalCacheSize() < model.lineCount());

        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);

        assertEquals(0, builder.lexicalCacheSize());
        assertEquals(0, builder.lexicalOutputCount());
    }

    private static RenderPlanBuilder builder(TextModel model, LineLexicalScanner scanner) {
        ContextualLineHighlighter highlighter = new ContextualLineHighlighter() {
            @Override public List<StyleSpan> highlight(String line) { return List.of(); }
            @Override public List<StyleSpan> highlight(DocumentView document, int line, String text) { return List.of(); }
            @Override public LineLexicalScanner lexicalScanner() { return scanner; }
        };
        RenderPlanBuilder builder = RenderPlanBuilder.standalone(highlighter, new TerminalEditorTextLayout(model));
        builder.setSyntaxHighlightingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        builder.setDecorationStyles(Style.DEFAULT, Style.DEFAULT, Style.DEFAULT, MATCH, MATCH, ERROR,
            List.of(Style.DEFAULT));
        return builder;
    }

    private static TextModel model(List<String> lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        return model;
    }
}
