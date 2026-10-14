package dev.tuink.core;

import java.util.ArrayList;
import java.util.List;

/** Derived diff presentation for one view; source/save baselines belong to the document. */
final class EditorGutterDiffState {
    GutterDiffMode gutterDiffMode = GutterDiffMode.MYERS;
    final List<GutterDiffFlag> savedDiffFlags = new ArrayList<>();
    final List<Boolean> savedDeletedMarkers = new ArrayList<>();
    final List<GutterDiffFlag> openDiffFlags = new ArrayList<>();
    final List<Boolean> openDeletedMarkers = new ArrayList<>();
    long baselineGeneration = -1;
    long gutterDiffComputedRevision = Long.MIN_VALUE;
    long gutterDiffLastRebuildAtNanos;
    long gutterDiffLastRebuildDurationNanos;
    boolean gutterDiffPendingRebuild;
}
