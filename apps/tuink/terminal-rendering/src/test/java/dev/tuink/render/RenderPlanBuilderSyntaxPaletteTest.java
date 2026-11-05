package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.DocumentView;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.LexicalScanner;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.SyntaxHighlighter;
import dev.tuink.editor.highlight.TokenSpan;
import dev.tuink.editor.search.SearchOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RenderPlanBuilderSyntaxPaletteTest {
    private static final Style COLORED = new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false);
    private static final Style GRAY = new Style(Color.BRIGHT_BLACK, Color.DEFAULT, true, false);
    private static final SyntaxStyleResolver COLOR_PALETTE = key -> COLORED;
    private static final SyntaxStyleResolver GRAY_PALETTE = key -> GRAY;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void paletteChangesRestyleRetainedRolesWithoutRepeatingSyntaxOrLexicalWork(boolean wrapped) {
        TextModel model = model("return (value)", "tail");
        var provider = new CountingContextualProvider();
        var layout = new TerminalEditorTextLayout(model);
        var builder = RenderPlanBuilder.standalone(new SemanticHighlighterAdapter(provider, COLOR_PALETTE), layout);
        var state = new EditorState();
        var wrapGeometry = layout.wrapStarts(0, 70, false, 4);
        long revision = model.revision();

        RenderPlan colored = build(builder, model, state, 80, wrapped);
        assertEquals(COLORED, colored.lines().getFirst().spans().getFirst().style());
        assertEquals(2, provider.syntaxCalls);
        assertEquals(2, provider.lexicalCalls);
        int facts = builder.bracketFactCount();
        long factBytes = builder.bracketFactsStorageBytes();
        assertTrue(facts > 0);
        int entries = builder.syntaxCacheSize();
        int resolvedWeight = builder.syntaxCacheWeight();
        var coloredKey = colored.lines().getFirst().baseStyleKey();

        builder.setSyntaxStyleResolver(GRAY_PALETTE);
        assertEquals(entries, builder.syntaxCacheSize(), "palette changes retain semantic cache entries");
        assertEquals(resolvedWeight - 1, builder.syntaxCacheWeight(), "only one resolved keyword span is released");
        RenderPlan gray = build(builder, model, state, 80, wrapped);
        assertEquals(GRAY, gray.lines().getFirst().spans().getFirst().style());
        assertEquals(resolvedWeight, builder.syntaxCacheWeight());
        var grayKey = gray.lines().getFirst().baseStyleKey();
        assertNotEquals(coloredKey, grayKey, "prepared display rows must not retain the previous palette");
        assertEquals(coloredKey.preparedRowKey(4, 0).producerId(), grayKey.preparedRowKey(4, 0).producerId());

        builder.setSyntaxStyleResolver(GRAY_PALETTE);
        RenderPlan stable = build(builder, model, state, 80, wrapped);
        assertEquals(grayKey, stable.lines().getFirst().baseStyleKey(), "the same immutable palette is a no-op");
        assertEquals(resolvedWeight, builder.syntaxCacheWeight());

        state.setCursor(new Position(0, 7));
        build(builder, model, state, 80, wrapped);
        builder.setSyntaxStyleResolver(COLOR_PALETTE);
        RenderPlan restored = build(builder, model, state, 80, wrapped);
        assertEquals(COLORED, restored.lines().getFirst().spans().getFirst().style());
        assertEquals(2, provider.syntaxCalls);
        assertEquals(2, provider.lexicalCalls, "matching brackets reuse their existing lexical context");
        assertEquals(facts, builder.bracketFactCount());
        assertEquals(factBytes, builder.bracketFactsStorageBytes());
        assertSame(layout, builder.textLayout());
        assertSame(wrapGeometry, layout.wrapStarts(0, 70, false, 4));
        assertEquals(revision, model.revision());
    }

    @Test
    void semanticAndResolvedSpansShareTheExistingWeightBoundAcrossPalettesAndViewportRanges() {
        var calls = new AtomicInteger();
        SyntaxHighlighter provider = new SyntaxHighlighter() {
            @Override public List<TokenSpan> highlight(String line) { throw new AssertionError("bounded API required"); }
            @Override public List<TokenSpan> highlight(String line, dev.tuink.editor.highlight.HighlightRequest request) {
                calls.incrementAndGet();
                var spans = new ArrayList<TokenSpan>();
                for (int offset = request.startUtf16(); offset < Math.min(request.endUtf16(), request.scanEnd(line)); offset++)
                    spans.add(new TokenSpan(offset, offset + 1, SemanticStyleKey.KEYWORD));
                return request.clipSpans(spans);
            }
        };
        TextModel model = model("a".repeat(100_000));
        var builder = plainBuilder(model, new SemanticHighlighterAdapter(provider, COLOR_PALETTE));
        var state = new EditorState();
        RenderPlan first = build(builder, model, state, 2048, false);
        int tokens = first.lines().getFirst().spans().size();
        int resolvedWeight = 1 + 2 * tokens;
        assertEquals(resolvedWeight, builder.syntaxCacheWeight(), "retention counts roles and their physical styles");
        for (int palette = 0; palette < 20; palette++) {
            builder.setSyntaxStyleResolver(palette % 2 == 0 ? GRAY_PALETTE : COLOR_PALETTE);
            assertEquals(1 + tokens, builder.syntaxCacheWeight());
            build(builder, model, state, 2048, false);
            assertEquals(resolvedWeight, builder.syntaxCacheWeight());
        }
        assertEquals(1, calls.get());
        for (int window = 1; window < 100; window++) {
            state.setLeftVisualColumn(window * 100);
            build(builder, model, state, 2048, false);
            assertTrue(builder.syntaxCacheWeight() <= RenderPlanBuilder.MAX_SYNTAX_CACHE_WEIGHT);
        }
        assertTrue(builder.syntaxCacheSize() < 100);
        int beforeRevisit = calls.get();
        state.setLeftVisualColumn(0);
        build(builder, model, state, 2048, false);
        assertEquals(beforeRevisit + 1, calls.get(), "semantic entries remain subject to the same bounded LRU");
        model.insertChar(new Position(0, 0), 'b');
        build(builder, model, state, 2048, false);
        assertEquals(1, builder.syntaxCacheSize(), "text changes still replace the syntax cache");
    }

    @Test
    void resumableTokensRemainUncachedAndClippedWhenThePaletteChangesBetweenRetries() {
        var calls = new AtomicInteger();
        ContextualSyntaxHighlighter provider = new ContextualSyntaxHighlighter() {
            @Override public List<TokenSpan> highlight(String line) { throw new AssertionError("bounded API required"); }
            @Override public List<TokenSpan> highlight(DocumentView document, int row, String line) {
                throw new AssertionError("bounded contextual API required");
            }
            @Override public dev.tuink.editor.highlight.HighlightResult highlightResult(DocumentView document,
                int row, String line, dev.tuink.editor.highlight.HighlightRequest request) {
                return new dev.tuink.editor.highlight.HighlightResult(
                    List.of(new TokenSpan(0, line.length(), SemanticStyleKey.KEYWORD)), calls.incrementAndGet() == 1);
            }
        };
        TextModel model = model("return");
        var builder = plainBuilder(model, new SemanticHighlighterAdapter(provider, COLOR_PALETTE));
        var state = new EditorState();
        RenderPlan first = build(builder, model, state, 8, false);
        assertTrue(builder.hasPendingSyntaxWork());
        assertEquals(0, builder.syntaxCacheSize());
        assertEquals(first.lines().getFirst().text().length(), first.lines().getFirst().spans().getFirst().end());
        builder.setSyntaxStyleResolver(GRAY_PALETTE);
        RenderPlan second = build(builder, model, state, 8, false);
        assertFalse(builder.hasPendingSyntaxWork());
        assertEquals(GRAY, second.lines().getFirst().spans().getFirst().style());
        assertEquals(1, builder.syntaxCacheSize());
        build(builder, model, state, 8, false);
        assertEquals(2, calls.get());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void styledOnlyHighlightersKeepTheirOwnStylesAndCachedResults(boolean wrapped) {
        TextModel model = model("legacy");
        var calls = new AtomicInteger();
        LineHighlighter provider = line -> {
            calls.incrementAndGet();
            return List.of(new StyleSpan(0, line.length(), COLORED));
        };
        var builder = plainBuilder(model, provider);
        var state = new EditorState();
        RenderPlan before = build(builder, model, state, 80, wrapped);
        int weight = builder.syntaxCacheWeight();
        builder.setSyntaxStyleResolver(key -> { throw new AssertionError("a styled provider has no semantic roles"); });
        RenderPlan after = build(builder, model, state, 80, wrapped);
        assertEquals(before.lines().getFirst().spans(), after.lines().getFirst().spans());
        assertEquals(weight, builder.syntaxCacheWeight());
        assertEquals(1, calls.get());
    }

    private static final class CountingContextualProvider implements ContextualSyntaxHighlighter {
        int syntaxCalls, lexicalCalls;
        private final LexicalScanner.State continuation = new LexicalScanner.State() {};
        @Override public List<TokenSpan> highlight(String line) { throw new AssertionError("bounded contextual API required"); }
        @Override public List<TokenSpan> highlight(DocumentView document, int row, String line) {
            throw new AssertionError("bounded contextual API required");
        }
        @Override public dev.tuink.editor.highlight.HighlightResult highlightResult(DocumentView document,
            int row, String line, dev.tuink.editor.highlight.HighlightRequest request) {
            syntaxCalls++;
            return dev.tuink.editor.highlight.HighlightResult.completed(row == 0
                ? List.of(new TokenSpan(0, 6, SemanticStyleKey.KEYWORD)) : List.of());
        }
        @Override public LexicalScanner lexicalScanner() {
            return (line, limit, previous) -> {
                lexicalCalls++;
                if (line.startsWith("return")) assertNull(previous);
                else assertSame(continuation, previous);
                return new LexicalScanner.Result(limit, continuation, List.of(), List.of());
            };
        }
    }

    private static RenderPlanBuilder plainBuilder(TextModel model, LineHighlighter provider) {
        var builder = RenderPlanBuilder.standalone(provider, new TerminalEditorTextLayout(model));
        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        return builder;
    }

    private static TextModel model(String... lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of(lines));
        return model;
    }

    private static RenderPlan build(RenderPlanBuilder builder, TextModel model, EditorState state, int cols, boolean wrapped) {
        return builder.build(model, state, 6, cols, "", "", null, SearchOptions.DEFAULT,
            null, null, null, null, wrapped, false);
    }
}
