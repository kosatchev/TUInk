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
        int col = visualToTextColumn(
            model.getLine(line),
            state.getLeftColumn(),
            Math.max(0, targetVisualCol),
            tabWidth
        );
        return model.clamp(new Position(line, Math.max(0, col)));
    }

    public static int visualToTextColumn(String line, int leftColumn, int targetVisualCol, int tabWidth) {
        int safeTabWidth = Math.max(1, tabWidth);
        int len = line.length();
        int col = Math.max(0, Math.min(leftColumn, len));
        int visual = 0;

        while (col < len) {
            char ch = line.charAt(col);
            int advance = ch == '\t' ? safeTabWidth - (visual % safeTabWidth) : 1;
            int nextVisual = visual + advance;
            if (targetVisualCol < nextVisual) {
                int leftDist = targetVisualCol - visual;
                int rightDist = nextVisual - targetVisualCol;
                return leftDist <= rightDist ? col : col + 1;
            }
            if (targetVisualCol == nextVisual) {
                return col + 1;
            }
            visual = nextVisual;
            col++;
        }
        return len;
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
        int line = Math.max(0, Math.min(state.getCursor().line(), model.lineCount() - 1));
        int oldLeft = state.getLeftColumn();
        int relativeCol = state.getCursor().col() - oldLeft;
        int targetCol = Math.max(0, targetLeftColumn + relativeCol);
        targetCol = Math.min(targetCol, model.getLine(line).length());
        state.setLeftColumn(Math.max(0, targetLeftColumn));
        state.setCursor(new Position(line, targetCol));
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
        int currentLeftColumn = Math.max(0, Math.min(state.getLeftColumn(), lineText.length()));
        int cursorColumn = Math.max(0, Math.min(state.getCursor().col(), lineText.length()));
        int relativeVisual = visualColumn(lineText, currentLeftColumn, cursorColumn, safeTabWidth);
        int targetLeftColumn = visualToTextColumn(
            lineText,
            0,
            Math.max(0, targetLeftVisualColumn),
            safeTabWidth
        );
        int targetCursorCol = visualToTextColumn(
            lineText,
            targetLeftColumn,
            Math.max(0, relativeVisual),
            safeTabWidth
        );
        state.setLeftColumn(Math.max(0, targetLeftColumn));
        state.setCursor(new Position(line, targetCursorCol));
    }

    private static int visualColumn(String line, int startCol, int endCol, int tabWidth) {
        int safeTabWidth = Math.max(1, tabWidth);
        int start = Math.max(0, Math.min(startCol, line.length()));
        int end = Math.max(start, Math.min(endCol, line.length()));
        int visual = 0;
        for (int i = start; i < end; i++) {
            char ch = line.charAt(i);
            int advance = ch == '\t' ? safeTabWidth - (visual % safeTabWidth) : 1;
            visual += advance;
        }
        return visual;
    }
}
