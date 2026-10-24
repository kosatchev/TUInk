package dev.tuink.editor.diff;

import java.util.List;
import java.util.Objects;

/** Optional bounded line comparison, independent of any editor view or diff library. */
@FunctionalInterface
public interface LineDiff {
    enum Algorithm { MYERS, HISTOGRAM }

    /**
     * Compares stable line sequences without materializing them as strings.
     * The caller keeps the lists and their character sequences unchanged during this call.
     * The immutable result contains ordered, non-overlapping, half-open line ranges.
     * A positive work budget bounds exact comparison; an implementation may conservatively
     * combine unverified changes when the budget is exhausted.
     */
    List<LineEdit> compute(List<? extends CharSequence> before, List<? extends CharSequence> after,
                           Algorithm algorithm, long maximumWork);

    /** Whether a view should schedule derived diff work at all. */
    default boolean available() { return true; }

    /** Headless views do not load a provider or schedule gutter work implicitly. */
    static LineDiff none() { return NoLineDiff.INSTANCE; }
}

final class NoLineDiff implements LineDiff {
    static final LineDiff INSTANCE = new NoLineDiff();

    private NoLineDiff() {}

    @Override public boolean available() { return false; }

    @Override
    public List<LineEdit> compute(List<? extends CharSequence> before, List<? extends CharSequence> after,
                                  Algorithm algorithm, long maximumWork) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(algorithm, "algorithm");
        if (maximumWork <= 0L) throw new IllegalArgumentException("Diff work budget must be positive");
        return List.of();
    }
}
