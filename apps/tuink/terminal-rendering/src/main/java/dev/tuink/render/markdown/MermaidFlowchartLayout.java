package dev.tuink.render.markdown;

import static dev.tuink.render.markdown.MermaidLayout.checkCancellation;
import static dev.tuink.render.markdown.MermaidLayout.wrap;

import dev.tuiframe.cell.CellText;
import dev.tuink.markdown.mermaid.Diagram;
import dev.tuink.render.markdown.MermaidLayout.Grid;
import dev.tuink.render.markdown.MermaidLayout.LayoutLimit;
import dev.tuink.render.markdown.MermaidLayout.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Layered DAG placement, distinct ports and collision-checked orthogonal routes. */
final class MermaidFlowchartLayout {
    private MermaidFlowchartLayout() {}

    static List<String> render(Diagram.Flowchart graph, int labelWidth) {
        if (graph.nodes().isEmpty()) throw new LayoutLimit("Flowchart contains no nodes");
        if (graph.nodes().size() > 1_024 || graph.edges().size() > 4_096)
            throw new LayoutLimit("Mermaid graph budget exceeded");
        boolean horizontal = graph.direction() == Diagram.Direction.LR || graph.direction() == Diagram.Direction.RL;
        Map<String, Diagram.Node> definitions = new LinkedHashMap<>();
        Map<String, List<Link>> incoming = new HashMap<>(), outgoing = new HashMap<>();
        for (var node : graph.nodes()) {
            if (definitions.put(node.id(), node) != null) throw new LayoutLimit("Duplicate Mermaid node identity");
            incoming.put(node.id(), new ArrayList<>()); outgoing.put(node.id(), new ArrayList<>());
        }
        List<Link> links = new ArrayList<>();
        int labelHeight = 0, labelExtent = 0;
        for (var edge : graph.edges()) {
            checkCancellation();
            if (!definitions.containsKey(edge.from()) || !definitions.containsKey(edge.to()))
                throw new LayoutLimit("Mermaid edge refers to an unknown node");
            var label = edge.label().isEmpty() ? List.<String>of() : wrap(edge.label(), labelWidth);
            var link = new Link(edge, label);
            links.add(link); incoming.get(edge.to()).add(link); outgoing.get(edge.from()).add(link);
            labelExtent = Math.max(labelExtent, link.labelWidth);
            labelHeight = Math.max(labelHeight, label.size());
        }
        Map<String, Integer> ranks = ranks(definitions, incoming, outgoing);
        int count = ranks.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        List<List<String>> layers = new ArrayList<>();
        for (int rank = 0; rank < count; rank++) layers.add(new ArrayList<>());
        definitions.keySet().forEach(id -> layers.get(ranks.get(id)).add(id));
        orderLayers(layers, incoming, outgoing);

        // Even pitches keep fan-out/fan-in symmetric and reserve enough room for labels.
        int pitch = ((Math.max(2, horizontal ? labelHeight + 1 : labelExtent + 2) + 1) / 2) * 2;
        int nodeGap = Math.max(3, horizontal ? labelHeight + 2 : labelExtent + 2);
        Map<String, MermaidNodeBox> boxes = new LinkedHashMap<>();
        for (var node : definitions.values()) {
            int ports = Math.max(incoming.get(node.id()).size(), outgoing.get(node.id()).size());
            int span = Math.max(3, (ports - 1) * pitch + 3);
            boxes.put(node.id(), new MermaidNodeBox(node, labelWidth, horizontal ? 3 : span, horizontal ? span : 3));
        }
        int[] outUses = new int[Math.max(1, count)], inUses = new int[Math.max(1, count)];
        int[] gapLabels = new int[Math.max(1, count)];
        for (var link : links) {
            link.sourceRank = ranks.get(link.edge.from()); link.targetRank = ranks.get(link.edge.to());
            link.sourceIndex = outUses[link.sourceRank]++;
            link.targetIndex = inUses[link.targetRank - 1]++;
            gapLabels[link.targetRank - 1] = Math.max(gapLabels[link.targetRank - 1],
                horizontal ? link.labelWidth : link.label.size());
        }
        int[] starts = new int[count], extents = new int[count], spans = new int[count];
        int primary = 2, largestSpan = 1;
        for (int rank = 0; rank < count; rank++) {
            var layer = layers.get(rank);
            int extent = 1, span = 0;
            for (String id : layer) {
                var box = boxes.get(id);
                extent = Math.max(extent, horizontal ? box.width : box.height);
                span += horizontal ? box.height : box.width;
            }
            span += Math.max(0, layer.size() - 1) * nodeGap;
            starts[rank] = primary; extents[rank] = extent; spans[rank] = span;
            largestSpan = Math.max(largestSpan, span);
            primary += extent + (rank == count - 1 ? 2
                : 2 * (outUses[rank] + inUses[rank]) + gapLabels[rank] + 2);
        }
        int secondaryStart = 2 * links.size() + (horizontal ? labelHeight : 0) + 3;
        int secondaryExtent = secondaryStart + largestSpan + (horizontal ? 2 : labelExtent + 4);
        var grid = new Grid(horizontal ? primary : secondaryExtent, horizontal ? secondaryExtent : primary);
        for (int rank = 0; rank < count; rank++) {
            int position = secondaryStart + largestSpan / 2 - spans[rank] / 2;
            for (String id : layers.get(rank)) {
                var box = boxes.get(id);
                box.x = horizontal ? starts[rank] + extents[rank] / 2 - box.width / 2 : position;
                box.y = horizontal ? position : starts[rank] + extents[rank] / 2 - box.height / 2;
                position += (horizontal ? box.height : box.width) + nodeGap;
                for (int row = box.y; row < box.y + box.height; row++)
                    grid.reserve(box.leftAt(row), row, box.rightAt(row) - box.leftAt(row) + 1, 1);
            }
        }
        for (String id : definitions.keySet()) {
            var box = boxes.get(id);
            var from = outgoing.get(id); var to = incoming.get(id);
            from.sort(Comparator.comparingInt(link -> secondaryCenter(boxes.get(link.edge.to()), horizontal)));
            to.sort(Comparator.comparingInt(link -> secondaryCenter(boxes.get(link.edge.from()), horizontal)));
            for (int index = 0; index < from.size(); index++)
                from.get(index).start = port(box, index, from.size(), pitch, horizontal, true);
            for (int index = 0; index < to.size(); index++)
                to.get(index).end = port(box, index, to.size(), pitch, horizontal, false);
        }
        for (var link : links) {
            link.sourceTrack = starts[link.sourceRank] + extents[link.sourceRank] + 1 + 2 * link.sourceIndex;
            link.targetTrack = starts[link.targetRank] - gapLabels[link.targetRank - 1] - 3 - 2 * link.targetIndex;
            if (!link.label.isEmpty()) {
                link.labelX = horizontal ? starts[link.targetRank] - link.labelWidth - 2 : link.end.x() + 2;
                link.labelY = horizontal ? link.end.y() - link.label.size()
                    : starts[link.targetRank] - link.label.size() - 2;
                grid.reserve(link.labelX, link.labelY, link.labelWidth, link.label.size());
            }
        }
        // Start with safe independent outside lanes, then shorten routes against
        // the complete graph. Greedy short routes must never block a later edge.
        int outer = 0;
        for (var link : links) {
            checkCancellation();
            int lane = secondaryStart - (horizontal ? labelHeight : 0) - 2 - 2 * outer++;
            link.points = outside(link, lane, horizontal);
            link.route = grid.route(link.points, link.edge.dashed());
            if (link.route == null) throw new LayoutLimit("Unable to route Mermaid connections without overlap");
        }
        for (var link : links) {
            if (link.targetRank == link.sourceRank + 1) {
                grid.remove(link.route);
                Grid.Route shortened = null;
                int a = secondary(link.start, horizontal), b = secondary(link.end, horizontal);
                for (int track : new int[] {link.sourceTrack, link.targetTrack}) {
                    shortened = grid.route(List.of(link.start, point(track, a, horizontal), point(track, b, horizontal), link.end), link.edge.dashed());
                    if (shortened != null) break;
                }
                link.shortened = shortened != null;
                link.route = shortened != null ? shortened : grid.route(link.points, link.edge.dashed());
                if (link.route == null) throw new LayoutLimit("Unable to restore Mermaid connection");
            }
        }
        // Remove unused lane spacing after successful shortening.
        for (var link : links) if (!link.shortened) grid.remove(link.route);
        outer = 0;
        for (var link : links) if (!link.shortened) {
            int lane = secondaryStart - (horizontal ? labelHeight : 0) - 2 - 2 * outer++;
            link.route = grid.route(outside(link, lane, horizontal), link.edge.dashed());
            if (link.route == null) throw new LayoutLimit("Unable to compact Mermaid connection lanes");
        }
        grid.mirrorPaths(graph.direction());
        for (var link : links) {
            Point end = transform(link.end, graph.direction(), grid.width, grid.height);
            grid.put(end.x(), end.y(), switch (graph.direction()) { case LR -> "→"; case RL -> "←"; case TD -> "↓"; case BT -> "↑"; });
            if (!link.label.isEmpty()) {
                Point at = transformRectangle(link.labelX, link.labelY, link.labelWidth, link.label.size(), graph.direction(), grid.width, grid.height);
                for (int row = 0; row < link.label.size(); row++) grid.text(at.x(), at.y() + row, link.label.get(row));
            }
        }
        for (var box : boxes.values()) {
            Point at = transformRectangle(box.x, box.y, box.width, box.height, graph.direction(), grid.width, grid.height);
            box.x = at.x(); box.y = at.y(); box.draw(grid);
        }
        // Rectangular ports connect the frame to the path; diamond walls retain their slope.
        for (var link : links) {
            if (definitions.get(link.edge.from()).shape() != Diagram.Shape.DECISION)
                framePort(grid, transform(link.start, graph.direction(), grid.width, grid.height), graph.direction(), true);
            if (definitions.get(link.edge.to()).shape() != Diagram.Shape.DECISION)
                framePort(grid, transform(link.end, graph.direction(), grid.width, grid.height), graph.direction(), false);
        }
        return grid.compactLines();
    }

