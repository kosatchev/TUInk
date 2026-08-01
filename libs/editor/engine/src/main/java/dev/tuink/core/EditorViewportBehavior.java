package dev.tuink.core;

import dev.tuink.unicode.GraphemeText;

/**
 * UI-agnostic behavior for mapping viewport coordinates and applying viewport scroll.
 */
public final class EditorViewportBehavior {
    private EditorViewportBehavior() {
    }

    public record WrapTop(int line, int segmentOffset) {
        public WrapTop {
            line = Math.max(0, line);
            segmentOffset = Math.max(0, segmentOffset);
        }
    }

    public static Position pointerToTextPosition(
        TextModel model,
        EditorTextLayout textLayout,
        EditorState state,
        int contentTopRow,
        int contentRows,
        int mouseRow,
        int mouseCol,
        int gutterWidth,
        int tabWidth
    ) {
        requireBoundModel(model, textLayout);
        int top = Math.max(1, contentTopRow);
        if (mouseRow < top || mouseRow >= top + contentRows) {
            return null;
        }
        int line = state.getTopLine() + (mouseRow - top);
        line = Math.max(0, Math.min(line, model.lineCount() - 1));
        int targetVisualCol = mouseCol < gutterWidth ? 0 : mouseCol - gutterWidth;
        int col = visualToTextColumn(
            textLayout,
            line,
            0,
            Math.max(0, state.getLeftVisualColumn() + targetVisualCol),
            tabWidth
        );
        return model.clamp(new Position(line, Math.max(0, col)));
    }

    public static int visualToTextColumn(
        EditorTextLayout textLayout,
        int line,
        int leftColumn,
        int targetVisualCol,
        int tabWidth
    ) {
        int leftVisual = textLayout.visualColumnAt(line, leftColumn, tabWidth);
        return textLayout.textOffsetAtVisualColumn(
            line,
            leftVisual + Math.max(0, targetVisualCol),
            tabWidth,
            EditorTextLayout.Bias.NEAREST
        );
    }

    public static void applyVerticalScroll(EditorState state, TextModel model, int targetTopLine) {
        int oldTop = state.getTopLine();
        int relativeLine = state.getCursor().line() - oldTop;
        int targetLine = Math.max(0, Math.min(targetTopLine + relativeLine, model.lineCount() - 1));
        String targetText = model.getLine(targetLine);
        int targetCol = GraphemeText.floorBoundary(
            targetText,
            Math.min(state.getCursor().col(), targetText.length())
        );
        state.setTopLine(Math.max(0, targetTopLine));
        state.setCursor(new Position(targetLine, targetCol));
    }

    public static void applyVerticalScrollKeepCursor(EditorState state, TextModel model, int targetTopLine) {
        int lineCount = Math.max(1, model.lineCount());
        int clampedTop = Math.max(0, Math.min(targetTopLine, lineCount - 1));
        state.setTopLine(clampedTop);

        Position cursor = state.getCursor();
        int clampedLine = Math.max(0, Math.min(cursor.line(), lineCount - 1));
        String clampedText = model.getLine(clampedLine);
        int clampedCol = Math.min(cursor.col(), clampedText.length());
        if (clampedLine != cursor.line()) {
            clampedCol = GraphemeText.floorBoundary(clampedText, clampedCol);
        }
        if (clampedLine != cursor.line() || clampedCol != cursor.col()) {
            state.setCursor(new Position(clampedLine, clampedCol));
        }
    }

    public static void applyHorizontalScroll(EditorState state, TextModel model, int targetLeftColumn) {
        int next = Math.max(0, targetLeftColumn);
        state.setLeftVisualColumn(next);
        state.setLeftColumn(next);
    }

    public static void applyHorizontalScrollVisual(
        EditorState state,
        TextModel model,
        EditorTextLayout textLayout,
        int targetLeftVisualColumn,
        int tabWidth
    ) {
        requireBoundModel(model, textLayout);
        int safeTabWidth = tabWidth <= 2 ? 2 : 4;
        int line = Math.max(0, Math.min(state.getCursor().line(), model.lineCount() - 1));
        int targetLeftColumn = textLayout.textOffsetAtVisualColumn(
            line,
            Math.max(0, targetLeftVisualColumn),
            safeTabWidth,
            EditorTextLayout.Bias.FLOOR
        );
        state.setLeftVisualColumn(Math.max(0, targetLeftVisualColumn));
        state.setLeftColumn(Math.max(0, targetLeftColumn));
    }

