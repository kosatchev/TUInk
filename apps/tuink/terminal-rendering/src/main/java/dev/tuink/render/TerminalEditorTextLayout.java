package dev.tuink.render;

import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.TextModel;
import dev.tuink.core.LineText;
import dev.tuiframe.text.TerminalText;
import dev.tuiframe.text.TerminalTextLayoutCache;
import java.util.List;
import java.util.Objects;

/** Terminal-cell layout implementation bound to one editor text model. */
public final class TerminalEditorTextLayout implements EditorTextLayout {
    private final TextModel model;
    private final TerminalTextLayoutCache cache;

    public TerminalEditorTextLayout(TextModel model) {
        this.model = Objects.requireNonNull(model, "model");
        this.cache = new TerminalTextLayoutCache(new ModelLineSource(model), true);
    }

    @Override
    public TextModel model() {
        return model;
    }

    @Override
    public int visualWidth(int line, int tabWidth) {
        return cache.metrics(line, tabWidth).visualWidth();
    }

    @Override
    public int visualColumnAt(int line, int textOffset, int tabWidth) {
        return cache.metrics(line, tabWidth).visualColumnAt(textOffset);
    }

    @Override
    public int textOffsetAtVisualColumn(int line, int visualColumn, int tabWidth, Bias bias) {
        return cache.metrics(line, tabWidth).offsetForVisualColumn(
            visualColumn,
            terminalBias(bias)
        );
    }

    @Override
    public VisualRange visualRange(
        int line,
        int leftVisualColumn,
        int maxVisualWidth,
        int tabWidth
    ) {
        TerminalText.Metrics metrics = cache.metrics(line, tabWidth);
        int left = Math.max(0, leftVisualColumn);
        int start = metrics.offsetForVisualColumn(left, TerminalText.Bias.FLOOR);
        int sourceVisual = metrics.visualColumnAt(start);
        int right = (int) Math.min(Integer.MAX_VALUE, (long) left + Math.max(0, maxVisualWidth));
        int end = metrics.offsetForVisualColumn(right, TerminalText.Bias.CEIL);
        return new VisualRange(start, end, sourceVisual);
    }

    @Override
    public List<Integer> wrapStarts(int line, int width, boolean wrapByWords, int tabWidth) {
        return cache.wrapStarts(line, width, wrapByWords, tabWidth);
    }

    @Override
    public int wrappedRowsBefore(
        int lineIndexExclusive,
        int width,
        boolean wrapByWords,
        int tabWidth
    ) {
        return cache.wrappedRowsBefore(lineIndexExclusive, width, wrapByWords, tabWidth);
    }

    @Override
    public int totalWrappedRows(int width, boolean wrapByWords, int tabWidth) {
        return cache.totalWrappedRows(width, wrapByWords, tabWidth);
    }

    @Override
    public int lineIndexFromWrappedOffset(
        int wrappedOffset,
        int width,
        boolean wrapByWords,
        int tabWidth
    ) {
        return cache.lineIndexFromWrappedOffset(wrappedOffset, width, wrapByWords, tabWidth);
    }

    @Override
    public int maxVisualLineWidth(int tabWidth) {
        return cache.maxVisualLineWidth(tabWidth);
    }

    private static TerminalText.Bias terminalBias(Bias bias) {
        return switch (bias == null ? Bias.NEAREST : bias) {
            case FLOOR -> TerminalText.Bias.FLOOR;
            case NEAREST -> TerminalText.Bias.NEAREST;
            case CEIL -> TerminalText.Bias.CEIL;
        };
    }

    private record ModelLineSource(TextModel model) implements TerminalTextLayoutCache.LineSource {
        private ModelLineSource {
            Objects.requireNonNull(model, "model");
        }

        @Override
        public long cacheIdentity() {
            return model.cacheIdentity();
        }

        @Override
        public long revision() {
            return model.revision();
        }

        @Override
        public long contentEpoch() {
            return model.contentEpoch();
        }

        @Override
        public List<TerminalTextLayoutCache.LineChange> changesSince(long revision) {
            List<TextModel.LineChange> changes = model.lineChangesSince(revision);
            return changes == null ? null : changes.stream()
                .map(change -> new TerminalTextLayoutCache.LineChange(
                    change.firstLine(), change.removedLineCount(), change.addedLineCount()
                ))
                .toList();
        }

        @Override
        public int lineCount() {
            return model.lineCount();
        }

        @Override
        public String line(int index) {
            return model.getLine(index);
        }

        @Override public CharSequence lineSequence(int index) { return model.lineText(index); }
        @Override public long lineIdentity(int index) { return model.lineText(index).identity(); }
        @Override public TerminalTextLayoutCache.TextEdit textEdit(int index) {
            LineText.Edit edit = model.lineText(index).edit();
            return edit == null ? null : new TerminalTextLayoutCache.TextEdit(
                edit.sourceIdentity(), edit.start(), edit.removedLength(), edit.insertedLength());
        }
    }
}
