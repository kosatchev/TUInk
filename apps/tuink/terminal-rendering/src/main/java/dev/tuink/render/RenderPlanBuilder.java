package dev.tuink.render;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.TextModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds render data from current model and state.
 */
public class RenderPlanBuilder {
    private final LineHighlighter highlighter;

    public RenderPlanBuilder(LineHighlighter highlighter) {
        this.highlighter = highlighter;
    }

    public RenderPlan build(
        TextModel model,
        EditorState state,
        int rows,
        int cols,
        String topBarText,
        String bottomBarText
    ) {
        int safeRows = Math.max(rows, 1);
        int safeCols = Math.max(cols, 1);
        int contentRows = Math.max(0, safeRows - 2);
        int scrollbarWidth = safeCols >= 8 ? 1 : 0;
        int gutterWidth = Math.max(3, String.valueOf(Math.max(model.lineCount(), 1)).length() + 1);
        int textWidth = Math.max(0, safeCols - gutterWidth - scrollbarWidth);

        List<LineVisual> lines = new ArrayList<>(contentRows);
        for (int row = 0; row < contentRows; row++) {
            int lineIndex = state.getTopLine() + row;
            if (lineIndex < model.lineCount()) {
                String line = model.getLine(lineIndex);
                String visibleText = clip(line, state.getLeftColumn(), textWidth);
                List<StyleSpan> spans = clipSpans(highlighter.highlight(line), state.getLeftColumn(), visibleText.length());
                String gutter = String.format("%" + (gutterWidth - 1) + "d ", lineIndex + 1);
                lines.add(new LineVisual(gutter, visibleText, spans));
            } else {
                lines.add(new LineVisual(pad("~", gutterWidth), "", List.of()));
            }
        }

        Position cursor = model.clamp(state.getCursor());
        int cursorRow = cursor.line() - state.getTopLine();
        int cursorCol = gutterWidth + Math.max(0, cursor.col() - state.getLeftColumn());
        int maxCursorCol = Math.max(gutterWidth, safeCols - 1 - scrollbarWidth);
        cursorRow = Math.max(0, Math.min(cursorRow, Math.max(contentRows - 1, 0)));
        cursorCol = Math.max(gutterWidth, Math.min(cursorCol, maxCursorCol));

        return new RenderPlan(
            lines,
            topBarText,
            bottomBarText,
            cursorRow,
            cursorCol,
            contentRows,
            state.getTopLine(),
            model.lineCount(),
            safeCols,
            gutterWidth,
            scrollbarWidth
        );
    }

    private static String clip(String line, int leftColumn, int maxWidth) {
        if (maxWidth <= 0 || leftColumn >= line.length()) {
            return "";
        }
        int start = Math.max(0, leftColumn);
        int end = Math.min(line.length(), start + maxWidth);
        return line.substring(start, end);
    }

    private static List<StyleSpan> clipSpans(List<StyleSpan> spans, int leftColumn, int visibleLength) {
        if (visibleLength <= 0 || spans.isEmpty()) {
            return List.of();
        }

        List<StyleSpan> clipped = new ArrayList<>();
        int windowStart = Math.max(0, leftColumn);
        int windowEnd = windowStart + visibleLength;

        for (StyleSpan span : spans) {
            int start = Math.max(span.start(), windowStart);
            int end = Math.min(span.end(), windowEnd);
            if (end > start) {
                clipped.add(new StyleSpan(start - windowStart, end - windowStart, span.style()));
            }
        }
        return clipped;
    }

    private static String pad(String text, int width) {
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        return text + " ".repeat(width - text.length());
    }
}
