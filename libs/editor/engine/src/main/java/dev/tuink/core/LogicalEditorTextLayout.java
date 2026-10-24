package dev.tuink.core;

import dev.tuink.unicode.GraphemeText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final Map<Integer, Metrics> metricCache = new LinkedHashMap<>(16, 0.75f, true);

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
        if (metrics.text().length() == 0 || width <= 0) {
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
        LineText text = model.lineText(safeLine);
        int safeTabWidth = Math.max(1, tabWidth);
        Metrics cached = metricCache.get(safeLine);
        if (cached != null && cached.text() == text && cached.tabWidth() == safeTabWidth) return cached;
        GraphemeText graphemes;
        LogicalColumnIndex columns;
        LineText.Edit edit = text.edit();
        if (cached != null && cached.tabWidth() == safeTabWidth && edit != null
            && edit.sourceIdentity() == cached.text().identity()) {
            graphemes = cached.graphemes().updated(text, edit.start(), edit.removedLength(), edit.insertedLength());
            GraphemeText.ResegmentedRange range = graphemes.resegmentedRange();
            LogicalColumnIndex changed = LogicalColumnIndex.build(graphemes, range.start(), range.insertedEnd(), safeTabWidth);
            columns = cached.columns().updated(changed,
                cached.graphemes().boundaryIndexAtOrBefore(range.start()),
                cached.graphemes().boundaryIndexAtOrBefore(range.removedEnd()));
        } else {
            graphemes = GraphemeText.of(text);
            columns = LogicalColumnIndex.build(graphemes, 0, text.length(), safeTabWidth);
        }
        Metrics result = new Metrics(text, graphemes, columns, safeTabWidth);
        metricCache.put(safeLine, result);
        if (metricCache.size() > 256) metricCache.remove(metricCache.keySet().iterator().next());
        return result;
    }

    private record Metrics(LineText text, GraphemeText graphemes, LogicalColumnIndex columns, int tabWidth) {
        private int visualWidth() {
            return columns.column(graphemes.graphemeCount());
        }

        private int visualAtBoundary(int boundary) {
            return columns.column(Math.max(0, Math.min(boundary, graphemes.graphemeCount())));
        }

        private int visualColumnAt(int textOffset) {
            return columns.column(graphemes.boundaryIndexAtOrBefore(textOffset));
        }

        private int textOffsetAtVisualColumn(int column, Bias requestedBias) {
            int target = Math.max(0, column);
            Bias bias = requestedBias == null ? Bias.NEAREST : requestedBias;
            int index = columns.floorColumn(target);
            if (index == graphemes.graphemeCount()) return text.length();
            int start = columns.column(index);
            if (start == target) return graphemes.start(index);
            int end = columns.column(index + 1);
            return bias == Bias.CEIL || bias == Bias.NEAREST && target - start > end - target
                ? graphemes.end(index) : graphemes.start(index);
        }
    }
}
