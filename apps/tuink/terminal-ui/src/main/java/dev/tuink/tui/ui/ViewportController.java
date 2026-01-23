package dev.tuink.tui.ui;

import dev.tuink.core.EditorEngine;
import dev.tuink.tui.KeyEvent;

/**
 * Tracks viewport interaction mode and applies cursor visibility policy.
 */
public final class ViewportController {
    private boolean manualHorizontalPan;

    public void onInput(KeyEvent key) {
        if (!(key instanceof KeyEvent.MouseEvent)) {
            manualHorizontalPan = false;
        }
    }

    public void onManualHorizontalPan() {
        manualHorizontalPan = true;
    }

    public void onScrollbarInteraction(EditorView.ScrollPointerResult scrollPointer, int leftVisualBefore, int leftVisualAfter) {
        if (scrollPointer == null || !scrollPointer.consumed()) {
            return;
        }
        if (scrollPointer.dragAxis() == EditorView.ScrollAxis.HORIZONTAL || leftVisualAfter != leftVisualBefore) {
            manualHorizontalPan = true;
        }
    }

    public void ensureCursorVisible(EditorEngine engine, int contentRows, int contentCols) {
        engine.ensureCursorVisible(contentRows, manualHorizontalPan ? -1 : contentCols);
    }
}
