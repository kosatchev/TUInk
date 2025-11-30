package dev.tuink.tui.ui;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.TextModel;
import dev.tuink.render.RenderPlan;
import dev.tuink.tui.KeyEvent;

/**
 * UI helper for the main editor text area.
 */
public final class EditorView {
    private boolean mouseSelecting;

    public CursorPosition cursorFromPlan(RenderPlan plan) {
        return new CursorPosition(plan.cursorRow() + 2, plan.cursorCol() + 1);
    }

    public void cancelMouseSelection() {
        mouseSelecting = false;
    }

    public void handleMousePress(
        KeyEvent.MouseEvent mouse,
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows,
        EditorEngine engine
    ) {
        Position hit = mouseToTextPosition(mouse, model, state, plan, contentRows);
        if (hit != null) {
            mouseSelecting = true;
            state.setSelectionAnchor(hit);
            state.setCursor(hit);
        } else {
            mouseSelecting = false;
        }
        engine.ensureCursorVisible(contentRows);
    }

    public void handleMouseDrag(
        KeyEvent.MouseEvent mouse,
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows,
        EditorEngine engine
    ) {
        if (!mouseSelecting || mouse.button() != KeyEvent.MouseButton.LEFT) {
            return;
        }
        Position hit = mouseToTextPosition(mouse, model, state, plan, contentRows);
        if (hit != null) {
            if (state.getSelectionAnchor() == null) {
                state.setSelectionAnchor(state.getCursor());
            }
            state.setCursor(hit);
            engine.ensureCursorVisible(contentRows);
        }
    }

    public void handleMouseRelease(EditorState state) {
        if (!mouseSelecting) {
            return;
        }
        mouseSelecting = false;
        if (state.getSelectionAnchor() != null && state.getSelectionAnchor().equals(state.getCursor())) {
            state.clearSelection();
        }
    }

    public Position mouseToTextPosition(
        KeyEvent.MouseEvent mouse,
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows
    ) {
        if (mouse.row() < 1 || mouse.row() > contentRows) {
            return null;
        }
        int line = state.getTopLine() + (mouse.row() - 1);
        line = Math.max(0, Math.min(line, model.lineCount() - 1));
        int col = mouse.col() < plan.gutterWidth()
            ? 0
            : state.getLeftColumn() + (mouse.col() - plan.gutterWidth());
        return model.clamp(new Position(line, Math.max(0, col)));
    }

    public record CursorPosition(int row, int col) {
    }
}
