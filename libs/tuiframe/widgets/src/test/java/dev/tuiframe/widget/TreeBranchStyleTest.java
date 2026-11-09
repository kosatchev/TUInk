package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TreeBranchStyleTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectionOwnsItsEmphasisWhileUnselectedBranchesKeepTheirs(boolean focused) {
        CellStyle body = new CellStyle(CellColor.GRAY_218, CellColor.GRAY_028, false, false);
        CellStyle selected = new CellStyle(CellColor.BLACK, CellColor.CYAN, false, false);
        CellStyle selectedFocused = new CellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE, false, false);
        Theme theme = role -> {
            if (WidgetThemeKeys.TREE_BRANCH.equals(role)) return new CellStyle(CellColor.YELLOW, CellColor.RED, true, true);
            if (WidgetThemeKeys.MODAL_BODY.equals(role)) return body;
            if (WidgetThemeKeys.LIST_ITEM_SELECTED.equals(role)) return selected;
            if (WidgetThemeKeys.LIST_ITEM_SELECTED_FOCUSED.equals(role)) return selectedFocused;
            return null;
        };
        var tree = new TreeView<>(List.of(
            new TreeView.Item<>(0, "branch", 0, TreeView.Kind.EXPANDED),
            new TreeView.Item<>(1, "child", 1, TreeView.Kind.LEAF),
            new TreeView.Item<>(2, "other", 0, TreeView.Kind.COLLAPSED)), new TreeView.State(0, 0, 0));
        var buffer = new CellBuffer(4, 20);
        tree.layout(new Rect(0, 0, 20, 4)).paint(new Canvas(buffer), theme, new WidgetState(focused, true));

        CellStyle active = focused ? selectedFocused : selected;
        CellStyle branch = buffer.get(0, 2).style();
        assertEquals(active.fg(), branch.fg());
        assertEquals(active.bg(), branch.bg());
        assertEquals(active, branch);
        assertFalse(branch.bold());
        assertFalse(branch.underline());
        assertFalse(buffer.get(1, 4).style().bold());
        assertEquals(body, buffer.get(1, 4).style());
        assertEquals(new CellStyle(body.fg(), body.bg(), true, true), buffer.get(2, 2).style());
        assertEquals(body, buffer.get(3, 0).style());
    }
}
