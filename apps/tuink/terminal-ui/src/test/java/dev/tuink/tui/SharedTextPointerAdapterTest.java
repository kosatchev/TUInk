package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import static dev.tuink.tui.PreviewSelectionTest.*;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.scroll.ScrollLayout;
import dev.tuink.core.*;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.ui.EditorView;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SharedTextPointerAdapterTest {
    @Test void realSourceAndPreviewAdaptersSharePressDragReleaseAndShiftSelection() {
        String text = "alpha beta";
        TextModel model = new TextModel(new SimpleLineBuffer()); model.replaceLines(List.of(text));
        EditorState source = new EditorState();
        var layout = new TerminalEditorTextLayout(model);
        var engine = new EditorEngine(model, source, layout);
        var sourceView = new EditorView(() -> 1_000_000_000L);
        sourceView.bindCommands(TuiSessionTestSupport.commands(new TabSession(model, source, layout, engine)));
        var sourceBounds = new Rect(3, 1, 20, 3);
        var sourcePlan = new RenderPlan(List.of(), "", "", null, null, null, null, 0, 0, true,
            false, false, 3, 0, 1, 0, 0, 10, 23, 3, 0,
            new ScrollLayout(sourceBounds, sourceBounds, null, null, null));
        try (var preview = new MarkdownPreviewState()) {
            preview.active = true; preview.bounds = new Rect(5, 3, 20, 3);
            preview.result = result(text, line(text, 0, text.length(), 0, text.length()));
            preview.requestedId = ID; preview.requestedRevision = preview.result.document().revision();
            preview.requestedWidth = preview.result.columns();
            sourceView.handlePointer(event(sourceBounds, 1, PointerEvent.Kind.PRESS, false), engine, sourcePlan, 5, 23, 3, 1, false);
            assertTrue(preview.pointer(event(preview.bounds, 1, PointerEvent.Kind.PRESS, false)));
            sourceView.handlePointer(event(sourceBounds, 7, PointerEvent.Kind.DRAG, false), engine, sourcePlan, 5, 23, 3, 1, false);
            preview.pointer(event(preview.bounds, 7, PointerEvent.Kind.DRAG, false));
            assertEquals(source.getCursor().col(), preview.selection.caret());
            assertEquals(source.getSelectionAnchor().col(), preview.selectionStart());
            assertEquals(model.getTextRange(source.getSelectionAnchor(), source.getCursor()), preview.selectedText());
            sourceView.handlePointer(event(sourceBounds, 7, PointerEvent.Kind.RELEASE, false), engine, sourcePlan, 5, 23, 3, 1, false);
            assertTrue(preview.pointer(event(preview.bounds, 7, PointerEvent.Kind.RELEASE, false)));
            assertFalse(sourceView.pointerCaptureActive()); assertFalse(preview.pointerCaptureActive());
            sourceView.handlePointer(event(sourceBounds, 4, PointerEvent.Kind.PRESS, true), engine, sourcePlan, 5, 23, 3, 1, true);
            preview.pointer(event(preview.bounds, 4, PointerEvent.Kind.PRESS, true));
            assertEquals(source.getCursor().col(), preview.selection.caret());
            assertEquals(model.getTextRange(source.getSelectionAnchor(), source.getCursor()), preview.selectedText());
            sourceView.cancelMouseSelection(); preview.cancelPointerInteraction();
            assertFalse(sourceView.pointerCaptureActive()); assertFalse(preview.pointerCaptureActive());
            assertEquals(text, model.getLine(0)); assertFalse(engine.undo());
        }
    }
    private static PointerEvent event(Rect bounds, int column, PointerEvent.Kind kind, boolean shift) {
        return new PointerEvent(bounds.y(), bounds.x() + column, kind, PointerEvent.Button.LEFT,
            shift ? Set.of(KeyStroke.Modifier.SHIFT) : Set.of());
    }
}
