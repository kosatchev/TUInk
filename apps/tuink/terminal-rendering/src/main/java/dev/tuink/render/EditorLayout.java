package dev.tuink.render;

import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.TextModel;

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
    int horizontalScrollbarHeight
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
        if (textLayout == null || textLayout.model() != model) {
            throw new IllegalArgumentException("textLayout must be bound to model");
        }
        int safeRows = Math.max(1, rows);
        int safeCols = Math.max(1, cols);
        int top = Math.max(1, contentTopRow);
        int overlays = Math.max(0, overlayRows);
        int availableRows = Math.max(0, safeRows - top - 1 - overlays);
        int gutter = gutterWidth(model.lineCount());
        int verticalWidth = 0;
        int horizontalHeight = 0;
        int contentRows = 0;
        int contentColumns = 0;
        Position cursor = model.clamp(state.getCursor());
        int maxWidth = wrapLines ? 0 : textLayout.maxVisualLineWidth(tabWidth);
        int cursorExtent = wrapLines
            ? 0
            : textLayout.visualColumnAt(cursor.line(), cursor.col(), tabWidth) + 1;
        int requiredColumns = Math.max(maxWidth, cursorExtent);

        for (int pass = 0; pass < 4; pass++) {
            contentRows = Math.max(0, availableRows - horizontalHeight);
            contentColumns = Math.max(0, safeCols - gutter - verticalWidth);
            int layoutWidth = Math.max(1, contentColumns);
            int totalRows = wrapLines
                ? textLayout.totalWrappedRows(layoutWidth, wrapByWords, tabWidth)
                : model.lineCount();
            int topOffset = wrapLines
                ? textLayout.wrappedRowsBefore(state.getTopLine(), layoutWidth, wrapByWords, tabWidth)
                    + state.getTopWrapSegment()
                : state.getTopLine();
            int nextVertical = safeCols >= 8 && (topOffset > 0 || totalRows > contentRows) ? 1 : 0;
            int nextColumns = Math.max(0, safeCols - gutter - nextVertical);
            int nextHorizontal = !wrapLines
                && availableRows >= 2
                && nextColumns > 0
                && (state.getLeftVisualColumn() > 0 || requiredColumns > nextColumns)
                ? 1
                : 0;
            if (nextVertical == verticalWidth && nextHorizontal == horizontalHeight) {
                verticalWidth = nextVertical;
                horizontalHeight = nextHorizontal;
                break;
            }
            verticalWidth = nextVertical;
            horizontalHeight = nextHorizontal;
        }

        contentRows = Math.max(0, availableRows - horizontalHeight);
        contentColumns = Math.max(0, safeCols - gutter - verticalWidth);
        return new EditorLayout(
            safeRows,
            safeCols,
            top,
            overlays,
            gutter,
            contentRows,
            contentColumns,
            verticalWidth,
            horizontalHeight
        );
    }

    private static int gutterWidth(int lineCount) {
        int digits = Math.max(1, String.valueOf(Math.max(1, lineCount)).length());
        return digits + 2;
    }
}
