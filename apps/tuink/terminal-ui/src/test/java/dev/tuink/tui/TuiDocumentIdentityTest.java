package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.LineText;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.DocumentView;
import dev.tuink.render.ContextualLineHighlighter;
import dev.tuink.render.HighlightRequest;
import dev.tuink.render.HighlightResult;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.StyleSpan;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TuiDocumentIdentityTest {
    @Test
    void attachedTabsRequireTheirSessionsCanonicalIdentityEvenWhenTheLayoutMatches() {
        TuiDocumentSession session = TuiDocumentSession.create(new EditorClipboard());
        RenderPlanBuilder canonical = session.newRenderPlanBuilder(line -> List.of());
        var tab = new TerminalEditorApp.EditorTab(session, canonical);

        assertSame(session.document().documentId(), tab.renderPlanBuilder().documentId());
        RenderPlanBuilder standalone = RenderPlanBuilder.standalone(line -> List.of(), session.view().textLayout());
        assertThrows(IllegalArgumentException.class, () -> new TerminalEditorApp.EditorTab(session, standalone));
    }

    @Test
    void renderersShareTheCanonicalSessionIdentityWithoutPublishingWholeDocumentSnapshots()
        throws InterruptedException {
        TuiDocumentSession session = TuiDocumentSession.create(new EditorClipboard());
        var view = session.view();
        view.model().replaceLines(List.of("x".repeat(1_000_000)));
        DocumentSnapshot published = session.document().snapshot();
        view.engine().insertInput("!");
        CapturingHighlighter highlighter = new CapturingHighlighter();
        long materializedBefore = LineText.work().materializedCodeUnits();

        RenderPlanBuilder first = session.newRenderPlanBuilder(highlighter);
        render(first, session);
        DocumentView firstCapability = highlighter.document;
        RenderPlanBuilder second = session.newRenderPlanBuilder(highlighter);
        render(second, session);

        assertNotNull(firstCapability);
        assertNotNull(highlighter.document);
        assertNotSame(firstCapability, highlighter.document, "each renderer owns its live adapter and caches");
        assertSame(published.documentId(), first.documentId());
        assertSame(published.documentId(), second.documentId());
        assertSame(published.documentId(), firstCapability.documentId());
        assertSame(published.documentId(), highlighter.document.documentId());
        assertEquals(view.model().revision(), highlighter.document.revision());
        assertEquals('!', highlighter.document.lineText(0).charAt(0));
        assertTrue(LineText.work().materializedCodeUnits() - materializedBefore < 262_144,
            "rendering two bounded prefixes must not materialize the million-character document");

        AtomicReference<DocumentSnapshot> backgroundSnapshot = new AtomicReference<>();
        Thread background = new Thread(() -> backgroundSnapshot.set(session.document().snapshot()));
        background.start();
        background.join();
        assertSame(published, backgroundSnapshot.get(), "rendering must not publish unpublished UI edits");
    }

    private static void render(RenderPlanBuilder builder, TuiDocumentSession session) {
        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        builder.build(session.view().model(), session.view().state(), 4, 80, "top", "bottom");
    }

    private static final class CapturingHighlighter implements ContextualLineHighlighter {
        private DocumentView document;

        @Override public List<StyleSpan> highlight(String line) {
            throw new AssertionError("The renderer must use the document-aware overload");
        }

        @Override public List<StyleSpan> highlight(DocumentView document, int lineIndex, String line) {
            throw new AssertionError("The renderer must supply a bounded highlight request");
        }

        @Override public HighlightResult highlightResult(DocumentView document, int lineIndex, String line,
                                                        HighlightRequest request) {
            this.document = document;
            return HighlightResult.completed(List.of());
        }
    }
}
