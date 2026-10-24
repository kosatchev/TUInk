package dev.tuink.diff.jgit;

import dev.tuink.editor.diff.LineDiff;
import dev.tuink.editor.diff.LineEdit;
import java.util.List;
import java.util.Objects;
import org.eclipse.jgit.diff.DiffAlgorithm;

/** JGit-backed line diff with the editor's bounded-work conservative fallback. */
public final class JgitLineDiff implements LineDiff {
    private static final DiffAlgorithm HISTOGRAM =
        DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM);
    private static final DiffAlgorithm MYERS =
        DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS);

    @Override
    public List<LineEdit> compute(List<? extends CharSequence> before, List<? extends CharSequence> after,
                                  Algorithm algorithm, long maximumWork) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        DiffAlgorithm selected = switch (Objects.requireNonNull(algorithm, "algorithm")) {
            case MYERS -> MYERS;
            case HISTOGRAM -> HISTOGRAM;
        };
        return BoundedLineDiff.compute(selected, before, after, maximumWork);
    }
}
