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
    private final int contentTopRow;
    private boolean mouseSelecting;

    public EditorView() {
        this(1);
    }

    public EditorView(int contentTopRow) {
        this.contentTopRow = Math.max(1, contentTopRow);
    }

    public CursorPosition cursorFromPlan(RenderPlan plan) {
        return new CursorPosition(plan.cursorRow() + contentTopRow + 1, plan.cursorCol() + 1);
    }

    public CursorPosition cursorFromPlan(RenderPlan plan, int contentTopRow) {
        int top = Math.max(1, contentTopRow);
        return new CursorPosition(plan.cursorRow() + top + 1, plan.cursorCol() + 1);
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
        handleMousePress(mouse, model, state, plan, contentRows, engine, contentTopRow);
    }

    public void handleMousePress(
        KeyEvent.MouseEvent mouse,
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows,
        EditorEngine engine,
        int contentTopRow
    ) {
        Position hit = mouseToTextPosition(mouse, model, state, plan, contentRows, contentTopRow);
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
        handleMouseDrag(mouse, model, state, plan, contentRows, engine, contentTopRow);
    }

    public void handleMouseDrag(
        KeyEvent.MouseEvent mouse,
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows,
        EditorEngine engine,
        int contentTopRow
    ) {
        if (!mouseSelecting || mouse.button() != KeyEvent.MouseButton.LEFT) {
            return;
        }
        Position hit = mouseToTextPosition(mouse, model, state, plan, contentRows, contentTopRow);
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
        return mouseToTextPosition(mouse, model, state, plan, contentRows, contentTopRow);
    }

    public Position mouseToTextPosition(
        KeyEvent.MouseEvent mouse,
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows,
        int contentTopRow
    ) {
        int top = Math.max(1, contentTopRow);
        if (mouse.row() < top || mouse.row() >= top + contentRows) {
            return null;
        }
        int line = state.getTopLine() + (mouse.row() - top);
        line = Math.max(0, Math.min(line, model.lineCount() - 1));
        int col = mouse.col() < plan.gutterWidth()
            ? 0
            : state.getLeftColumn() + (mouse.col() - plan.gutterWidth());
        return model.clamp(new Position(line, Math.max(0, col)));
    }

    public record CursorPosition(int row, int col) {
    }
}
