package dev.tuink.terminal.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Stateless terminal-line wrapping over precomputed grapheme metrics. */
public final class TerminalLineLayout {
    private TerminalLineLayout() {
    }

    /**
     * Returns UTF-16 offsets at which visual rows start.
     *
     * <p>Tab stops remain absolute to the complete line, and no returned
     * offset splits an extended grapheme cluster.</p>
     */
    public static List<Integer> wrapStarts(
        TerminalText.Metrics metrics,
        int maxVisualWidth,
        boolean wrapByWords
    ) {
        Objects.requireNonNull(metrics, "metrics");
        if (metrics.text().isEmpty() || maxVisualWidth <= 0) {
            return List.of(0);
        }
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        int segmentStartIndex = 0;
        int segmentStartVisual = 0;
        int visual = 0;
        int lastWhitespaceEndIndex = -1;
        int index = 0;

        while (index < metrics.graphemeCount()) {
            int advance = metrics.visualColumnAtBoundary(index + 1)
                - metrics.visualColumnAtBoundary(index);
            if (visual + advance - segmentStartVisual > maxVisualWidth && index > segmentStartIndex) {
                int breakIndex = wrapByWords && lastWhitespaceEndIndex > segmentStartIndex
                    ? lastWhitespaceEndIndex
                    : index;
                int startOffset = metrics.start(breakIndex);
                starts.add(startOffset);
                segmentStartIndex = breakIndex;
                segmentStartVisual = metrics.visualColumnAtBoundary(breakIndex);
                visual = segmentStartVisual;
                lastWhitespaceEndIndex = -1;
                index = breakIndex;
                continue;
            }

            visual += advance;
            index++;
            if (metrics.isWhitespace(index - 1)) {
                lastWhitespaceEndIndex = index;
            }
            if (visual - segmentStartVisual > maxVisualWidth && index == segmentStartIndex + 1) {
                if (index < metrics.graphemeCount()) {
                    starts.add(metrics.start(index));
                }
                segmentStartIndex = index;
                segmentStartVisual = visual;
                lastWhitespaceEndIndex = -1;
            }
        }
        return List.copyOf(starts);
    }
}
