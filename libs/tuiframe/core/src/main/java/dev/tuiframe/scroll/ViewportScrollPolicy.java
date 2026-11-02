package dev.tuiframe.scroll;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.screen.ScrollHint;
import java.util.Objects;

/** Selects optional terminal scroll candidates; the presenter still verifies the actual cells. */
public final class ViewportScrollPolicy {
    private ViewportScrollPolicy() {}

    /** Immutable presentation identity and geometry, without retaining a view or application owner. */
    public record Viewport(String identity, long layoutGeneration, Rect contentBounds,
                           int topRow, int leftColumn, boolean horizontalAllowed) {
        public Viewport {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(contentBounds, "contentBounds");
            if (identity.isEmpty() || topRow < 0 || leftColumn < 0)
                throw new IllegalArgumentException("Invalid scroll viewport");
        }
    }

    /** Keeps only an immutable viewport, never a document, view, layout cache or application owner. */
    public static final class Tracker {
        private Viewport previous;

        public ScrollHint next(Viewport current) {
            ScrollHint hint = between(previous, current);
            previous = current;
            return hint;
        }

        public void invalidate() { previous = null; }
    }

    /** Null denotes an occluded, invalidated, or previously unknown content surface. */
    public static ScrollHint between(Viewport previous, Viewport current) {
        if (previous == null || current == null || current.contentBounds().isEmpty()
            || !previous.identity().equals(current.identity())
            || previous.layoutGeneration() != current.layoutGeneration()
            || !previous.contentBounds().equals(current.contentBounds())) return ScrollHint.none();
        long rows = (long) current.topRow() - previous.topRow();
        long columns = (long) current.leftColumn() - previous.leftColumn();
        if (rows != 0 && columns == 0 && Math.abs(rows) < current.contentBounds().height())
            return new ScrollHint.Vertical(current.contentBounds());
        if (rows == 0 && columns != 0 && previous.horizontalAllowed() && current.horizontalAllowed()
            && Math.abs(columns) < current.contentBounds().width())
            return new ScrollHint.Horizontal(current.contentBounds(), (int) columns);
        return ScrollHint.none();
    }
}
