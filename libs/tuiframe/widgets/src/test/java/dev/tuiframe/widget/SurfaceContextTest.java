package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import org.junit.jupiter.api.Test;

class SurfaceContextTest {
    private static final CellStyle DARK = new CellStyle(CellColor.BRIGHT_WHITE, CellColor.BLACK, false, false);
    private static final CellStyle LIGHT = new CellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, false, false);
    private static final CellStyle SELECTED = new CellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE, true, false);

    @Test
    void childAndGrandchildUseParentLocalCoordinatesAndCannotEscapeItsClip() {
        var root = new SurfaceContext(new Rect(4, 2, 80, 24), new Rect(6, 3, 70, 20), Theme.fixed(DARK));
        var child = root.child(new Rect(10, 3, 25, 8));
        var grandchild = child.child(new Rect(-3, -1, 40, 10));

        assertEquals(new Rect(14, 5, 25, 8), child.bounds());
        assertEquals(child.bounds(), child.clip());
        assertEquals(new Rect(11, 4, 40, 10), grandchild.bounds());
        assertEquals(child.clip(), grandchild.clip(), "A child's larger allocation cannot paint outside its parent");
        assertSame(root.theme(), grandchild.theme());
    }

    @Test
    void partiallyVisibleChildrenRetainTheirAllocationButOnlyExposeTheVisibleClip() {
        var root = new SurfaceContext(new Rect(0, 0, 80, 24), new Rect(0, 0, 60, 20), Theme.fixed(DARK));
        var child = root.child(new Rect(50, 18, 25, 8));
        var outside = root.child(new Rect(70, 4, 8, 3));

        assertEquals(new Rect(50, 18, 25, 8), child.bounds());
        assertEquals(new Rect(50, 18, 10, 2), child.clip());
        assertTrue(outside.clip().isEmpty(), "An off-parent child has no paint or hit area");
    }

    @Test
    void childRoleOverridesRetainUnknownParentRolesAndDoNotChangeSiblings() {
        Theme parent = role -> {
            if (WidgetThemeKeys.MODAL_BODY.equals(role)) return DARK;
            if (WidgetThemeKeys.LIST_ITEM_SELECTED.equals(role)) return SELECTED;
            return null;
        };
        var root = SurfaceContext.root(new Rect(0, 0, 80, 24), parent);
        var document = root.child(new Rect(31, 0, 49, 24));
        var popup = document.withTheme(role -> WidgetThemeKeys.MODAL_BODY.equals(role) ? LIGHT : null);
        var popupChild = popup.child(new Rect(2, 2, 20, 4));

        assertEquals(LIGHT, popupChild.theme().resolveStyle(WidgetThemeKeys.MODAL_BODY));
        assertEquals(SELECTED, popupChild.theme().resolveStyle(WidgetThemeKeys.LIST_ITEM_SELECTED));
        assertEquals(DARK, root.theme().resolveStyle(WidgetThemeKeys.MODAL_BODY));
        assertEquals(DARK, document.theme().resolveStyle(WidgetThemeKeys.MODAL_BODY));
        assertEquals(document.bounds(), popup.bounds());
        assertEquals(document.clip(), popup.clip());
    }

    @Test
    void anExplicitTerminalDefaultStyleOverridesTheParentInsteadOfRequestingFallback() {
        var root = SurfaceContext.root(new Rect(0, 0, 20, 5), Theme.fixed(DARK));
        var child = root.child(new Rect(1, 1, 10, 2))
            .withTheme(role -> WidgetThemeKeys.TEXT_FIELD.equals(role) ? CellStyle.DEFAULT : null);

        assertEquals(CellStyle.DEFAULT, child.theme().resolveStyle(WidgetThemeKeys.TEXT_FIELD));
        assertEquals(DARK, child.theme().resolveStyle(WidgetThemeKeys.MODAL_BODY));
    }
}
