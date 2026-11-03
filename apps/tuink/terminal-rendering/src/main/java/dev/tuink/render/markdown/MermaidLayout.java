package dev.tuink.render.markdown;

import dev.tuiframe.cell.CellText;
import dev.tuiframe.text.TerminalText;
import dev.tuink.markdown.mermaid.Diagram;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded terminal-cell layout over the neutral Mermaid model. No parser or UI state is retained. */
public final class MermaidLayout {
    private static final int MAX_WIDTH = 1_024;
    private static final int MAX_HEIGHT = 8_192;
    private static final long MAX_CELLS = 1_048_576L;

    private MermaidLayout() {}

    /**
     * The width is a wrapping hint, not a clipping boundary. Wider diagrams retain
     * their complete structure so the containing preview can scroll horizontally.
     */
    public static List<String> render(Diagram diagram, int width) {
        Objects.requireNonNull(diagram, "diagram");
        if (diagram instanceof Diagram.Unsupported unavailable) {
            return fallback(diagram.source(), unavailable.diagnostic());
        }
        try {
            int labelWidth = Math.max(8, Math.min(28, Math.max(1, width) / 3));
            if (diagram instanceof Diagram.Flowchart flowchart) return MermaidFlowchartLayout.render(flowchart, labelWidth);
            if (diagram instanceof Diagram.Sequence sequence) return MermaidSequenceLayout.render(sequence, labelWidth * 2);
            return fallback(diagram.source(), "Unsupported Mermaid model");
        } catch (LayoutLimit limit) {
            return fallback(diagram.source(), limit.getMessage());
        }
    }

    static void drawBox(Grid grid, int x, int y, int width, List<String> label, boolean rounded) {
        grid.text(x, y, (rounded ? "╭" : "┌") + "─".repeat(width - 2) + (rounded ? "╮" : "┐"));
        for (int row = 0; row < label.size(); row++) {
            grid.text(x, y + row + 1, "│ " + CellText.fit(label.get(row), width - 4) + " │");
        }
        grid.text(x, y + label.size() + 1, (rounded ? "╰" : "└") + "─".repeat(width - 2) + (rounded ? "╯" : "┘"));
    }

