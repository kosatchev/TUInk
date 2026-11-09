package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.geometry.Rect;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaletteDockTest {
    private final List<PaletteDock.Item<String>> items = List.of(new PaletteDock.Item<>("files", true, 6, 3),
        new PaletteDock.Item<>("search", true, 18, 11));

    @Test void shortAllocationGivesActivePaletteTheBodyAndRestoresPreferredLayoutLater() {
        var small = PaletteDock.layout(new Rect(2, 3, 30, 10), items, "search", null);
        assertEquals(0, small.slot("files").body().height());
        assertEquals(8, small.slot("search").body().height());
        assertTrue(small.dividers().isEmpty());
        var normal = PaletteDock.layout(new Rect(2, 3, 30, 27), items, "search", null);
        assertEquals(6, normal.slot("files").body().height());
        assertEquals(18, normal.slot("search").body().height());
        assertEquals(1, normal.dividers().size());
        assertEquals(7, normal.dividers().getFirst().split().firstExtent());
    }
    @Test void collapsingOrMaximizingDoesNotChangeTheInputPreferences() {
        Rect bounds = new Rect(0, 0, 30, 27);
        var collapsed = PaletteDock.layout(bounds, List.of(new PaletteDock.Item<>("files", false, 6, 3), items.getLast()), "search", null);
        assertEquals(1, collapsed.slot("files").bounds().height());
        assertEquals(25, collapsed.slot("search").body().height());
        var full = PaletteDock.layout(bounds, items, "search", "search");
        assertEquals(bounds, full.slot("search").bounds());
        assertNull(full.slot("files"));
        assertEquals(6, items.getFirst().preferredHeight());
    }
    @Test void allocationsStayInsideTheDockAndNeverOverlapEvenAtZeroSize() {
        for (int height = 0; height < 80; height++) for (int width : List.of(0, 1, 30)) {
            Rect bounds = new Rect(2, 3, width, height);
            var layout = PaletteDock.layout(bounds, items, "search", null);
            for (var slot : layout.slots()) { assertTrue(bounds.contains(slot.bounds())); assertTrue(slot.bounds().contains(slot.body())); }
            for (int i = 1; i < layout.slots().size(); i++) assertFalse(layout.slots().get(i - 1).bounds().intersects(layout.slots().get(i).bounds()));
            if (height == 1) assertNotNull(layout.slot("search"));
        }
    }
}
