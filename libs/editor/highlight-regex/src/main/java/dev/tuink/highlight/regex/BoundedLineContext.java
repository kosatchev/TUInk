package dev.tuink.highlight.regex;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentView;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/** Retains a small set of proven line-boundary states, never a state for every document line. */
final class BoundedLineContext<S> {
    private static final int MAX_STATES = 256;
    private static final int MAX_STATE_WEIGHT = 4096;
    private final S initial;
    private final ToIntFunction<S> weigh;
    private final Scanner<S> scanner;
    private final TreeMap<Integer, Entry<S>> states = new TreeMap<>();
    private DocumentId documentId;
    private long revision;
    private int firstLine;
    private int weight;

    BoundedLineContext(S initial, ToIntFunction<S> weigh, Scanner<S> scanner) {
        this.initial = initial;
        this.weigh = weigh;
        this.scanner = scanner;
    }

    record Resolved<S>(S state, int remainingCodeUnits, boolean complete, boolean advanced,
                       boolean permanentlyUnavailable) {}

    Resolved<S> before(DocumentView document, int target, int first, int maxCodeUnits, HighlightWork work) {
        if (!Objects.equals(documentId, document.documentId()) || firstLine != first) {
            states.clear();
            weight = 0;
            documentId = document.documentId();
            firstLine = first;
            revision = document.revision();
        } else if (revision != document.revision()) {
            int changed = Math.max(first, document.firstChangedLineSince(revision));
            var invalid = states.tailMap(changed, false);
            for (Entry<S> entry : invalid.values()) weight -= entry.weight();
            invalid.clear();
            revision = document.revision();
        }
        Map.Entry<Integer, Entry<S>> checkpoint = states.floorEntry(target);
        int line = checkpoint == null ? first : checkpoint.getKey();
        int startedAt = line;
        S state = checkpoint == null ? initial : checkpoint.getValue().state();
        int remaining = maxCodeUnits;
        try {
            while (line < target) {
                String text = document.line(line);
                // Empty lines still cost one unit: a distant line index cannot turn
                // a zero-character budget into an unbounded document traversal.
                long cost = (long) text.length() + 1;
                if (cost > remaining) return unresolved(startedAt, target, remaining);
                remaining -= (int) cost;
                work.spend(1);
                S after = scanner.scan(text, line, state, work);
                // A semantic/state limit cannot be resumed with a fresh frame's
                // work allowance. Keep earlier checkpoints, but do not schedule
                // idle retries for this permanently unavailable context.
                if (after == null) return new Resolved<>(null, remaining, false, false, true);
                state = after;
                remember(++line, state);
            }
        } catch (HighlightWork.Exhausted exhausted) {
            return unresolved(startedAt, target, remaining);
        }
        return new Resolved<>(state, remaining, true, retainedProgress(startedAt, target), false);
    }

    private Resolved<S> unresolved(int startedAt, int target, int remaining) {
        return new Resolved<>(null, remaining, false, retainedProgress(startedAt, target), false);
    }

    private boolean retainedProgress(int startedAt, int target) {
        Integer retained = states.floorKey(target);
        return retained != null && retained > startedAt;
    }

    void remember(int line, S state) {
        int cost = Math.max(1, weigh.applyAsInt(state));
        if (cost > MAX_STATE_WEIGHT) return;
        Entry<S> replaced = states.put(line, new Entry<>(state, cost));
        weight += cost - (replaced == null ? 0 : replaced.weight());
        while (states.size() > MAX_STATES || weight > MAX_STATE_WEIGHT) {
            // Keep checkpoints near the region being scanned. Always dropping
            // the earliest entry would immediately discard every new checkpoint
            // after a jump above an already populated cache.
            boolean discardLast = (long) states.lastKey() - line > (long) line - states.firstKey();
            weight -= (discardLast ? states.pollLastEntry() : states.pollFirstEntry()).getValue().weight();
        }
    }

    @FunctionalInterface
    interface Scanner<S> {
        /** A null result means that the complete line state could not be established. */
        S scan(String line, int lineIndex, S before, HighlightWork work);
    }

    private record Entry<S>(S state, int weight) {}
}
