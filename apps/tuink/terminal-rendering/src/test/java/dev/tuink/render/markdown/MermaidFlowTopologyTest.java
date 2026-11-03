package dev.tuink.render.markdown;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.markdown.mermaid.Diagram;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Reads connections from the public drawing; it has no access to layout ranks, ports or routes. */
class MermaidFlowTopologyTest {
    @Test void crossingIndependentEdgesNeverInventOtherEndpointsInAnyDirection() {
        everyDirection(direction -> {
            var drawing = drawing(direction, List.of("A", "B", "C", "D"), List.of(
                edge("A", "D", ""), edge("B", "C", "")));
            assertEquals(Set.of("D"), drawing.targets("A", direction), drawing.diagnostic(direction));
            assertEquals(Set.of("C"), drawing.targets("B", direction), drawing.diagnostic(direction));
            assertEquals(1, drawing.arrowheads("A", direction).size(), drawing.diagnostic(direction));
            assertEquals(1, drawing.arrowheads("B", direction).size(), drawing.diagnostic(direction));
        });
    }

    @Test void parallelEdgesKeepBothLabelsAndTwoIndependentlyReachableArrowheads() {
        everyDirection(direction -> {
            var drawing = drawing(direction, List.of("A", "B"), List.of(
                edge("A", "B", "yes"), edge("A", "B", "no")));
            assertEquals(1, occurrences(drawing.text(), "yes"), drawing.diagnostic(direction));
            assertEquals(1, occurrences(drawing.text(), "no"), drawing.diagnostic(direction));
            assertEquals(Set.of("B"), drawing.targets("A", direction), drawing.diagnostic(direction));
            assertEquals(2, drawing.arrowheads("A", direction).size(), drawing.diagnostic(direction));
        });
    }

    @Test void anEdgeLabelCannotEraseTheContinuousStemToItsDestination() {
        everyDirection(direction -> {
            var drawing = drawing(direction, List.of("A", "B"), List.of(edge("A", "B", "next")));
            assertEquals(1, occurrences(drawing.text(), "next"), drawing.diagnostic(direction));
            assertEquals(Set.of("B"), drawing.targets("A", direction), drawing.diagnostic(direction));
            assertEquals(1, drawing.arrowheads("A", direction).size(), drawing.diagnostic(direction));
        });
    }

    @Test void branchAndJoinKeepEachDirectEdgeDistinctFromTransitiveReachability() {
        everyDirection(direction -> {
            var drawing = drawing(direction, List.of("A", "B", "C", "D"), List.of(
                edge("A", "B", ""), edge("A", "C", ""),
                edge("B", "D", ""), edge("C", "D", "")));
            assertEquals(Set.of("B", "C"), drawing.targets("A", direction), drawing.diagnostic(direction));
            assertEquals(Set.of("D"), drawing.targets("B", direction), drawing.diagnostic(direction));
            assertEquals(Set.of("D"), drawing.targets("C", direction), drawing.diagnostic(direction));
        });
    }

    @Test void denseMixedStrokeCrossingsPreserveEachSourcesExactThreeTargets() {
        everyDirection(direction -> {
            var sources = List.of("A", "B", "C", "D");
            var targets = List.of("W", "X", "Y", "Z");
            var edges = new ArrayList<Diagram.Edge>();
            for (int from = 0; from < sources.size(); from++) {
                for (int to = 0; to < targets.size(); to++) {
                    if (from != to) edges.add(new Diagram.Edge(sources.get(from), targets.get(to), "",
                        (from + to) % 2 == 0));
                }
            }
            var drawing = drawing(direction, List.of("A", "B", "C", "D", "W", "X", "Y", "Z"), edges);
            assertTrue(drawing.text().contains("╪"), "Dense fixture must exercise a nonjoining crossing\n"
                + drawing.diagnostic(direction));
            assertTrue(drawing.text().contains("╌") || drawing.text().contains("┊"),
                "Mixed routes must retain a visible dashed stroke\n" + drawing.diagnostic(direction));
            for (int index = 0; index < sources.size(); index++) {
                var expected = new HashSet<>(targets);
                expected.remove(targets.get(index));
                assertEquals(expected, drawing.targets(sources.get(index), direction), drawing.diagnostic(direction));
                assertEquals(3, drawing.arrowheads(sources.get(index), direction).size(), drawing.diagnostic(direction));
            }
        });
    }

