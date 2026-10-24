package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentView;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HighlightRequestTest {
    @Test
    void legacyAdapterClipsOriginalOffsetsAndRefusesOversizedInputBeforeCallingTheImplementation() {
        AtomicInteger calls = new AtomicInteger();
        LineHighlighter legacy = line -> {
            calls.incrementAndGet();
            return List.of(new StyleSpan(0, line.length(), Style.DEFAULT));
        };
        HighlightRequest request = new HighlightRequest(2, 5, 8, 4, 100);

        assertEquals(List.of(new StyleSpan(2, 5, Style.DEFAULT)), legacy.highlight("abcdef", request));
        assertTrue(legacy.highlight("a".repeat(100_000), request).isEmpty());
        assertEquals(1, calls.get());
        assertFalse(new HighlightRequest(0, 1, 8, 4, 0).allowsFullLine("a"));
    }

    @Test
    void contextualCompatibilityKeepsDocumentContextAndTheSameOversizedInputGuard() {
        AtomicInteger calls = new AtomicInteger();
        TextModel model = new TextModel(new SimpleLineBuffer());
        DocumentView document = new TextModelDocumentView(model, new DocumentId("highlight-request"));
        ContextualLineHighlighter legacy = new ContextualLineHighlighter() {
            @Override public List<StyleSpan> highlight(String line) {
                throw new AssertionError("The document-aware overload must be used");
            }
            @Override public List<StyleSpan> highlight(DocumentView view, int index, String line) {
                assertEquals(document, view);
                calls.incrementAndGet();
                return List.of(new StyleSpan(0, line.length(), Style.DEFAULT));
            }
        };
        HighlightRequest request = new HighlightRequest(0, 1, 8, 4, 100);

        assertEquals(1, legacy.highlight(document, 0, "short", request).size());
        assertTrue(legacy.highlight(document, 0, "a".repeat(100_000), request).isEmpty());
        assertEquals(1, calls.get());
    }

    @Test
    void outputAndWorkExhaustionFallBackToPlainTextAndScanEndsAreCodePointSafe() {
        List<StyleSpan> spans = List.of(new StyleSpan(0, 1, Style.DEFAULT),
            new StyleSpan(2, 3, Style.DEFAULT), new StyleSpan(4, 5, Style.DEFAULT));

        assertTrue(new HighlightRequest(0, 5, 8, 2, 100).clipSpans(spans).isEmpty());
        assertTrue(new HighlightRequest(0, 5, 8, 4, 2).clipSpans(spans).isEmpty());
        assertEquals(List.of(spans.getFirst()), new HighlightRequest(0, 1, 8, 1, 100).clipSpans(spans));
        assertEquals(1, new HighlightRequest(0, 4, 2, 4, 100).scanEnd("a😀b"));
        assertThrows(IllegalArgumentException.class, () -> new HighlightRequest(2, 1, 8, 4, 100));
        assertThrows(IllegalArgumentException.class, () -> new HighlightRequest(0, 1, -1, 4, 100));
    }
}
