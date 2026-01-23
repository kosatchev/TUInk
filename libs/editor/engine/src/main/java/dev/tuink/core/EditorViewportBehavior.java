package dev.tuink.core;

/**
 * UI-agnostic behavior for mapping viewport coordinates and applying viewport scroll.
 */
public final class EditorViewportBehavior {
    private EditorViewportBehavior() {
    }

    public static Position pointerToTextPosition(
        TextModel model,
        EditorState state,
        int contentTopRow,
        int contentRows,
        int mouseRow,
        int mouseCol,
        int gutterWidth,
        int tabWidth
    ) {
        int top = Math.max(1, contentTopRow);
        if (mouseRow < top || mouseRow >= top + contentRows) {
            return null;
        }
        int line = state.getTopLine() + (mouseRow - top);
        line = Math.max(0, Math.min(line, model.lineCount() - 1));
        int targetVisualCol = mouseCol < gutterWidth ? 0 : mouseCol - gutterWidth;
        int lineLeftColumn = visualToTextColumn(
            model.getLine(line),
            0,
            Math.max(0, state.getLeftVisualColumn()),
            tabWidth
        );
        int col = visualToTextColumn(
            model.getLine(line),
            lineLeftColumn,
            Math.max(0, targetVisualCol),
            tabWidth
        );
        return model.clamp(new Position(line, Math.max(0, col)));
    }

    public static int visualToTextColumn(String line, int leftColumn, int targetVisualCol, int tabWidth) {
        return ViewportMetrics.visualToTextColumn(line, leftColumn, targetVisualCol, tabWidth);
    }

    public static void applyVerticalScroll(EditorState state, TextModel model, int targetTopLine) {
        int oldTop = state.getTopLine();
        int relativeLine = state.getCursor().line() - oldTop;
        int targetLine = Math.max(0, Math.min(targetTopLine + relativeLine, model.lineCount() - 1));
        int targetCol = Math.min(state.getCursor().col(), model.getLine(targetLine).length());
        state.setTopLine(Math.max(0, targetTopLine));
        state.setCursor(new Position(targetLine, targetCol));
    }

    public static void applyHorizontalScroll(EditorState state, TextModel model, int targetLeftColumn) {
        int next = Math.max(0, targetLeftColumn);
        state.setLeftVisualColumn(next);
        state.setLeftColumn(next);
    }

    public static void applyHorizontalScrollVisual(
        EditorState state,
        TextModel model,
        int targetLeftVisualColumn,
        int tabWidth
    ) {
        int safeTabWidth = tabWidth <= 2 ? 2 : 4;
        int line = Math.max(0, Math.min(state.getCursor().line(), model.lineCount() - 1));
        String lineText = model.getLine(line);
        int targetLeftColumn = visualToTextColumn(
            lineText,
            0,
            Math.max(0, targetLeftVisualColumn),
            safeTabWidth
        );
        state.setLeftVisualColumn(Math.max(0, targetLeftVisualColumn));
        state.setLeftColumn(Math.max(0, targetLeftColumn));
    }
}
