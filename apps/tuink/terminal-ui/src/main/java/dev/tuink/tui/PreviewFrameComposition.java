package dev.tuink.tui;

import java.util.Objects;

/** Exact owner-thread preview frame; contains no mutable editor document or source render plan. */
record PreviewFrameComposition(
    int rows,
    int cols,
    int contentTopRow,
    MarkdownPreviewState.Placement preview,
    FrameComposition.Surface surface,
    FrameComposition.Layers layers,
    boolean fullRedraw
) {
    PreviewFrameComposition {
        if (rows < 1 || cols < 1 || contentTopRow < 0 || contentTopRow > rows) {
            throw new IllegalArgumentException("Invalid preview frame dimensions");
        }
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(layers, "layers");
        layers.validatePromptOwner(false);
    }
}