    @Test void decisionHasADiamondSilhouetteRatherThanAMarkerInsideARectangle() {
        var diagram = new Diagram.Flowchart("", Diagram.Direction.TD,
            List.of(new Diagram.Node("A", "Decide", Diagram.Shape.DECISION)), List.of());
        var lines = MermaidLayout.render(diagram, 80);
        String text = String.join("\n", lines);
        assertFalse(text.contains("[Mermaid:"), text);
        assertEquals(1, occurrences(text, "Decide"), text);
        var nonempty = lines.stream().filter(line -> !line.isBlank()).toList();
        var middle = nonempty.stream().filter(line -> line.contains("Decide")).findFirst().orElseThrow();
        int middleWidth = middle.strip().length();
        assertTrue(nonempty.getFirst().strip().length() < middleWidth, text);
        assertTrue(nonempty.getLast().strip().length() < middleWidth, text);
    }

    private static void everyDirection(Consumer<Diagram.Direction> assertion) {
        assertAll(Arrays.stream(Diagram.Direction.values())
            .map(direction -> (Executable) () -> assertion.accept(direction)));
    }

    private static Diagram.Edge edge(String from, String to, String label) {
        return new Diagram.Edge(from, to, label, false);
    }

    private static Drawing drawing(Diagram.Direction direction, List<String> ids, List<Diagram.Edge> edges) {
        var nodes = ids.stream().map(id -> new Diagram.Node(id, "BOX_" + id, Diagram.Shape.BOX)).toList();
        return new Drawing(MermaidLayout.render(new Diagram.Flowchart("", direction, nodes, edges), 80), nodes);
    }

    private record Point(int x, int y) {
        Point next(Move move) { return new Point(x + move.dx, y + move.dy); }
    }
    private record Bounds(int left, int top, int right, int bottom) {
        boolean contains(Point point) {
            return point.x >= left && point.x <= right && point.y >= top && point.y <= bottom;
        }
        int area() { return (right - left + 1) * (bottom - top + 1); }
    }
    private record Head(Point cell, String destination) {}
    private record Step(Point cell, Move travel) {}
    private enum Move {
        UP(0, -1, 1), RIGHT(1, 0, 2), DOWN(0, 1, 4), LEFT(-1, 0, 8);
        final int dx, dy, bit;
        Move(int dx, int dy, int bit) { this.dx = dx; this.dy = dy; this.bit = bit; }
        Move opposite() { return values()[(ordinal() + 2) % 4]; }
        static Move forward(Diagram.Direction direction) {
            return switch (direction) { case LR -> RIGHT; case RL -> LEFT; case TD -> DOWN; case BT -> UP; };
        }
    }

    /** ASCII fixture captions make char coordinates identical to terminal-cell coordinates. */
    private static final class Drawing {
        private final List<String> lines;
        private final Map<String, Bounds> nodes = new LinkedHashMap<>();
        private final int width;
        Drawing(List<String> lines, List<Diagram.Node> modelNodes) {
            this.lines = lines;
            width = lines.stream().mapToInt(String::length).max().orElse(0);
            assertFalse(text().contains("[Mermaid:"), text());
            assertTrue(width > 0 && width <= 1_024 && lines.size() <= 8_192, text());
            for (var node : modelNodes) nodes.put(node.id(), enclosingBox(node.label()));
        }
        String text() { return String.join("\n", lines); }
        String diagnostic(Diagram.Direction direction) { return direction + "\n" + text(); }
        char at(Point point) {
            return point.y < 0 || point.y >= lines.size() || point.x < 0 || point.x >= lines.get(point.y).length()
                ? ' ' : lines.get(point.y).charAt(point.x);
        }
        boolean inside(Point point) { return nodes.values().stream().anyMatch(bounds -> bounds.contains(point)); }

