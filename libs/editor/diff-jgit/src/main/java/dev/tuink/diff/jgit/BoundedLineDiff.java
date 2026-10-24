package dev.tuink.diff.jgit;

import dev.tuink.editor.diff.LineEdit;
import dev.tuink.editor.document.ImmutableText;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.diff.SequenceComparator;

/** Caps exact diff work; a linear fallback conservatively marks the changed middle. */
final class BoundedLineDiff {
    private BoundedLineDiff() {
    }

    static List<LineEdit> compute(
        DiffAlgorithm algorithm,
        List<? extends CharSequence> before,
        List<? extends CharSequence> after,
        long maximumWork
    ) {
        if (maximumWork <= 0L) {
            throw new IllegalArgumentException("Diff work budget must be positive");
        }
        BudgetComparator comparator = new BudgetComparator(maximumWork);
        Bounds bounds = changedBounds(before, after, comparator);
        long span = (long) bounds.beforeEnd() - bounds.prefix()
            + bounds.afterEnd() - bounds.prefix();
        // JGit can compare cached, unequal hashes without calling our comparator.
        // Bound that edit-graph work as well as the visible string/hash operations.
        if (span == 0L || bounds.beforeEnd() == bounds.prefix() || bounds.afterEnd() == bounds.prefix()
            || span > maximumWork / span) {
            return conservativeDiff(bounds);
        }
        try {
            EditList relative = algorithm.diff(
                comparator,
                new Lines(before.subList(bounds.prefix(), bounds.beforeEnd())),
                new Lines(after.subList(bounds.prefix(), bounds.afterEnd()))
            );
            List<LineEdit> result = new ArrayList<>(relative.size());
            for (Edit edit : relative) {
                result.add(new LineEdit(
                    bounds.prefix() + edit.getBeginA(), bounds.prefix() + edit.getEndA(),
                    bounds.prefix() + edit.getBeginB(), bounds.prefix() + edit.getEndB()
                ));
            }
            return List.copyOf(result);
        } catch (BudgetExceeded exhausted) {
            return conservativeDiff(bounds);
        }
    }

    private static Bounds changedBounds(List<? extends CharSequence> before, List<? extends CharSequence> after,
        BudgetComparator comparator) {
        int prefix = 0;
        int commonLimit = Math.min(before.size(), after.size());
        while (prefix < commonLimit && comparator.verifyBoundary(before.get(prefix), after.get(prefix))) {
            prefix++;
        }
        int beforeEnd = before.size();
        int afterEnd = after.size();
        while (beforeEnd > prefix && afterEnd > prefix
            && comparator.verifyBoundary(before.get(beforeEnd - 1), after.get(afterEnd - 1))) {
            beforeEnd--;
            afterEnd--;
        }
        return new Bounds(prefix, beforeEnd, afterEnd);
    }

    private static List<LineEdit> conservativeDiff(Bounds bounds) {
        if (bounds.beforeEnd() != bounds.prefix() || bounds.afterEnd() != bounds.prefix()) {
            return List.of(new LineEdit(bounds.prefix(), bounds.beforeEnd(), bounds.prefix(), bounds.afterEnd()));
        }
        return List.of();
    }

    private record Bounds(int prefix, int beforeEnd, int afterEnd) {
    }

    private static boolean textEquals(CharSequence left, CharSequence right) {
        if (left == right) return true;
        if (left.length() != right.length()) return false;
        if (left instanceof ImmutableText line) return line.contentEquals(right);
        if (right instanceof ImmutableText line) return line.contentEquals(left);
        if (left instanceof String && right instanceof String) return left.equals(right);
        for (int offset = 0; offset < left.length(); offset++) {
            if (left.charAt(offset) != right.charAt(offset)) return false;
        }
        return true;
    }

    private static final class Lines extends Sequence {
        private final List<? extends CharSequence> values;

        private Lines(List<? extends CharSequence> values) {
            this.values = values;
        }

        @Override
        public int size() {
            return values.size();
        }
    }

    private static final class BudgetComparator extends SequenceComparator<Lines> {
        private long remaining;

        private BudgetComparator(long maximumWork) {
            remaining = maximumWork;
        }

        private boolean verifyBoundary(CharSequence left, CharSequence right) {
            if (left == right) return true;
            if (left.length() != right.length()) return false;
            if (left instanceof ImmutableText && right instanceof ImmutableText && left.hashCode() != right.hashCode()) {
                return false;
            }
            // An unverified boundary belongs to the conservatively changed middle.
            if (left.length() > remaining) return false;
            spend(left.length());
            return textEquals(left, right);
        }

        @Override
        public boolean equals(Lines a, int ai, Lines b, int bi) {
            CharSequence left = a.values.get(ai);
            CharSequence right = b.values.get(bi);
            spend(left == right ? 1L : 1L + Math.min(left.length(), right.length()));
            return textEquals(left, right);
        }

        @Override
        public int hash(Lines sequence, int index) {
            CharSequence line = sequence.values.get(index);
            // Charge even a cached String hash: the budget does not depend on JVM state.
            spend(1L + line.length());
            if (line instanceof String || line instanceof ImmutableText) return line.hashCode();
            int hash = 0;
            for (int offset = 0; offset < line.length(); offset++) hash = 31 * hash + line.charAt(offset);
            return hash;
        }

        private void spend(long work) {
            if (work > remaining) {
                throw BudgetExceeded.INSTANCE;
            }
            remaining -= work;
        }
    }

    private static final class BudgetExceeded extends RuntimeException {
        private static final BudgetExceeded INSTANCE = new BudgetExceeded();

        private BudgetExceeded() {
            super(null, null, false, false);
        }
    }
}
