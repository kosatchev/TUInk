package dev.tuink.core;

import java.util.concurrent.atomic.AtomicLong;

/** Mutable editing position of one view; no document metadata or history lives here. */
public final class EditorViewState {
    private static final AtomicLong NEXT_ID = new AtomicLong();
    final long id = NEXT_ID.incrementAndGet();
    Position cursor = new Position(0, 0);
    Position selectionAnchor;
    int topLine;
    int topWrapSegment;
    int leftColumn;
    int leftVisualColumn;
    long navigationRevision;
}
