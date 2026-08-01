package dev.tuink.terminal.cell;

import dev.tuink.terminal.style.CellStyle;

/**
 * 2D frame buffer used by UI adapters (terminal, GUI, web).
 */
public class CellBuffer {
    private static final long HASH_SEED_1 = 0x9E3779B97F4A7C15L;
    private static final long HASH_SEED_2 = 0xD1B54A32D192ED03L;
    private static final int EMPTY_CELL_HASH = Cell.EMPTY.hashCode();
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
        if (cell == null) {
            throw new IllegalArgumentException("cell must not be null");
        }
        for (int r = 0; r < rows; r++) {
            long sig1 = 0L;
            long sig2 = 0L;
            for (int c = 0; c < cols; c++) {
                Cell placed = cell.continuation() ? new Cell(" ", cell.style()) : cell;
                if (placed.width() == 2 && c + 1 >= cols) {
                    placed = new Cell("�", placed.style());
                }
                cells[r][c] = placed;
                int placedHash = signatureHash(placed);
                sig1 ^= mix(placedHash, c, HASH_SEED_1);
                sig2 ^= mix(placedHash, c, HASH_SEED_2);
                if (placed.width() == 2) {
                    Cell continuation = Cell.continuation(placed.style());
                    c++;
                    cells[r][c] = continuation;
                    int continuationHash = signatureHash(continuation);
                    sig1 ^= mix(continuationHash, c, HASH_SEED_1);
                    sig2 ^= mix(continuationHash, c, HASH_SEED_2);
                }
            }
            rowSignature1[r] = sig1;
            rowSignature2[r] = sig2;
        }
    }

    public void set(int row, int col, String grapheme, CellStyle style) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            return;
        }
        Cell next = new Cell(grapheme, style);
        if (next.width() == 2 && col + 1 >= cols) {
            next = new Cell("�", style);
        }
        clearFootprint(row, col);
        if (next.width() == 2) {
            clearFootprint(row, col + 1);
        }
        replace(row, col, next);
        if (next.width() == 2) {
            replace(row, col + 1, Cell.continuation(style));
        }
    }

    public boolean rowHasWideCells(int row) {
        for (int col = 0; col < cols; col++) {
            Cell cell = cells[row][col];
            if (cell.width() == 2 || cell.continuation()) {
                return true;
            }
        }
        return false;
    }

    public boolean regionHasWideCells(int top, int bottom, int fromCol, int toCol) {
        int safeTop = Math.max(0, top);
        int safeBottom = Math.min(rows - 1, bottom);
        int safeFrom = Math.max(0, fromCol);
        int safeTo = Math.min(cols - 1, toCol);
        for (int row = safeTop; row <= safeBottom; row++) {
            for (int col = safeFrom; col <= safeTo; col++) {
                Cell cell = cells[row][col];
                if (cell.width() == 2 || cell.continuation()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void clearFootprint(int row, int col) {
        if (col < 0 || col >= cols) {
            return;
        }
        Cell current = cells[row][col];
        if (current.continuation() && col > 0) {
            Cell lead = cells[row][col - 1];
            if (lead.width() == 2) {
                replace(row, col - 1, Cell.EMPTY);
            }
        } else if (current.width() == 2 && col + 1 < cols) {
            replace(row, col + 1, Cell.EMPTY);
        }
        replace(row, col, Cell.EMPTY);
    }

    private void replace(int row, int col, Cell next) {
        Cell previous = cells[row][col];
        if (previous == next) {
            return;
        }
        if (previous != null) {
            int previousHash = signatureHash(previous);
            rowSignature1[row] ^= mix(previousHash, col, HASH_SEED_1);
            rowSignature2[row] ^= mix(previousHash, col, HASH_SEED_2);
        }
        cells[row][col] = next;
        int nextHash = signatureHash(next);
        rowSignature1[row] ^= mix(nextHash, col, HASH_SEED_1);
        rowSignature2[row] ^= mix(nextHash, col, HASH_SEED_2);
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

    private static long mix(int cellHash, int col, long seed) {
        long x = seed ^ ((long) cellHash * 0x9E3779B185EBCA87L) ^ (long) col * 0xC2B2AE3D27D4EB4FL;
        x ^= (x >>> 33);
        x *= 0xFF51AFD7ED558CCDL;
        x ^= (x >>> 33);
        x *= 0xC4CEB9FE1A85EC53L;
        x ^= (x >>> 33);
        return x;
    }

    private static int signatureHash(Cell cell) {
        return cell == Cell.EMPTY ? EMPTY_CELL_HASH : cell.hashCode();
    }
}
