package dev.tuink.core;

import dev.tuink.unicode.GraphemeText;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Terminal-independent logical layout for headless editor sessions.
 *
 * <p>Each extended grapheme occupies one logical unit and tabs advance to the
 * next configured tab stop. Frontends with pixel or terminal-cell metrics
 * provide their own {@link EditorTextLayout} implementation.</p>
 */
public final class LogicalEditorTextLayout implements EditorTextLayout {
    private final TextModel model;

    public LogicalEditorTextLayout(TextModel model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    @Override
    public TextModel model() {
        return model;
    }

    @Override
    public int visualWidth(int line, int tabWidth) {
        return metrics(line, tabWidth).visualWidth();
    }

    @Override
    public int visualColumnAt(int line, int textOffset, int tabWidth) {
        return metrics(line, tabWidth).visualColumnAt(textOffset);
    }

    @Override
    public int textOffsetAtVisualColumn(int line, int visualColumn, int tabWidth, Bias bias) {
        return metrics(line, tabWidth).textOffsetAtVisualColumn(visualColumn, bias);
    }

    @Override
    public List<Integer> wrapStarts(int line, int width, boolean wrapByWords, int tabWidth) {
        Metrics metrics = metrics(line, tabWidth);
        if (metrics.text().isEmpty() || width <= 0) {
            return List.of(0);
        }
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        int segmentStart = 0;
        int lastWhitespaceEnd = -1;
        int index = 0;
        while (index < metrics.graphemes().graphemeCount()) {
            int nextVisual = metrics.visualAtBoundary(index + 1);
            int segmentVisual = metrics.visualAtBoundary(segmentStart);
            if (nextVisual - segmentVisual > width && index > segmentStart) {
                int breakIndex = wrapByWords && lastWhitespaceEnd > segmentStart
                    ? lastWhitespaceEnd
                    : index;
                starts.add(metrics.graphemes().start(breakIndex));
                segmentStart = breakIndex;
                lastWhitespaceEnd = -1;
                index = breakIndex;
                continue;
            }
            index++;
            if (metrics.graphemes().isWhitespace(index - 1)) {
                lastWhitespaceEnd = index;
            }
            if (nextVisual - segmentVisual > width && index == segmentStart + 1) {
                if (index < metrics.graphemes().graphemeCount()) {
                    starts.add(metrics.graphemes().start(index));
                }
                segmentStart = index;
                lastWhitespaceEnd = -1;
            }
        }
        int lastStart = starts.get(starts.size() - 1);
        if (metrics.visualWidth() - metrics.visualColumnAt(lastStart) >= width) {
            starts.add(metrics.text().length());
        }
        return List.copyOf(starts);
    }

    @Override
    public int wrappedRowsBefore(
        int lineIndexExclusive,
        int width,
        boolean wrapByWords,
        int tabWidth
    ) {
        int rows = 0;
        int limit = Math.max(0, Math.min(lineIndexExclusive, model.lineCount()));
        for (int line = 0; line < limit; line++) {
            rows += wrapStarts(line, width, wrapByWords, tabWidth).size();
        }
        return rows;
    }

    @Override
    public int totalWrappedRows(int width, boolean wrapByWords, int tabWidth) {
        return wrappedRowsBefore(model.lineCount(), width, wrapByWords, tabWidth);
    }

    @Override
    public int lineIndexFromWrappedOffset(
        int wrappedOffset,
        int width,
        boolean wrapByWords,
        int tabWidth
    ) {
        int remaining = Math.max(0, wrappedOffset);
        for (int line = 0; line < model.lineCount(); line++) {
            int rows = wrapStarts(line, width, wrapByWords, tabWidth).size();
            if (remaining < rows) {
                return line;
            }
            remaining -= rows;
        }
        return Math.max(0, model.lineCount() - 1);
    }

    @Override
    public int maxVisualLineWidth(int tabWidth) {
        int max = 0;
        for (int line = 0; line < model.lineCount(); line++) {
            max = Math.max(max, visualWidth(line, tabWidth));
        }
        return max;
    }

    private Metrics metrics(int line, int tabWidth) {
        int safeLine = Math.max(0, Math.min(line, model.lineCount() - 1));
        String text = model.getLine(safeLine);
        GraphemeText graphemes = GraphemeText.of(text);
        int[] visual = new int[graphemes.graphemeCount() + 1];
        int safeTabWidth = Math.max(1, tabWidth);
        for (int index = 0; index < graphemes.graphemeCount(); index++) {
            int width = graphemes.isTab(index)
                ? safeTabWidth - visual[index] % safeTabWidth
                : 1;
            visual[index + 1] = visual[index] + width;
        }
        return new Metrics(text, graphemes, visual);
    }

    private record Metrics(String text, GraphemeText graphemes, int[] visual) {
        private int visualWidth() {
            return visual[visual.length - 1];
        }

        private int visualAtBoundary(int boundary) {
            return visual[Math.max(0, Math.min(boundary, visual.length - 1))];
        }

        private int visualColumnAt(int textOffset) {
            int offset = graphemes.floorBoundary(textOffset);
            for (int index = 0; index < graphemes.graphemeCount(); index++) {
                if (graphemes.start(index) == offset) {
                    return visual[index];
                }
            }
            return offset >= text.length() ? visualWidth() : 0;
        }

        private int textOffsetAtVisualColumn(int column, Bias requestedBias) {
            int target = Math.max(0, column);
            Bias bias = requestedBias == null ? Bias.NEAREST : requestedBias;
            for (int index = 0; index < graphemes.graphemeCount(); index++) {
                int start = visual[index];
                int end = visual[index + 1];
                if (target < end) {
                    if (bias == Bias.CEIL
                        || (bias == Bias.NEAREST && target - start > end - target)) {
                        return graphemes.end(index);
                    }
                    return graphemes.start(index);
                }
                if (target == end) {
                    return graphemes.end(index);
                }
            }
            return text.length();
        }
    }
}
