package dev.tuink.render.markdown;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.markdown.mermaid.Diagram;
import java.util.List;
import org.junit.jupiter.api.Test;

class MermaidRoutingGridTest {
    @Test void removingEitherAxisRestoresTheOtherDashedStyleInEitherInsertionOrder() {
        for (boolean horizontalDashed : new boolean[] {false, true})
            for (boolean horizontalFirst : new boolean[] {false, true})
                for (boolean removeHorizontal : new boolean[] {false, true}) {
                    var grid = new MermaidLayout.Grid(9, 9);
                    var h = List.of(new MermaidLayout.Point(1, 4), new MermaidLayout.Point(7, 4));
                    var v = List.of(new MermaidLayout.Point(4, 1), new MermaidLayout.Point(4, 7));
                    var first = grid.route(horizontalFirst ? h : v, horizontalFirst ? horizontalDashed : !horizontalDashed);
                    var second = grid.route(horizontalFirst ? v : h, horizontalFirst ? !horizontalDashed : horizontalDashed);
                    assertNotNull(first); assertNotNull(second);
                    assertTrue(String.join("\n", grid.lines()).contains("╪"));
                    var horizontal = horizontalFirst ? first : second;
                    var vertical = horizontalFirst ? second : first;
                    grid.remove(removeHorizontal ? horizontal : vertical);
                    String remaining = String.join("\n", grid.lines());
                    assertFalse(remaining.contains("╪"));
                    assertTrue(remaining.contains(removeHorizontal
                        ? (!horizontalDashed ? "┊" : "│") : (horizontalDashed ? "╌" : "─")));
                    grid.remove(removeHorizontal ? vertical : horizontal);
                    assertTrue(grid.lines().isEmpty());
                }
    }

    @Test void finalDrawingTransformEndsRouteMutationForEveryDirection() {
        for (var direction : Diagram.Direction.values()) {
            var grid = new MermaidLayout.Grid(9, 9);
            var points = List.of(new MermaidLayout.Point(1, 4), new MermaidLayout.Point(7, 4));
            var route = grid.route(points, false);
            assertNotNull(route);
            grid.mirrorPaths(direction);
            assertThrows(IllegalStateException.class, () -> grid.remove(route));
            assertThrows(IllegalStateException.class, () -> grid.route(points, false));
        }
    }
}
