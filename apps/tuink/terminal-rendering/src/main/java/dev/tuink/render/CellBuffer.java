package dev.tuink.render;

/**
 * 2D frame buffer used by UI adapters (terminal, GUI, web).
 */
public class CellBuffer {
    private final int rows;
    private final int cols;
    private final Cell[][] cells;

    public CellBuffer(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.cells = new Cell[rows][cols];
        fill(Cell.EMPTY);
    }

    public int rows() {
        return rows;
    }

    public int cols() {
        return cols;
    }

    public void fill(Cell cell) {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                cells[r][c] = cell;
            }
        }
    }

    public void set(int row, int col, String grapheme, Style style) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            return;
        }
        cells[row][col] = new Cell(grapheme, style);
    }

    public Cell get(int row, int col) {
        return cells[row][col];
    }
}
