package dev.tuink.render.surface;

/**
 * Geometry for one accepted terminal text projection. Semantic positions are
 * deliberately opaque: editing positions and generated-text offsets are different domains.
 * Implementations borrow existing layout metrics; this port owns no document or cache.
 */
public interface TextSurfaceGeometry<P> {
    int totalRows();
    int totalColumns();
    int rowWidth(int row);
    TextSurfaceInteraction.Visual visualPosition(P position);
    P positionAt(TextSurfaceInteraction.Visual visual);
    TextSurfaceInteraction.Visual normalize(TextSurfaceInteraction.Visual visual);
    int previousColumn(int row, int column);
    int nextColumn(int row, int column);

    /** Non-linear visual atoms (for example a two-dimensional formula) select as one semantic range. */
    default Atom<P> selectionAtom(TextSurfaceInteraction.Visual visual) { return null; }

    record Atom<P>(P start, P end) {}
}