    static List<String> wrap(String text, int columns) {
        List<String> result = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) { result.add(""); continue; }
            StringBuilder current = new StringBuilder();
            int cells = 0;
            TerminalText.GraphemeCursor graphemes = TerminalText.graphemeCursor(line);
            while (graphemes.advance()) {
                int advance = graphemes.isTab() ? 4 - cells % 4 : Math.max(1, graphemes.width());
                while (cells > 0 && cells + advance > columns) {
                    int space = current.lastIndexOf(" ");
                    if (space > 0) {
                        result.add(current.substring(0, space).stripTrailing());
                        String tail = current.substring(space + 1).stripLeading();
                        current.setLength(0); current.append(tail);
                        cells = CellText.width(tail);
                    } else {
                        result.add(current.toString());
                        current.setLength(0); cells = 0;
                    }
                    if (result.size() > 128) throw new LayoutLimit("Mermaid label height exceeds the terminal budget");
                    if (graphemes.isTab()) advance = 4 - cells % 4;
                }
                current.append(graphemes.isTab() ? " ".repeat(advance)
                    : TerminalText.sanitizeGrapheme(line, graphemes.start(), graphemes.end()));
                cells += advance;
            }
            result.add(current.toString());
        }
        if (result.size() > 128) throw new LayoutLimit("Mermaid label height exceeds the terminal budget");
        return List.copyOf(result);
    }

    private static List<String> fallback(String source, String diagnostic) {
        List<String> lines = new ArrayList<>();
        lines.add("[Mermaid: " + diagnostic + "]");
        int remaining = 65_536;
        String available = source.substring(0, Math.min(source.length(), remaining));
        for (String line : available.split("\n", -1)) {
            if (lines.size() >= 1_024 || remaining <= 0) { lines.add("…"); break; }
            String clipped = line.length() > remaining ? line.substring(0, remaining) : line;
            lines.add(clipped);
            remaining -= clipped.length();
        }
        if (source.length() > available.length() && !lines.get(lines.size() - 1).equals("…")) lines.add("…");
        return List.copyOf(lines);
    }

    static void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) throw new LayoutLimit("Mermaid layout cancelled");
    }

    record Point(int x, int y) {}
    static final class LayoutLimit extends RuntimeException {
        LayoutLimit(String message) { super(message); }
    }

    static final class Grid {
        final int width;
        final int height;
        final int[][] paths;
        final boolean[][] dashed;
        final boolean[][] reserved;
        final boolean[][] crossings;
        final String[][] ink;
        private long routeSteps = 8_388_608L;
        private final Map<Integer, CrossingStyle> crossingStyles = new HashMap<>();
        private boolean routesFinalized;

        Grid(int width, int height) {
            if (width < 1 || height < 1 || width > MAX_WIDTH || height > MAX_HEIGHT
                    || (long) width * height > MAX_CELLS) {
                throw new LayoutLimit("Diagram exceeds the terminal layout budget");
            }
            this.width = width; this.height = height;
            paths = new int[height][width];
            dashed = new boolean[height][width];
            reserved = new boolean[height][width];
            crossings = new boolean[height][width];
            ink = new String[height][width];
        }

        void reserve(int x, int y, int w, int h) {
            if (routesFinalized) throw new IllegalStateException("Mermaid routes have already been finalized");
            if (x < 0 || y < 0 || w < 0 || h < 0 || (long)x + w > width || (long)y + h > height)
                throw new LayoutLimit("Mermaid element exceeded its layout bounds");
            for (int row = y; row < y + h; row++) for (int col = x; col < x + w; col++) {
                if (reserved[row][col]) throw new LayoutLimit("Mermaid elements overlap");
                reserved[row][col] = true;
            }
        }

        /** A crossing joins neither edge: only straight paths may cross, never corners or shared segments. */
        Route route(List<Point> points, boolean dash) {
            if (routesFinalized) throw new IllegalStateException("Mermaid routes have already been finalized");
            List<RouteCell> cells = trace(points);
            if (cells == null) return null;
            for (var cell : cells) {
                int previous = paths[cell.y][cell.x];
                if (reserved[cell.y][cell.x] || ink[cell.y][cell.x] != null || crossings[cell.y][cell.x]) return null;
                if (previous != 0 && !((previous == 5 && cell.bits == 10) || (previous == 10 && cell.bits == 5))) return null;
            }
            for (var cell : cells) {
                int previous = paths[cell.y][cell.x];
                if (previous != 0) crossingStyles.put(cell.y * width + cell.x,
                    previous == 5 ? new CrossingStyle(dash, dashed[cell.y][cell.x])
                        : new CrossingStyle(dashed[cell.y][cell.x], dash));
                crossings[cell.y][cell.x] = previous != 0;
                dashed[cell.y][cell.x] = previous == 0 ? dash : dashed[cell.y][cell.x] && dash;
                paths[cell.y][cell.x] |= cell.bits;
            }
            return new Route(List.copyOf(points), dash);
        }

        private List<RouteCell> trace(List<Point> points) {
            List<RouteCell> cells = new ArrayList<>();
            for (int index = 1; index < points.size(); index++) {
                Point from = points.get(index - 1), to = points.get(index);
                if (from.x != to.x && from.y != to.y) throw new LayoutLimit("Non-orthogonal Mermaid route");
                int dx = Integer.compare(to.x, from.x), dy = Integer.compare(to.y, from.y);
                int length = Math.abs(to.x - from.x) + Math.abs(to.y - from.y);
                if (length == 0) continue;
                for (int step = 0; step <= length; step++) {
                    if (--routeSteps < 0) throw new LayoutLimit("Mermaid routing work budget exceeded");
                    if ((routeSteps & 255) == 0) checkCancellation();
                    int x = from.x + step * dx, y = from.y + step * dy;
                    if (x < 0 || y < 0 || x >= width || y >= height) return null;
                    int forward = dx > 0 ? 2 : dx < 0 ? 8 : dy > 0 ? 4 : 1;
                    int backward = dx > 0 ? 8 : dx < 0 ? 2 : dy > 0 ? 1 : 4;
                    int bits = (step < length ? forward : 0) | (step > 0 ? backward : 0);
                    int last = cells.size() - 1;
                    if (last >= 0 && cells.get(last).x == x && cells.get(last).y == y)
                        cells.set(last, new RouteCell(x, y, cells.get(last).bits | bits));
                    else cells.add(new RouteCell(x, y, bits));
                }
            }
            return cells;
        }

        private record RouteCell(int x, int y, int bits) {}
        record Route(List<Point> points, boolean dashed) {}
        private record CrossingStyle(boolean horizontalDashed, boolean verticalDashed) {}

        void remove(Route route) {
            if (routesFinalized) throw new IllegalStateException("Mermaid routes have already been finalized");
            for (var cell : trace(route.points)) {
                int remaining = paths[cell.y][cell.x] & ~cell.bits;
                if (crossings[cell.y][cell.x]) {
                    var style = crossingStyles.remove(cell.y * width + cell.x);
                    dashed[cell.y][cell.x] = remaining == 5 ? style.verticalDashed : style.horizontalDashed;
                    crossings[cell.y][cell.x] = false;
                } else dashed[cell.y][cell.x] = false;
                paths[cell.y][cell.x] = remaining;
            }
        }

        /** Final drawing transform: route handles remain in planning coordinates and cannot be reused. */
        void mirrorPaths(Diagram.Direction direction) {
            if (routesFinalized) throw new IllegalStateException("Mermaid routes have already been finalized");
            routesFinalized = true;
            crossingStyles.clear();
            if (direction != Diagram.Direction.RL && direction != Diagram.Direction.BT) return;
            boolean horizontal = direction == Diagram.Direction.RL;
            int rows = horizontal ? height : (height + 1) / 2;
            int cols = horizontal ? (width + 1) / 2 : width;
            for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) {
                int oppositeX = horizontal ? width - 1 - x : x;
                int oppositeY = horizontal ? y : height - 1 - y;
                int a = mirrorBits(paths[y][x], horizontal), b = mirrorBits(paths[oppositeY][oppositeX], horizontal);
                boolean d = dashed[y][x], c = crossings[y][x];
                paths[y][x] = b; paths[oppositeY][oppositeX] = a;
                dashed[y][x] = dashed[oppositeY][oppositeX]; dashed[oppositeY][oppositeX] = d;
                crossings[y][x] = crossings[oppositeY][oppositeX]; crossings[oppositeY][oppositeX] = c;
            }
        }

        private static int mirrorBits(int bits, boolean horizontal) {
            return horizontal ? (bits & 5) | ((bits & 2) << 2) | ((bits & 8) >> 2)
                : (bits & 10) | ((bits & 1) << 2) | ((bits & 4) >> 2);
        }

        void put(int x, int y, String value) {
            if (x < 0 || x >= width || y < 0 || y >= height) throw new LayoutLimit("Diagram route exceeded its bounds");
            ink[y][x] = value;
        }

        void text(int x, int y, String value) {
            int column = x;
            TerminalText.GraphemeCursor cursor = TerminalText.graphemeCursor(value);
            while (cursor.advance()) {
                int advance = cursor.isTab() ? 4 - Math.floorMod(column - x, 4) : Math.max(1, cursor.width());
                if (column < 0 || y < 0 || y >= height || column + advance > width) {
                    throw new LayoutLimit("Diagram label exceeded its bounds");
                }
                if (cursor.isTab()) {
                    for (int cell = 0; cell < advance; cell++) put(column + cell, y, " ");
                } else {
                    put(column, y, TerminalText.sanitizeGrapheme(value, cursor.start(), cursor.end()));
                    for (int cell = 1; cell < advance; cell++) put(column + cell, y, "");
                }
                column += advance;
            }
        }

        void path(List<Point> points, boolean dash) {
            if (routesFinalized) throw new IllegalStateException("Mermaid routes have already been finalized");
            for (int index = 1; index < points.size(); index++) {
                Point from = points.get(index - 1);
                Point to = points.get(index);
                if (from.x != to.x && from.y != to.y) throw new LayoutLimit("Non-orthogonal Mermaid route");
                int dx = Integer.compare(to.x, from.x);
                int dy = Integer.compare(to.y, from.y);
                int length = Math.abs(to.x - from.x) + Math.abs(to.y - from.y);
                int x = from.x;
                int y = from.y;
                for (int step = 0; step <= length; step++, x += dx, y += dy) {
                    if (x < 0 || y < 0 || x >= width || y >= height) throw new LayoutLimit("Diagram route exceeded its bounds");
                    int forward = dx > 0 ? 2 : dx < 0 ? 8 : dy > 0 ? 4 : 1;
                    int backward = dx > 0 ? 8 : dx < 0 ? 2 : dy > 0 ? 1 : 4;
                    int bits = (step < length ? forward : 0) | (step > 0 ? backward : 0);
                    if (paths[y][x] == 0) dashed[y][x] = dash;
                    else dashed[y][x] &= dash;
                    paths[y][x] |= bits;
                }
            }
        }

        List<String> lines() {
            return lines(0);
        }

        /** Remove unused planning lanes while retaining the ordinary two-cell outer margin. */
        List<String> compactLines() {
            int first = width;
            for (int row = 0; row < height; row++) for (int col = 0; col < first; col++) {
                if (ink[row][col] == null ? paths[row][col] != 0 : !ink[row][col].isBlank()) {
                    first = col;
                    break;
                }
            }
            return lines(Math.max(0, first - 2));
        }

        private List<String> lines(int firstColumn) {
            List<String> result = new ArrayList<>(height);
            for (int row = 0; row < height; row++) {
                StringBuilder line = new StringBuilder();
                for (int col = firstColumn; col < width; col++) {
                    if (ink[row][col] != null) line.append(ink[row][col]);
                    else line.append(crossings[row][col] ? '╪' : glyph(paths[row][col], dashed[row][col]));
                }
                int end = line.length();
                while (end > 0 && line.charAt(end - 1) == ' ') end--;
                result.add(line.substring(0, end));
            }
            while (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) result.remove(result.size() - 1);
            while (!result.isEmpty() && result.get(0).isEmpty()) result.remove(0);
            return List.copyOf(result);
        }

        private static char glyph(int bits, boolean dashed) {
            return switch (bits) {
                case 0 -> ' ';
                case 1, 4, 5 -> dashed ? '┊' : '│';
                case 2, 8, 10 -> dashed ? '╌' : '─';
                case 6 -> '┌';
                case 12 -> '┐';
                case 3 -> '└';
                case 9 -> '┘';
                case 7 -> '├';
                case 13 -> '┤';
                case 11 -> '┴';
                case 14 -> '┬';
                default -> '┼';
            };
        }
    }
}
