package dev.tuink.render.surface;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuink.render.surface.TextSurfaceInteraction.*;
import org.junit.jupiter.api.Test;

class TextSurfaceInteractionTest {
    /** Deliberately no editor or Markdown: alternate projections exercise the same controller. */
    private static final class Geometry implements TextSurfaceGeometry<Visual> {
        private final int[] widths;
        Geometry(int... widths) { this.widths = widths; }
        public int totalRows() { return widths.length; }
        public int totalColumns() { return 100; }
        public int rowWidth(int row) { return widths[row]; }
        public Visual visualPosition(Visual position) { return position; }
        public Visual positionAt(Visual visual) { return visual; }
        public Visual normalize(Visual visual) {
            int row = Math.clamp(visual.row(), 0, widths.length - 1);
            return new Visual(row, Math.clamp(visual.column(), 0, widths[row]));
        }
        public int previousColumn(int row, int column) { return Math.max(0, column - 1); }
        public int nextColumn(int row, int column) { return Math.min(widths[row], column + 1); }
    }

    @Test void preferredColumnSurvivesShortRowsAndPageMovement() {
        var geometry = new Geometry(8, 1, 8, 8);
        var first = TextSurfaceInteraction.move(geometry, new Visual(0, 7), Move.DOWN, 1, -1);
        assertEquals(new Visual(1, 1), first.visual());
        var second = TextSurfaceInteraction.move(geometry, first.visual(), Move.PAGE_DOWN, 2, first.preferredColumn());
        assertEquals(new Visual(3, 7), second.visual());
        assertEquals(-1, TextSurfaceInteraction.move(geometry, second.visual(), Move.HOME, 1, 7).preferredColumn());
    }

    @Test void hitsUseViewportOriginAndRejectRowsOutsideTheContent() {
        var viewport = new Viewport(10, 4, 3, 20);
        assertEquals(new Visual(12, 11), TextSurfaceInteraction.hit(viewport, 2, 7));
        assertNull(TextSurfaceInteraction.hit(viewport, -1, 7));
        assertNull(TextSurfaceInteraction.hit(viewport, 3, 7));
    }

    @Test void panPoliciesShareClampButPreserveDifferentCaretOwnership() {
        var geometry = new Geometry(80, 80, 80, 80, 80, 80);
        var viewport = new Viewport(0, 0, 3, 10);
        var caret = new Visual(0, 2);
        var preserved = TextSurfaceInteraction.pan(geometry, viewport, caret, 99, 3, PanCaret.PRESERVE);
        var translated = TextSurfaceInteraction.pan(geometry, viewport, caret, 99, 3, PanCaret.TRANSLATE_VISUAL);
        assertEquals(new Viewport(3, 3, 3, 10), preserved.viewport());
        assertEquals(preserved.viewport(), translated.viewport());
        assertEquals(caret, preserved.visual());
        assertEquals(new Visual(3, 5), translated.visual());
    }

    @Test void revealAndPageClampUseTheSameFiniteViewport() {
        var viewport = new Viewport(0, 0, 4, 10);
        assertEquals(new Viewport(3, 11, 4, 10), TextSurfaceInteraction.reveal(viewport,
            new Visual(6, 20), 7, 30, 0, true, true));
        assertEquals(3, TextSurfaceInteraction.pageTop(1, 5, 7, 4, true));
        assertEquals(6, TextSurfaceInteraction.pageTop(1, 5, 7, 4, false));
    }

    @Test void nonLinearAtomsResolveSelectionByDirectionBeforeSemanticBoundaryPolicy() {
        TextSurfaceGeometry<Integer> atom = new TextSurfaceGeometry<>() {
            public int totalRows() { return 5; }
            public int totalColumns() { return 20; }
            public int rowWidth(int row) { return 20; }
            public Visual visualPosition(Integer offset) { return new Visual(0, 0); }
            public Integer positionAt(Visual visual) { return 3; }
            public Visual normalize(Visual visual) { return visual; }
            public int previousColumn(int row, int column) { return column - 1; }
            public int nextColumn(int row, int column) { return column + 1; }
            public Atom<Integer> selectionAtom(Visual visual) { return new Atom<>(3, 12); }
        };
        TextSurfaceContent<Integer> content = (offset, ceil) -> offset;
        assertEquals(12, TextSurfaceInteraction.target(atom, content, new Visual(4, 8), true, true));
        assertEquals(3, TextSurfaceInteraction.target(atom, content, new Visual(4, 8), true, false));
        assertEquals(3, TextSurfaceInteraction.target(atom, content, new Visual(4, 8), false, true));
    }
}
