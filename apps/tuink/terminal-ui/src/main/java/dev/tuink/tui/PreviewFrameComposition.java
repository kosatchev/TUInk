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
        int owners = (layers.saveAsMode() ? 1 : 0) + (layers.confirm() != null ? 1 : 0)
            + (layers.openFile().visible() ? 1 : 0) + (layers.help() != null ? 1 : 0)
            + (layers.format() != null ? 1 : 0);
        if (owners > 1) throw new IllegalArgumentException("Only one prompt or modal frame owner is allowed");
    }
}