        Bounds enclosingBox(String caption) {
            assertEquals(1, occurrences(text(), caption), text());
            int row = 0;
            while (!lines.get(row).contains(caption)) row++;
            int column = lines.get(row).indexOf(caption);
            List<Bounds> candidates = new ArrayList<>();
            for (int top = 0; top < row; top++) for (int left = 0; left < column; left++) {
                char corner = at(new Point(left, top));
                if (corner != '┌' && corner != '╭') continue;
                for (int right = column + caption.length(); right < width; right++) {
                    char upperRight = at(new Point(right, top));
                    if (upperRight != '┐' && upperRight != '╮') continue;
                    for (int bottom = row + 1; bottom < lines.size(); bottom++) {
                        char lowerLeft = at(new Point(left, bottom)), lowerRight = at(new Point(right, bottom));
                        if ((lowerLeft == '└' || lowerLeft == '╰') && (lowerRight == '┘' || lowerRight == '╯'))
                            candidates.add(new Bounds(left, top, right, bottom));
                    }
                }
            }
            return candidates.stream().min(java.util.Comparator.comparingInt(Bounds::area)).orElseThrow(
                () -> new AssertionError("Missing box around " + caption + "\n" + text()));
        }

        Set<String> targets(String source, Diagram.Direction direction) {
            Set<String> result = new HashSet<>();
            for (var head : arrowheads(source, direction)) result.add(head.destination());
            return Set.copyOf(result);
        }

        Set<Head> arrowheads(String source, Diagram.Direction direction) {
            var forward = Move.forward(direction);
            var from = nodes.get(source);
            var queue = new ArrayDeque<Step>();
            // Start outside each possible outgoing port; outlines never connect separate routes.
            if (forward == Move.RIGHT || forward == Move.LEFT) {
                int x = forward == Move.RIGHT ? from.right + 1 : from.left - 1;
                for (int y = from.top; y <= from.bottom; y++) queue.add(new Step(new Point(x, y), forward));
            } else {
                int y = forward == Move.DOWN ? from.bottom + 1 : from.top - 1;
                for (int x = from.left; x <= from.right; x++) queue.add(new Step(new Point(x, y), forward));
            }
            Set<Step> visited = new HashSet<>();
            Set<Head> heads = new HashSet<>();
            while (!queue.isEmpty()) {
                var step = queue.removeFirst();
                if (!visited.add(step) || inside(step.cell)) continue;
                assertTrue(visited.size() <= 100_000, "Topology traversal exceeded its fixture budget");
                char glyph = at(step.cell);
                Move arrow = arrow(glyph);
                if (arrow != null) {
                    if (arrow == step.travel) {
                        Point destination = step.cell.next(arrow);
                        nodes.forEach((id, bounds) -> {
                            if (!id.equals(source) && bounds.contains(destination)) heads.add(new Head(step.cell, id));
                        });
                    }
                    continue;
                }
                int ports = ports(glyph);
                if ((ports & step.travel.opposite().bit) == 0) continue;
                for (var exit : Move.values()) {
                    if (exit == step.travel.opposite() || (ports & exit.bit) == 0) continue;
                    // ╪/╫ encode two crossing strokes, not a graph junction: arrival fixes the exit axis.
                    if ((glyph == '╪' || glyph == '╫') && exit != step.travel) continue;
                    queue.addLast(new Step(step.cell.next(exit), exit));
                }
            }
            return Set.copyOf(heads);
        }
    }

    private static Move arrow(char glyph) {
        return switch (glyph) { case '→' -> Move.RIGHT; case '←' -> Move.LEFT; case '↓' -> Move.DOWN; case '↑' -> Move.UP;
            default -> null; };
    }
    private static int ports(char glyph) {
        return switch (glyph) {
            case '─', '╌', '┄', '┅', '┈', '┉' -> 2 | 8;
            case '│', '┊', '┆' -> 1 | 4;
            case '┌', '╭' -> 2 | 4;
            case '┐', '╮' -> 4 | 8;
            case '└', '╰' -> 1 | 2;
            case '┘', '╯' -> 1 | 8;
            case '├' -> 1 | 2 | 4;
            case '┤' -> 1 | 4 | 8;
            case '┬' -> 2 | 4 | 8;
            case '┴' -> 1 | 2 | 8;
            case '┼', '╪', '╫' -> 1 | 2 | 4 | 8;
            default -> 0;
        };
    }
    private static int occurrences(String text, String token) {
        int count = 0;
        for (int offset = 0; (offset = text.indexOf(token, offset)) >= 0; offset += token.length()) count++;
        return count;
    }
}
