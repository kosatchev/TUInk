package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FocusSpaceTest {
    @Test
    void terminalMappingIsTheClippedInverseOfPointerMapping() {
        FocusSpace space = new FocusSpace(
            new Point(10, 5),
            new Rect(1, 2, 4, 3)
        );
        Point local = new Point(2, 3);
        Point terminal = space.terminalPoint(local).orElseThrow();

        assertEquals(new Point(12, 8), terminal);
        assertEquals(local, space.localPoint(new PointerEvent(
            terminal.y(),
            terminal.x(),
            PointerEvent.Kind.MOVE,
            PointerEvent.Button.NONE,
            Set.of(KeyStroke.Modifier.SHIFT)
        )).orElseThrow());
        assertTrue(space.terminalPoint(new Point(0, 3)).isEmpty());
        assertTrue(space.terminalPoint(new Point(5, 3)).isEmpty());
        assertThrows(NullPointerException.class, () -> space.terminalPoint(null));
    }

    @Test
    void terminalMappingRejectsNegativeAndOverflowingGlobalCoordinates() {
        FocusSpace negativeX = new FocusSpace(
            new Point(-2, 0),
            new Rect(1, 0, 1, 1)
        );
        FocusSpace negativeY = new FocusSpace(
            new Point(0, -2),
            new Rect(0, 1, 1, 1)
        );
        FocusSpace overflowX = new FocusSpace(
            new Point(Integer.MAX_VALUE, 0),
            new Rect(1, 0, 1, 1)
        );
        FocusSpace overflowY = new FocusSpace(
            new Point(0, Integer.MAX_VALUE),
            new Rect(0, 1, 1, 1)
        );

        assertTrue(negativeX.terminalPoint(new Point(1, 0)).isEmpty());
        assertTrue(negativeY.terminalPoint(new Point(0, 1)).isEmpty());
        assertTrue(overflowX.terminalPoint(new Point(1, 0)).isEmpty());
        assertTrue(overflowY.terminalPoint(new Point(0, 1)).isEmpty());
    }
}
