package dev.tuiframe.widget;

import dev.tuiframe.geometry.Rect;
import java.util.Objects;

/**
 * Immutable geometry for two application-owned regions and a one-cell divider.
 * The first region is left of, or above, the second. When both minimum extents
 * cannot fit, the second region receives the entire allocation. The consumer
 * retains its preferred extent so a temporary small allocation does not erase it.
 * This class owns neither child widgets nor focus or application state.
 */
public final class SplitPane {
    public enum Axis { HORIZONTAL, VERTICAL }

    private final Rect bounds;
    private final Axis axis;
    private final Rect firstBounds;
    private final Rect dividerBounds;
    private final Rect secondBounds;
    private final int minimumFirstExtent;
    private final int maximumFirstExtent;

    private SplitPane(Rect bounds, Axis axis, Rect firstBounds, Rect dividerBounds,
        Rect secondBounds, int minimumFirstExtent, int maximumFirstExtent) {
        this.bounds = bounds;
        this.axis = axis;
        this.firstBounds = firstBounds;
        this.dividerBounds = dividerBounds;
        this.secondBounds = secondBounds;
        this.minimumFirstExtent = minimumFirstExtent;
        this.maximumFirstExtent = maximumFirstExtent;
    }

    public static SplitPane sideBySide(Rect bounds, int preferredFirstWidth,
        int minimumFirstWidth, int minimumSecondWidth) {
        return resolve(bounds, Axis.HORIZONTAL, preferredFirstWidth, minimumFirstWidth, minimumSecondWidth);
    }

    public static SplitPane resolve(Rect bounds, Axis axis, int preferredFirstExtent,
        int minimumFirstExtent, int minimumSecondExtent) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(axis, "axis");
        if (preferredFirstExtent < 0 || minimumFirstExtent < 1 || minimumSecondExtent < 1) {
            throw new IllegalArgumentException("preferred extent must be non-negative and minimum extents positive");
        }
        int extent = axis == Axis.HORIZONTAL ? bounds.width() : bounds.height();
        if (bounds.isEmpty() || (long) minimumFirstExtent + minimumSecondExtent + 1 > extent) {
            Rect empty = new Rect(bounds.x(), bounds.y(), 0, 0);
            return new SplitPane(bounds, axis, empty, empty, bounds, 0, 0);
        }
        int maximum = extent - minimumSecondExtent - 1;
        int first = Math.max(minimumFirstExtent, Math.min(preferredFirstExtent, maximum));
        Rect firstBounds;
        Rect dividerBounds;
        Rect secondBounds;
        if (axis == Axis.HORIZONTAL) {
            firstBounds = new Rect(bounds.x(), bounds.y(), first, bounds.height());
            dividerBounds = new Rect(firstBounds.rightExclusive(), bounds.y(), 1, bounds.height());
            secondBounds = Rect.fromEdges(dividerBounds.rightExclusive(), bounds.y(),
                bounds.rightExclusive(), bounds.bottomExclusive());
        } else {
            firstBounds = new Rect(bounds.x(), bounds.y(), bounds.width(), first);
            dividerBounds = new Rect(bounds.x(), firstBounds.bottomExclusive(), bounds.width(), 1);
            secondBounds = Rect.fromEdges(bounds.x(), dividerBounds.bottomExclusive(),
                bounds.rightExclusive(), bounds.bottomExclusive());
        }
        return new SplitPane(bounds, axis, firstBounds, dividerBounds, secondBounds, minimumFirstExtent, maximum);
    }

    public Rect bounds() { return bounds; }
    public Axis axis() { return axis; }
    public Rect firstBounds() { return firstBounds; }
    public Rect dividerBounds() { return dividerBounds; }
    public Rect secondBounds() { return secondBounds; }
    public boolean divided() { return !dividerBounds.isEmpty(); }
    public int firstExtent() { return axis == Axis.HORIZONTAL ? firstBounds.width() : firstBounds.height(); }
    public int minimumFirstExtent() { return minimumFirstExtent; }
    public int maximumFirstExtent() { return maximumFirstExtent; }

    /** Clamps a requested first-region extent without losing intermediate pointer precision. */
    public int clampFirstExtent(long extent) {
        return (int) Math.max(minimumFirstExtent, Math.min(extent, maximumFirstExtent));
    }
}
