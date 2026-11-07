package dev.tuink.render;

import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.TextModel;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.scroll.ScrollLayout;
import java.util.Objects;

/** A single authoritative layout calculation for the editor viewport. */
public record EditorLayout(
    int rows,
    int cols,
    int contentTopRow,
    int overlayRows,
    int gutterWidth,
    int contentRows,
    int contentColumns,
    int scrollbarWidth,
    int horizontalScrollbarHeight,
    ScrollLayout scrollLayout,
    Rect editorBounds
) {
    public EditorLayout {
        rows = Math.max(1, rows);
        cols = Math.max(1, cols);
        contentTopRow = Math.max(1, contentTopRow);
        overlayRows = Math.max(0, overlayRows);
        gutterWidth = Math.max(0, gutterWidth);
        contentRows = Math.max(0, contentRows);
        contentColumns = Math.max(0, contentColumns);
        scrollbarWidth = Math.max(0, scrollbarWidth);
        horizontalScrollbarHeight = Math.max(0, horizontalScrollbarHeight);
        Objects.requireNonNull(scrollLayout, "scrollLayout");
        Objects.requireNonNull(editorBounds, "editorBounds");
    }

    /** Compatibility constructor for a full-width editor surface. */
    public EditorLayout(int rows, int cols, int contentTopRow, int overlayRows, int gutterWidth,
        int contentRows, int contentColumns, int scrollbarWidth, int horizontalScrollbarHeight,
        ScrollLayout scrollLayout) {
        this(rows, cols, contentTopRow, overlayRows, gutterWidth, contentRows, contentColumns,
            scrollbarWidth, horizontalScrollbarHeight, scrollLayout,
            new Rect(0, Math.max(1, contentTopRow), Math.max(1, cols),
                Math.max(0, contentRows) + Math.max(0, horizontalScrollbarHeight)));
    }

    public static EditorLayout resolve(
        TextModel model,
        EditorTextLayout textLayout,
        EditorState state,
        int rows,
        int cols,
        int contentTopRow,
        int overlayRows,
        boolean wrapLines,
        boolean wrapByWords,
        int tabWidth
    ) {
        return resolve(model, textLayout, state, rows, cols, contentTopRow, overlayRows,
            wrapLines, wrapByWords, tabWidth, new Rect(0, 0, Math.max(1, cols), Math.max(1, rows)));
    }

    /** Resolves an editor pane while keeping rows and columns in terminal coordinates. */
    public static EditorLayout resolve(
        TextModel model,
        EditorTextLayout textLayout,
        EditorState state,
        int rows,
        int cols,
        int contentTopRow,
        int overlayRows,
        boolean wrapLines,
        boolean wrapByWords,
        int tabWidth,
        Rect editorSurface
    ) {
        if (textLayout == null || textLayout.model() != model) {
            throw new IllegalArgumentException("textLayout must be bound to model");
        }
        int safeRows = Math.max(1, rows);
        int safeCols = Math.max(1, cols);
        int top = Math.max(1, contentTopRow);
        int overlays = Math.max(0, overlayRows);
        int availableRows = Math.max(0, safeRows - top - 1 - overlays);
        Rect editor = Objects.requireNonNull(editorSurface, "editorSurface")
            .intersection(new Rect(0, top, safeCols, availableRows));
        int gutter = gutterWidth(model.lineCount());
        int visibleGutter = Math.min(gutter, editor.width());
        Rect viewport = new Rect(editor.x() + visibleGutter, editor.y(),
            Math.max(0, editor.width() - gutter), editor.height());
        Position cursor = model.clamp(state.getCursor());
        int maxWidth = wrapLines ? 0 : textLayout.maxVisualLineWidth(tabWidth);
        int cursorExtent = wrapLines
            ? 0
            : textLayout.visualColumnAt(cursor.line(), cursor.col(), tabWidth) + 1;
        int requiredColumns = Math.max(maxWidth, cursorExtent);

        ScrollLayout scrollLayout;
        if (wrapLines) {
            // The shared scrollbar policy negotiates the space. Document wrapping
            // then uses that content width, including its EOF insertion rows.
            int totalRows = textLayout.totalWrappedRows(Math.max(1, viewport.width()), wrapByWords, tabWidth);
            scrollLayout = ScrollLayout.vertical(viewport, totalRows);
            if (scrollLayout.contentBounds().width() != viewport.width()) {
                totalRows = textLayout.totalWrappedRows(
                    Math.max(1, scrollLayout.contentBounds().width()), wrapByWords, tabWidth);
                scrollLayout = ScrollLayout.vertical(viewport, totalRows);
            }
        } else {
            scrollLayout = ScrollLayout.calculate(viewport, model.lineCount(), requiredColumns, 0);
        }
        int verticalWidth = scrollLayout.verticalScrollbarBounds() == null ? 0 : 1;
        int horizontalHeight = scrollLayout.horizontalScrollbarBounds() == null ? 0 : 1;
        return new EditorLayout(
            safeRows,
            safeCols,
            editor.y(),
            overlays,
            gutter,
            scrollLayout.contentBounds().height(),
            scrollLayout.contentBounds().width(),
            verticalWidth,
            horizontalHeight,
            scrollLayout,
            editor
        );
    }

    private static int gutterWidth(int lineCount) {
        int digits = Math.max(1, String.valueOf(Math.max(1, lineCount)).length());
        return digits + 2;
    }
}