    public static void applyWrapTop(EditorState state, WrapTop top) {
        if (top == null) {
            state.setTopWrapSegment(0);
            return;
        }
        state.setTopLine(top.line());
        state.setTopWrapSegment(top.segmentOffset());
    }

    public static WrapTop normalizeWrapTop(
        TextModel model,
        EditorTextLayout textLayout,
        int topLine,
        int topWrapSegment,
        int width,
        boolean wrapByWords,
        int tabWidth
    ) {
        requireBoundModel(model, textLayout);
        int lineCount = Math.max(1, model.lineCount());
        int line = Math.max(0, Math.min(topLine, lineCount - 1));
        int segment = Math.max(0, topWrapSegment);
        while (line < lineCount) {
            int segments = textLayout.wrapStarts(line, width, wrapByWords, tabWidth).size();
            if (segment < segments) {
                return new WrapTop(line, segment);
            }
            segment -= segments;
            line++;
        }
        return new WrapTop(Math.max(0, lineCount - 1), 0);
    }

    public static WrapTop wrapTopFromWrappedOffset(
        TextModel model,
        EditorTextLayout textLayout,
        int wrappedOffset,
        int width,
        boolean wrapByWords,
        int tabWidth
    ) {
        requireBoundModel(model, textLayout);
        int line = textLayout.lineIndexFromWrappedOffset(wrappedOffset, width, wrapByWords, tabWidth);
        int lineWrappedStart = textLayout.wrappedRowsBefore(line, width, wrapByWords, tabWidth);
        int segmentOffset = Math.max(0, wrappedOffset - lineWrappedStart);
        return new WrapTop(line, segmentOffset);
    }

    public static int wrappedOffsetForWrapTop(
        TextModel model,
        EditorTextLayout textLayout,
        WrapTop top,
        int width,
        boolean wrapByWords,
        int tabWidth
    ) {
        requireBoundModel(model, textLayout);
        if (top == null) {
            return 0;
        }
        WrapTop normalized = normalizeWrapTop(
            model,
            textLayout,
            top.line(),
            top.segmentOffset(),
            width,
            wrapByWords,
            tabWidth
        );
        return textLayout.wrappedRowsBefore(normalized.line(), width, wrapByWords, tabWidth)
            + normalized.segmentOffset();
    }

    public static WrapTop wrapTopFromWrappedOffsetNear(
        TextModel model,
        EditorTextLayout textLayout,
        int currentLine,
        int currentSegmentOffset,
        int currentWrappedOffset,
        int targetWrappedOffset,
        int width,
        boolean wrapByWords,
        int tabWidth
    ) {
        requireBoundModel(model, textLayout);
        int lineCount = model.lineCount();
        if (lineCount <= 0) {
            return new WrapTop(0, 0);
        }
        int line = Math.max(0, Math.min(currentLine, lineCount - 1));
        int offset = Math.max(0, currentWrappedOffset);
        int segmentOffset = Math.max(0, currentSegmentOffset);
        int target = Math.max(0, targetWrappedOffset);

        if (segmentOffset > 0) {
            int segments = textLayout.wrapStarts(line, width, wrapByWords, tabWidth).size();
            while (line < lineCount - 1 && segmentOffset >= segments) {
                offset += segments;
                segmentOffset -= segments;
                line++;
                segments = textLayout.wrapStarts(line, width, wrapByWords, tabWidth).size();
            }
        }

        if (target >= offset) {
            while (line < lineCount) {
                int segments = textLayout.wrapStarts(line, width, wrapByWords, tabWidth).size();
                int nextOffset = offset + segments;
                if (target < nextOffset || line == lineCount - 1) {
                    return new WrapTop(line, Math.max(0, target - offset));
                }
                offset = nextOffset;
                line++;
            }
            return new WrapTop(lineCount - 1, 0);
        }

        while (line > 0) {
            int prevLine = line - 1;
            int prevSegments = textLayout.wrapStarts(prevLine, width, wrapByWords, tabWidth).size();
            int prevOffset = Math.max(0, offset - prevSegments);
            if (target >= prevOffset) {
                return new WrapTop(prevLine, Math.max(0, target - prevOffset));
            }
            offset = prevOffset;
            line = prevLine;
        }
        return new WrapTop(0, 0);
    }

    private static void requireBoundModel(TextModel model, EditorTextLayout textLayout) {
        if (model == null || textLayout == null || textLayout.model() != model) {
            throw new IllegalArgumentException("textLayout must be bound to model");
        }
    }

}
