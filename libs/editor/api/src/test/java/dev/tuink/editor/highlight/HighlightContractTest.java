package dev.tuink.editor.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HighlightContractTest {
    @Test
    void tokenRolesAreNamespacedAndOutputAndWorkLimitsRemainIndependent() {
        assertEquals(new SemanticStyleKey("syntax.keyword"), SemanticStyleKey.KEYWORD);
        assertThrows(IllegalArgumentException.class, () -> new SemanticStyleKey("BRIGHT_BLUE"));
        assertThrows(IllegalArgumentException.class, () -> new SemanticStyleKey("keyword"));
        assertThrows(IllegalArgumentException.class, () -> new TokenSpan(2, 1, SemanticStyleKey.STRING));
        List<TokenSpan> spans = List.of(new TokenSpan(0, 2, SemanticStyleKey.STRING),
            new TokenSpan(2, 4, SemanticStyleKey.NUMBER));

        assertEquals(List.of(new TokenSpan(1, 2, SemanticStyleKey.STRING)),
            new HighlightRequest(1, 2, 8, 1, 4).clipSpans(spans));
        assertTrue(new HighlightRequest(0, 4, 8, 1, 4).clipSpans(spans).isEmpty());
        assertTrue(new HighlightRequest(0, 4, 8, 4, 1).clipSpans(spans).isEmpty());
        assertEquals(1, new HighlightRequest(0, 4, 2, 4, 16).scanEnd("a😀b"));
    }

    @Test
    void oneConservativeScannerHandlesGenericAndMarkdownProfilesAndKeepsUnknownContextUnknown() {
        LexicalScanner generic = new GenericLexicalScanner(LexicalConfig.GENERIC);
        LexicalScanner markdown = new GenericLexicalScanner(LexicalConfig.MARKDOWN);

        assertTrue(generic.scan("1) item", 7, null).excludedRanges().isEmpty());
        assertEquals(List.of(new LexicalScanner.Range(1, 2)), markdown.scan("1) item", 7, null).excludedRanges());
        var opened = generic.scan("/*", 2, null);
        var continued = generic.scan("( )", 3, opened.endState());
        assertEquals(List.of(new LexicalScanner.Range(0, 3)), continued.excludedRanges());
        var partial = generic.scan("\"text\" tail", 3, null);
        assertFalse(partial.contextKnown());
        assertSame(LexicalScanner.State.UNKNOWN, partial.endState());
        var empty = generic.scan("", 0, partial.endState());
        assertFalse(empty.contextKnown());
        assertSame(LexicalScanner.State.UNKNOWN, empty.endState());
    }
}
