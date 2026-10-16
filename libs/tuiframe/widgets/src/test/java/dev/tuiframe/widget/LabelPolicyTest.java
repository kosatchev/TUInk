package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.style.CellStyle;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LabelPolicyTest {
    private static final Rect CLIP = new Rect(0, 0, 40, 8);
    private static final FocusContext CONTEXT = new FocusContext(true, true, FocusSpace.root(CLIP));

    @Test
    void tabGeometryAndCloseHitUseThePreparedLabelAndForwardItsTabOrigin() {
        int[] origin = {-1};
        LabelPolicy policy = new LabelPolicy() {
            @Override
            public CellText.FittedText fitMiddle(String text, int cells, int tail, int initialColumn) {
                origin[0] = initialColumn;
                return new CellText.FittedText("界", true);
            }
            @Override public String clip(String text, int left, int cells) { return CellText.clip(text, left, cells); }
        };
        TabBar.Placement placed = new TabBar(List.of(new TabBar.Item("very long source", true, "#", "*", 0, policy)),
            0, TabBar.State.initial()).layout(new Rect(1, 1, 30, 1));
        CellBuffer buffer = new CellBuffer(8, 40);
        placed.paint(new Canvas(buffer), Theme.fixed(CellStyle.DEFAULT), WidgetState.FOCUSED);
        assertEquals(2, origin[0]);
        assertEquals("x", buffer.get(1, 7).grapheme());
        var action = placed.handle(new PointerEvent(1, 7, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of()), CONTEXT);
        assertInstanceOf(TabBar.ClosePressed.class, action.output().orElseThrow());
    }

    @Test
    void treeViewportPassesItsActualHiddenCellsWithoutChangingSelectionGeometry() {
        int[] viewport = {-1, -1};
        LabelPolicy policy = new LabelPolicy() {
            @Override public CellText.FittedText fitMiddle(String text, int cells, int tail, int initialColumn) {
                return CellText.fitMiddle(text, cells, tail, initialColumn);
            }
            @Override public String clip(String text, int left, int cells) {
                viewport[0] = left;
                viewport[1] = cells;
                return " ".repeat(Math.min(2, cells));
            }
        };
        TreeView.Placement<String> placed = new TreeView<>(
            List.of(new TreeView.Item<>("exact opaque value", "long source label", 0, TreeView.Kind.LEAF, "", policy)),
            new TreeView.State(0, 0, 5)).layout(new Rect(2, 1, 8, 3));
        CellBuffer buffer = new CellBuffer(8, 40);
        placed.paint(new Canvas(buffer), Theme.fixed(CellStyle.DEFAULT), WidgetState.FOCUSED);
        assertEquals(3, viewport[0]); // Two disclosure cells precede this label.
        assertEquals(8, viewport[1]);
        var action = placed.handle(new PointerEvent(1, 3, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of()), CONTEXT);
        var press = assertInstanceOf(TreeView.ItemPressed.class, action.output().orElseThrow());
        assertEquals("exact opaque value", press.value());
        assertTrue(action.consumed());
    }
}
