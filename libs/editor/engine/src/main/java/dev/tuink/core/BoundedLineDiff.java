package dev.tuink.core;

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

    static EditList compute(
        DiffAlgorithm algorithm,
        List<String> before,
        List<String> after,
        long maximumWork
    ) {
        if (maximumWork <= 0L) {
            throw new IllegalArgumentException("Diff work budget must be positive");
        }
        Bounds bounds = changedBounds(before, after);
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
                new BudgetComparator(maximumWork),
                new Lines(before.subList(bounds.prefix(), bounds.beforeEnd())),
                new Lines(after.subList(bounds.prefix(), bounds.afterEnd()))
            );
            EditList result = new EditList();
            for (Edit edit : relative) {
                result.add(new Edit(
                    bounds.prefix() + edit.getBeginA(), bounds.prefix() + edit.getEndA(),
                    bounds.prefix() + edit.getBeginB(), bounds.prefix() + edit.getEndB()
                ));
            }
            return result;
        } catch (BudgetExceeded exhausted) {
            return conservativeDiff(bounds);
        }
    }

    private static Bounds changedBounds(List<String> before, List<String> after) {
        int prefix = 0;
        int commonLimit = Math.min(before.size(), after.size());
        while (prefix < commonLimit && before.get(prefix).equals(after.get(prefix))) {
            prefix++;
        }
        int beforeEnd = before.size();
        int afterEnd = after.size();
        while (beforeEnd > prefix && afterEnd > prefix
            && before.get(beforeEnd - 1).equals(after.get(afterEnd - 1))) {
            beforeEnd--;
            afterEnd--;
        }
        return new Bounds(prefix, beforeEnd, afterEnd);
    }

    private static EditList conservativeDiff(Bounds bounds) {
        EditList result = new EditList();
        if (bounds.beforeEnd() != bounds.prefix() || bounds.afterEnd() != bounds.prefix()) {
            result.add(new Edit(bounds.prefix(), bounds.beforeEnd(), bounds.prefix(), bounds.afterEnd()));
        }
        return result;
    }

    private record Bounds(int prefix, int beforeEnd, int afterEnd) {
    }

    private static final class Lines extends Sequence {
        private final List<String> values;

        private Lines(List<String> values) {
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

        @Override
        public boolean equals(Lines a, int ai, Lines b, int bi) {
            String left = a.values.get(ai);
            String right = b.values.get(bi);
            spend(left == right ? 1L : 1L + Math.min(left.length(), right.length()));
            return left.equals(right);
        }

        @Override
        public int hash(Lines sequence, int index) {
            String line = sequence.values.get(index);
            // Charge even a cached String hash: the budget does not depend on JVM state.
            spend(1L + line.length());
            return line.hashCode();
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
