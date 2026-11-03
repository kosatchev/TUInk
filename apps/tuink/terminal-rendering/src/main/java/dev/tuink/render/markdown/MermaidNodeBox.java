package dev.tuink.render.markdown;

import dev.tuiframe.cell.CellText;
import dev.tuink.markdown.mermaid.Diagram;
import java.util.List;
import java.util.Objects;

/** Measured node geometry shared by flowchart placement, boundary ports and drawing. */
final class MermaidNodeBox {
    private static final int DECISION_LABEL_WIDTH = 12;

    final Diagram.Node node;
    final List<String> label;
    final int width;
    final int height;
    int x;
    int y;

    MermaidNodeBox(Diagram.Node node, int labelWidth, int minWidth, int minHeight) {
        this.node = Objects.requireNonNull(node, "node");
        boolean decision = node.shape() == Diagram.Shape.DECISION;
        label = MermaidLayout.wrap(node.label(), Math.max(1,
            decision ? Math.min(DECISION_LABEL_WIDTH, labelWidth) : labelWidth));
        if (decision) {
            int radius = Math.max(2, Math.max(Math.max(0, minWidth) / 2, Math.max(0, minHeight) / 2));
            for (int row = 0; row < label.size(); row++) {
                int offset = Math.abs(row - label.size() / 2);
                radius = Math.max(radius, offset + CellText.width(label.get(row)) / 2 + 2);
            }
            width = height = 2 * radius + 1;
        } else {
            width = Math.max(Math.max(4, minWidth), label.stream().mapToInt(CellText::width).max().orElse(0) + 4);
            height = Math.max(Math.max(3, minHeight), label.size() + 2);
        }
    }

    int centerX() { return x + width / 2; }
    int centerY() { return y + height / 2; }

    /** Absolute cell occupied by the outline at the requested absolute row or column. */
    int leftAt(int row) {
        checkRow(row);
        return node.shape() == Diagram.Shape.DECISION ? x + Math.abs(row - centerY()) : x;
    }
    int rightAt(int row) {
        checkRow(row);
        return node.shape() == Diagram.Shape.DECISION ? x + width - 1 - Math.abs(row - centerY()) : x + width - 1;
    }
    int topAt(int column) {
        checkColumn(column);
        return node.shape() == Diagram.Shape.DECISION ? y + Math.abs(column - centerX()) : y;
    }
    int bottomAt(int column) {
        checkColumn(column);
        return node.shape() == Diagram.Shape.DECISION ? y + height - 1 - Math.abs(column - centerX()) : y + height - 1;
    }

    void draw(MermaidLayout.Grid grid) {
        if (node.shape() == Diagram.Shape.DECISION) drawDecision(grid);
        else drawRectangle(grid, node.shape() == Diagram.Shape.ROUNDED);
        int firstRow = y + (height - label.size()) / 2;
        for (int row = 0; row < label.size(); row++) {
            String text = label.get(row);
            grid.text(x + (width - CellText.width(text)) / 2, firstRow + row, text);
        }
    }

    private void drawRectangle(MermaidLayout.Grid grid, boolean rounded) {
        grid.text(x, y, (rounded ? "╭" : "┌") + "─".repeat(width - 2) + (rounded ? "╮" : "┐"));
        for (int row = 1; row < height - 1; row++)
            grid.text(x, y + row, "│" + " ".repeat(width - 2) + "│");
        grid.text(x, y + height - 1, (rounded ? "╰" : "└") + "─".repeat(width - 2) + (rounded ? "╯" : "┘"));
    }

    private void drawDecision(MermaidLayout.Grid grid) {
        for (int row = y; row < y + height; row++) {
            int left = leftAt(row), right = rightAt(row);
            if (left == right) {
                grid.put(left, row, "◇");
                continue;
            }
            grid.text(left + 1, row, " ".repeat(right - left - 1));
            grid.put(left, row, row == centerY() ? "◇" : row < centerY() ? "╱" : "╲");
            grid.put(right, row, row == centerY() ? "◇" : row < centerY() ? "╲" : "╱");
        }
    }

    private void checkRow(int row) {
        if (row < y || row >= y + height) throw new IllegalArgumentException("Port row is outside its Mermaid node");
    }
    private void checkColumn(int column) {
        if (column < x || column >= x + width) throw new IllegalArgumentException("Port column is outside its Mermaid node");
    }
}
