package dev.tuink.tui.ui;

import dev.tuink.core.EditorEngine;
import dev.tuink.tui.KeyEvent;

/**
 * Tracks viewport interaction mode and applies cursor visibility policy.
 */
public final class ViewportController {
    private boolean manualHorizontalPan;
    private boolean manualVerticalPan;

    public void onInput(KeyEvent key) {
        if (!(key instanceof KeyEvent.MouseEvent)) {
            manualHorizontalPan = false;
            manualVerticalPan = false;
        }
    }

    public void onManualHorizontalPan() {
        manualHorizontalPan = true;
    }

    public void onManualVerticalPan() {
        manualVerticalPan = true;
    }

    public void onScrollbarInteraction(
        EditorView.ScrollPointerResult scrollPointer,
        int leftVisualBefore,
        int leftVisualAfter,
        int topLineBefore,
        int topLineAfter
    ) {
        if (scrollPointer == null || !scrollPointer.consumed()) {
            return;
        }
        if (scrollPointer.dragAxis() == EditorView.ScrollAxis.HORIZONTAL || leftVisualAfter != leftVisualBefore) {
            manualHorizontalPan = true;
        }
        if (scrollPointer.dragAxis() == EditorView.ScrollAxis.VERTICAL || topLineAfter != topLineBefore) {
            manualVerticalPan = true;
        }
    }

    public void ensureCursorVisible(EditorEngine engine, int contentRows, int contentCols) {
        if (manualVerticalPan) {
            return;
        }
        engine.ensureCursorVisible(contentRows, manualHorizontalPan ? -1 : contentCols);
    }
}
