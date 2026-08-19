package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.DocumentView;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextualLineHighlighterBoundaryTest {
    @Test
    void renderPassesAStableReadOnlyCapabilityInsteadOfTheMutableModel() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("first"));
        CapturingHighlighter highlighter = new CapturingHighlighter();
        RenderPlanBuilder builder = new RenderPlanBuilder(
            highlighter,
            new TerminalEditorTextLayout(model)
        );

        builder.build(model, new EditorState(), 4, 20, "top", "bottom");
        DocumentView firstView = highlighter.document;

        assertNotNull(firstView);
        assertFalse(firstView instanceof TextModel);
        assertEquals(model.revision(), firstView.revision());
        assertEquals("first", firstView.line(0));

        model.insertChar(new Position(0, 5), '!');
        builder.build(model, new EditorState(), 4, 20, "top", "bottom");

        assertSame(firstView, highlighter.document);
        assertEquals(model.revision(), highlighter.document.revision());
        assertEquals("first!", highlighter.document.line(0));
    }

    private static final class CapturingHighlighter implements ContextualLineHighlighter {
        private DocumentView document;

        @Override
        public List<StyleSpan> highlight(String line) {
            return List.of();
        }

        @Override
        public List<StyleSpan> highlight(DocumentView document, int lineIndex, String line) {
            this.document = document;
            return List.of();
        }
    }
}
