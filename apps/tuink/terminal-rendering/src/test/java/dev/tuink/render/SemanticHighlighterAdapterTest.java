package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.DocumentView;
import dev.tuink.editor.highlight.ContextualSyntaxHighlighter;
import dev.tuink.editor.highlight.LexicalScanner;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.SyntaxHighlighter;
import dev.tuink.editor.highlight.TokenSpan;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SemanticHighlighterAdapterTest {
    @Test
    void uniformCodeRoleUsesTheTerminalCodeColor() {
        SyntaxHighlighter provider = line -> List.of(new TokenSpan(0, line.length(), SemanticStyleKey.CODE));
        assertEquals(List.of(new StyleSpan(0, 9, Style.fg(Color.YELLOW))),
            new SemanticHighlighterAdapter(provider).highlight("return 7;"));
    }

    @Test
    void semanticTokensCanUseDifferentFrontendPalettesAndUnknownKeysRemainPlain() {
        SemanticStyleKey custom = new SemanticStyleKey("plugin.special-token");
        List<TokenSpan> tokens = List.of(new TokenSpan(0, 2, SemanticStyleKey.KEYWORD), new TokenSpan(2, 3, custom));
        SyntaxHighlighter provider = line -> tokens;
        SemanticHighlighterAdapter standard = new SemanticHighlighterAdapter(provider);
        Style alternate = new Style(Color.RED, Color.BLUE, false, true);
        SemanticHighlighterAdapter themed = new SemanticHighlighterAdapter(provider, key -> alternate);

        assertEquals(List.of(new StyleSpan(0, 2, new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false)),
            new StyleSpan(2, 3, Style.DEFAULT)), standard.highlight("abc"));
        assertEquals(List.of(new StyleSpan(0, 2, alternate), new StyleSpan(2, 3, alternate)), themed.highlight("abc"));
        assertEquals(List.of(new TokenSpan(0, 2, SemanticStyleKey.KEYWORD), new TokenSpan(2, 3, custom)), tokens);
    }

    @Test
    void boundedRequestsPreserveDocumentIdentityRetryAndLexicalContinuation() {
        DocumentSnapshot document = new DocumentSnapshot(new DocumentId("canonical"), 3, List.of("abc"));
        AtomicReference<dev.tuink.editor.highlight.HighlightRequest> received = new AtomicReference<>();
        LexicalScanner.State continuation = new LexicalScanner.State() {};
        LexicalScanner scanner = (line, limit, previous) -> {
            assertSame(continuation, previous);
            return new LexicalScanner.Result(2, continuation, List.of(new LexicalScanner.Range(0, 2)), List.of());
        };
        ContextualSyntaxHighlighter provider = new ContextualSyntaxHighlighter() {
            @Override public List<TokenSpan> highlight(String line) {
                throw new AssertionError("A bounded contextual request must not use the legacy overload");
            }
            @Override public List<TokenSpan> highlight(DocumentView view, int line, String text) {
                throw new AssertionError("The adapter must forward the bounded request");
            }
            @Override public dev.tuink.editor.highlight.HighlightResult highlightResult(DocumentView view,
                int lineIndex, String text, dev.tuink.editor.highlight.HighlightRequest request) {
                assertSame(document, view);
                received.set(request);
                return new dev.tuink.editor.highlight.HighlightResult(
                    List.of(new TokenSpan(0, 3, SemanticStyleKey.STRING)), true);
            }
            @Override public LexicalScanner lexicalScanner() { return scanner; }
        };
        SemanticHighlighterAdapter adapter = new SemanticHighlighterAdapter(provider);

        HighlightResult result = adapter.highlightResult(document, 0, "abc", new HighlightRequest(1, 2, 8, 4, 16));

        assertEquals(new dev.tuink.editor.highlight.HighlightRequest(1, 2, 8, 4, 16), received.get());
        assertEquals(List.of(new StyleSpan(1, 2, Style.fg(Color.BRIGHT_GREEN))), result.spans());
        assertTrue(result.retryPending());
        var lexical = adapter.lexicalScanner().scan("abc", 2, continuation);
        assertSame(continuation, lexical.endState());
        assertEquals(List.of(new LexicalScanner.Range(0, 2)), lexical.excludedRanges());
        assertSame(LexicalScanner.State.UNKNOWN, LineLexicalScanner.State.UNKNOWN);
    }

    @Test
    void oversizedLegacyInputIsRefusedBeforeCallingTheProvider() {
        AtomicInteger calls = new AtomicInteger();
        SyntaxHighlighter legacy = line -> {
            calls.incrementAndGet();
            return List.of(new TokenSpan(0, line.length(), SemanticStyleKey.STRING));
        };
        SemanticHighlighterAdapter adapter = new SemanticHighlighterAdapter(legacy);

        assertTrue(adapter.highlight("x".repeat(100_000), new HighlightRequest(0, 4, 8, 4, 16)).isEmpty());
        assertEquals(0, calls.get());
        assertEquals(List.of(new StyleSpan(0, 3, Style.fg(Color.BRIGHT_GREEN))),
            adapter.highlight("abc", new HighlightRequest(0, 3, 8, 4, 16)));
        assertEquals(1, calls.get());
    }
}
