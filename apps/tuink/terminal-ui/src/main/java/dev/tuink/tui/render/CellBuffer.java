package dev.tuink.tui.render;

import dev.tuink.render.Style;

/**
 * 2D frame buffer used by UI adapters (terminal, GUI, web).
 */
public class CellBuffer {
    private static final long HASH_SEED_1 = 0x9E3779B97F4A7C15L;
    private static final long HASH_SEED_2 = 0xD1B54A32D192ED03L;
    private final int rows;
    private final int cols;
    private final Cell[][] cells;
    private final long[] rowSignature1;
    private final long[] rowSignature2;

    public CellBuffer(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.cells = new Cell[rows][cols];
        this.rowSignature1 = new long[rows];
        this.rowSignature2 = new long[rows];
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
            long sig1 = 0L;
            long sig2 = 0L;
            for (int c = 0; c < cols; c++) {
                cells[r][c] = cell;
                sig1 ^= mix(cell, c, HASH_SEED_1);
                sig2 ^= mix(cell, c, HASH_SEED_2);
            }
            rowSignature1[r] = sig1;
            rowSignature2[r] = sig2;
        }
    }

    public void set(int row, int col, String grapheme, Style style) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            return;
        }
        Cell previous = cells[row][col];
        if (previous != null) {
            rowSignature1[row] ^= mix(previous, col, HASH_SEED_1);
            rowSignature2[row] ^= mix(previous, col, HASH_SEED_2);
        }
        Cell next = new Cell(grapheme, style);
        cells[row][col] = next;
        rowSignature1[row] ^= mix(next, col, HASH_SEED_1);
        rowSignature2[row] ^= mix(next, col, HASH_SEED_2);
    }

    public Cell get(int row, int col) {
        return cells[row][col];
    }

    public long rowSignature1(int row) {
        return rowSignature1[row];
    }

    public long rowSignature2(int row) {
        return rowSignature2[row];
    }

    private static long mix(Cell cell, int col, long seed) {
        long x = seed ^ ((long) cell.hashCode() * 0x9E3779B185EBCA87L) ^ (long) col * 0xC2B2AE3D27D4EB4FL;
        x ^= (x >>> 33);
        x *= 0xFF51AFD7ED558CCDL;
        x ^= (x >>> 33);
        x *= 0xC4CEB9FE1A85EC53L;
        x ^= (x >>> 33);
        return x;
    }
}
