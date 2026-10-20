package dev.tuink.core;

import dev.tuink.editor.language.LanguageId;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.IntFunction;

/** Bounded, revision-aware lexical checkpoints used only by typing assists. */
final class IndentationContext {
    static final int MAX_SCAN_CODE_UNITS = 65_536;
    static final int MAX_SCAN_LINES = 1024;
    private static final int MAX_CHECKPOINTS = 256;
    private static final int MAX_STATE_WEIGHT = 4096;
    private final IntFunction<String> lines;
    private final TreeMap<Integer, IndentationLexer.State> checkpoints = new TreeMap<>();
    private long contentEpoch = Long.MIN_VALUE;
    private long revision = -1;
    private LanguageId language;
    private Path file;
    private int lineCount;
    private int retainedWeight;

    IndentationContext(IntFunction<String> lines) {
        this.lines = Objects.requireNonNull(lines, "lines");
    }

    long revision() {
        return revision;
    }

    void reset() {
        checkpoints.clear();
        retainedWeight = 0;
        contentEpoch = Long.MIN_VALUE;
        revision = -1;
    }

    void synchronize(long epoch, long nextRevision, int firstChangedLine, int count, Path nextFile) {
        LanguageId nextLanguage = LanguageProfiles.language(nextFile);
        if (contentEpoch != epoch || revision > nextRevision || language != nextLanguage) {
            checkpoints.clear();
            retainedWeight = 0;
        } else if (revision != nextRevision) {
            invalidateAfter(firstChangedLine);
        }
        invalidateAfter(count - 1);
        contentEpoch = epoch;
        revision = nextRevision;
        language = nextLanguage;
        file = nextFile;
        lineCount = count;
    }

    /** Null means that syntax cannot be established within this invocation's budget. */
    String codeBeforeCursor(int target, int column) {
        if (target < 0 || target >= lineCount) {
            return null;
        }
        String text = lines.apply(target);
        int end = Math.max(0, Math.min(column, text.length()));
        if (end > MAX_SCAN_CODE_UNITS) {
            return null;
        }
        if (!IndentationLexer.needsContext(language)) {
            return text.substring(0, end);
        }
        Map.Entry<Integer, IndentationLexer.State> checkpoint = checkpoints.floorEntry(target);
        int next = checkpoint == null ? 0 : checkpoint.getKey();
        IndentationLexer.State before = checkpoint == null ? IndentationLexer.State.CODE : checkpoint.getValue();
        int remaining = MAX_SCAN_CODE_UNITS;
        int remainingLines = MAX_SCAN_LINES;
        while (next < target) {
            if (remainingLines-- == 0) {
                return null;
            }
            String previous = lines.apply(next);
            long cost = (long) previous.length() + 1;
            if (cost > remaining) {
                return null;
            }
            remaining -= (int) cost;
            IndentationLexer.Result scanned = IndentationLexer.scan(previous, previous.length(), file, before, false);
            if (scanned == null) {
                return null;
            }
            before = scanned.afterLine();
            remember(++next, before);
        }
        if (end > remaining) {
            return null;
        }
        IndentationLexer.Result scanned = IndentationLexer.scan(text, end, file, before, true);
        return scanned == null || !scanned.codeAtCursor() ? null : scanned.code();
    }

    private void remember(int line, IndentationLexer.State state) {
        int weight = state.weight();
        if (weight > MAX_STATE_WEIGHT) {
            return;
        }
        IndentationLexer.State previous = checkpoints.put(line, state);
        retainedWeight += weight - (previous == null ? 0 : previous.weight());
        while (checkpoints.size() > MAX_CHECKPOINTS || retainedWeight > MAX_STATE_WEIGHT) {
            retainedWeight -= checkpoints.pollFirstEntry().getValue().weight();
        }
    }

    private void invalidateAfter(int line) {
        var invalid = checkpoints.tailMap(Math.max(0, line), false);
        for (IndentationLexer.State state : invalid.values()) {
            retainedWeight -= state.weight();
        }
        invalid.clear();
    }
}
