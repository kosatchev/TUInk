package dev.tuink.core;

import java.util.ArrayList;
import java.util.List;

/** One composition algorithm, first counting without output allocations, then materializing admitted output. */
final class ReplacementBuilder {
    final List<String> lines;
    final List<String> separators;
    private final DocumentEditBudget budget;
    private final boolean materialize;
    private long completedLines;
    private long length;
    private boolean copied;
    private String wholeLine;
    private StringBuilder pending;

    private ReplacementBuilder(DocumentEditBudget budget, boolean materialize) {
        this.budget = budget;
        this.materialize = materialize;
        lines = materialize ? new ArrayList<>() : null;
        separators = materialize ? new ArrayList<>() : null;
    }

    static ReplacementBuilder counting(int sourceLines) {
        return new ReplacementBuilder(DocumentEditBudget.currentHeap(sourceLines), false);
    }

    static ReplacementBuilder counting(DocumentEditBudget budget) {
        return new ReplacementBuilder(budget, false);
    }

    ReplacementBuilder materializer() {
        return new ReplacementBuilder(budget.fresh(), true);
    }

    void append(CharSequence text, int start, int end) {
        if (start == end) return;
        boolean reuse = length == 0 && start == 0 && end == text.length();
        copied |= !reuse || text instanceof LineText line && line.cachedString() == null;
        length = DocumentEditBudget.add(length, end - (long) start);
        budget.append(length, copied);
        if (!materialize) return;
        if (reuse) {
            wholeLine = text.toString();
            return;
        }
        if (pending == null) {
            pending = new StringBuilder();
            if (wholeLine != null) {
                pending.append(wholeLine);
                wholeLine = null;
            }
        }
        pending.append(text, start, end);
    }

    void appendReplacement(ReplacementText replacement, String separator) {
        String text = replacement.text();
        if (replacement.lineBreaks() == 0) {
            append(text, 0, text.length());
            return;
        }
        if (!materialize) {
            append(text, 0, replacement.firstEnd());
            newline(separator);
            int interiorLines = replacement.lineBreaks() - 1;
            budget.finishLines(replacement.interiorUnits(), interiorLines, replacement.longestInteriorLine());
            completedLines += interiorLines;
            append(text, replacement.lastStart(), text.length());
            return;
        }
        int start = 0;
        for (int end = 0; end < text.length(); end++) {
            char character = text.charAt(end);
            if (character != '\r' && character != '\n') continue;
            append(text, start, end);
            newline(separator);
            if (character == '\r' && end + 1 < text.length() && text.charAt(end + 1) == '\n') end++;
            start = end + 1;
        }
        append(text, start, text.length());
    }

    Position position() {
        return new Position((int) completedLines, (int) length);
    }

    void newline(String separator) {
        finishLine();
        if (materialize) separators.add(separator);
    }

    void finishLine() {
        budget.finishLine(length, copied);
        if (materialize) lines.add(pending == null ? wholeLine == null ? "" : wholeLine : pending.toString());
        completedLines++;
        length = 0;
        copied = false;
        pending = null;
        wholeLine = null;
    }
}
