package dev.tuink.tui.ui;

import dev.tuink.core.EditorEngine;
import dev.tuink.tui.input.EditorInput;
import dev.tuiframe.input.PointerEvent;

/**
 * Tracks viewport interaction mode and applies cursor visibility policy.
 */
public final class ViewportController {
    private boolean manualHorizontalPan;
    private boolean manualVerticalPan;

    public void onInput(EditorInput input) {
        if (input.command() != null || !(input.event() instanceof PointerEvent)) {
            manualHorizontalPan = false;
            manualVerticalPan = false;
        }
    }

    public void onManualVerticalPan() {
        manualVerticalPan = true;
    }

    public void onEditorTextInteraction() {
        // Returning focus to the text area should restore cursor-follow behavior.
        manualHorizontalPan = false;
        manualVerticalPan = false;
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

    public void ensureCursorVisible(
        EditorEngine engine,
        int contentRows,
        int contentCols,
        boolean wrapLines,
        boolean wrapByWords
    ) {
        if (manualVerticalPan) {
            return;
        }
        engine.ensureCursorVisible(
            contentRows,
            wrapLines ? contentCols : (manualHorizontalPan ? -1 : contentCols),
            wrapLines,
            wrapByWords
        );
    }
}
