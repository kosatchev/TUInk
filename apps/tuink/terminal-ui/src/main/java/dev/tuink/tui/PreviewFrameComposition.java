package dev.tuink.tui;

import java.util.Objects;
import dev.tuink.tui.ui.SearchBarView;

/** Exact owner-thread preview frame; contains no mutable editor document or source render plan. */
record PreviewFrameComposition(
    int rows,
    int cols,
    int contentTopRow,
    MarkdownPreviewState.Placement preview,
    FrameComposition.Surface surface,
    FrameComposition.Layers layers,
    SearchBarView.Placement search,
    boolean fullRedraw
) {
    PreviewFrameComposition {
        if (rows < 1 || cols < 1 || contentTopRow < 0 || contentTopRow > rows) {
            throw new IllegalArgumentException("Invalid preview frame dimensions");
        }
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(layers, "layers");
        layers.validatePromptOwner(search != null);
        layers.validateGeometry(rows, cols, contentTopRow, surface.inlinePrompt().height(), surface.textLayout());
    }
}