    private static Map<String, Integer> ranks(Map<String, Diagram.Node> nodes, Map<String, List<Link>> incoming, Map<String, List<Link>> outgoing) {
        Map<String, Integer> degree = new HashMap<>(), ranks = new HashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (String id : nodes.keySet()) { degree.put(id, incoming.get(id).size()); ranks.put(id, 0); if (incoming.get(id).isEmpty()) queue.add(id); }
        int visited = 0;
        while (!queue.isEmpty()) {
            checkCancellation(); String id = queue.removeFirst(); visited++;
            for (var link : outgoing.get(id)) {
                String to = link.edge.to(); ranks.put(to, Math.max(ranks.get(to), ranks.get(id) + 1));
                if (degree.compute(to, (key, value) -> value - 1) == 0) queue.addLast(to);
            }
        }
        if (visited != nodes.size()) throw new LayoutLimit("Cyclic flowcharts are outside the current Mermaid subset");
        return ranks;
    }

    private static void orderLayers(List<List<String>> layers, Map<String, List<Link>> incoming, Map<String, List<Link>> outgoing) {
        Map<String, Double> positions = new HashMap<>();
        for (var layer : layers) updatePositions(layer, positions);
        for (int pass = 0; pass < 2; pass++) {
            for (int rank = 1; rank < layers.size(); rank++) {
                var layer = layers.get(rank);
                layer.sort(Comparator.comparingDouble(id -> incoming.get(id).stream().mapToDouble(link -> positions.get(link.edge.from())).average().orElse(positions.get(id))));
                updatePositions(layer, positions);
            }
            for (int rank = layers.size() - 2; rank >= 0; rank--) {
                var layer = layers.get(rank);
                layer.sort(Comparator.comparingDouble(id -> outgoing.get(id).stream().mapToDouble(link -> positions.get(link.edge.to())).average().orElse(positions.get(id))));
                updatePositions(layer, positions);
            }
        }
    }
    private static void updatePositions(List<String> layer, Map<String, Double> positions) {
        for (int index = 0; index < layer.size(); index++) positions.put(layer.get(index), (index + 0.5) / layer.size());
    }
    private static Point port(MermaidNodeBox box, int index, int count, int pitch, boolean horizontal, boolean source) {
        int offset = (index * 2 - count + 1) * pitch / 2;
        if (horizontal) { int y = box.centerY() + offset; return new Point(source ? box.rightAt(y) + 1 : box.leftAt(y) - 1, y); }
        int x = box.centerX() + offset; return new Point(x, source ? box.bottomAt(x) + 1 : box.topAt(x) - 1);
    }
    private static int secondaryCenter(MermaidNodeBox box, boolean horizontal) { return horizontal ? box.centerY() : box.centerX(); }
    private static List<Point> outside(Link link, int lane, boolean horizontal) {
        int a = secondary(link.start, horizontal), b = secondary(link.end, horizontal);
        return List.of(link.start, point(link.sourceTrack, a, horizontal), point(link.sourceTrack, lane, horizontal),
            point(link.targetTrack, lane, horizontal), point(link.targetTrack, b, horizontal), link.end);
    }
    private static int secondary(Point p, boolean horizontal) { return horizontal ? p.y() : p.x(); }
    private static Point point(int primary, int secondary, boolean horizontal) { return horizontal ? new Point(primary, secondary) : new Point(secondary, primary); }
    private static Point transform(Point p, Diagram.Direction direction, int width, int height) {
        return new Point(direction == Diagram.Direction.RL ? width - 1 - p.x() : p.x(), direction == Diagram.Direction.BT ? height - 1 - p.y() : p.y());
    }
    private static Point transformRectangle(int x, int y, int w, int h, Diagram.Direction direction, int width, int height) {
        return new Point(direction == Diagram.Direction.RL ? width - x - w : x, direction == Diagram.Direction.BT ? height - y - h : y);
    }
    private static void framePort(Grid grid, Point outside, Diagram.Direction direction, boolean source) {
        int dx = switch (direction) { case LR -> 1; case RL -> -1; default -> 0; };
        int dy = switch (direction) { case TD -> 1; case BT -> -1; default -> 0; };
        int sign = source ? -1 : 1;
        String glyph = dx != 0 ? (dx * sign < 0 ? "├" : "┤") : (dy * sign < 0 ? "┬" : "┴");
        grid.put(outside.x() + dx * sign, outside.y() + dy * sign, glyph);
    }
    private static final class Link {
        final Diagram.Edge edge; final List<String> label; final int labelWidth;
        int sourceRank, targetRank, sourceIndex, targetIndex, sourceTrack, targetTrack, labelX, labelY;
        Point start, end;
        List<Point> points;
        Grid.Route route;
        boolean shortened;
        Link(Diagram.Edge edge, List<String> label) { this.edge = edge; this.label = label; labelWidth = label.stream().mapToInt(CellText::width).max().orElse(0); }
    }
}
