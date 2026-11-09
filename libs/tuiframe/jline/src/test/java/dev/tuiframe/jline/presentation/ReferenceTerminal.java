package dev.tuiframe.jline.presentation;

import dev.tuiframe.cell.Cell;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalText;
import java.util.Arrays;

/**
 * Small test-only terminal model for the ANSI subset emitted by {@link AnsiDiff}.
 * It intentionally interprets patches instead of sharing diff implementation
 * details, so replay tests verify the resulting physical cell grid.
 */
final class ReferenceTerminal {
    private static final char ESC = '\u001B';

    private final int rows;
    private final int cols;
    private final Cell[][] cells;
    private int cursorRow;
    private int cursorCol;
    private int scrollTop;
    private int scrollBottom;
    private CellColor foreground = CellColor.DEFAULT;
    private CellColor background = CellColor.DEFAULT;
    private boolean bold;
    private boolean underline;
    private boolean inverse;

    ReferenceTerminal(int rows, int cols) {
        if (rows <= 0 || cols <= 0) {
            throw new IllegalArgumentException("reference terminal dimensions must be positive");
        }
        this.rows = rows;
        this.cols = cols;
        this.cells = new Cell[rows][cols];
        this.scrollBottom = rows - 1;
        Cell blank = new Cell(" ", CellStyle.DEFAULT);
        for (Cell[] row : cells) {
            Arrays.fill(row, blank);
        }
    }

    int rows() {
        return rows;
    }

    int cols() {
        return cols;
    }

    Cell cell(int row, int col) {
        return cells[row][col];
    }

    void apply(String ansi) {
        if (ansi == null) {
            throw new IllegalArgumentException("ansi must not be null");
        }
        int offset = 0;
        while (offset < ansi.length()) {
            int escape = ansi.indexOf(ESC, offset);
            if (escape < 0) {
                writeText(ansi.substring(offset));
                return;
            }
            if (escape > offset) {
                writeText(ansi.substring(offset, escape));
            }
            offset = applyCsi(ansi, escape);
        }
    }

    private int applyCsi(String ansi, int escape) {
        if (escape + 1 >= ansi.length() || ansi.charAt(escape + 1) != '[') {
            throw new IllegalArgumentException("unsupported escape at offset " + escape);
        }
        int finalOffset = escape + 2;
        while (finalOffset < ansi.length()) {
            char candidate = ansi.charAt(finalOffset);
            if (candidate >= 0x40 && candidate <= 0x7e) {
                break;
            }
            finalOffset++;
        }
        if (finalOffset >= ansi.length()) {
            throw new IllegalArgumentException("unterminated CSI at offset " + escape);
        }
        String parameters = ansi.substring(escape + 2, finalOffset);
        char command = ansi.charAt(finalOffset);
        switch (command) {
            case 'H' -> moveCursor(parameters);
            case 'm' -> selectGraphicRendition(parameters);
            case 'P' -> deleteCharacters(count(parameters));
            case '@' -> insertCharacters(count(parameters));
            case 'r' -> setScrollRegion(parameters);
            case 'S' -> scrollUp(count(parameters));
            case 'T' -> scrollDown(count(parameters));
            default -> throw new IllegalArgumentException(
                "unsupported CSI command " + command + " at offset " + escape
            );
        }
        return finalOffset + 1;
    }

    private void writeText(String text) {
        if (text.isEmpty()) {
            return;
        }
        for (TerminalText.Grapheme grapheme : TerminalText.graphemes(text)) {
            if (cursorRow < 0 || cursorRow >= rows || cursorCol < 0 || cursorCol >= cols) {
                throw new IllegalStateException(
                    "text write outside terminal at " + cursorCol + ',' + cursorRow
                );
            }
            Cell next = new Cell(grapheme.text(), activeStyle());
            if (next.width() == 2 && cursorCol + 1 >= cols) {
                throw new IllegalStateException("wide grapheme crosses the terminal edge");
            }
            clearFootprint(cursorRow, cursorCol);
            if (next.width() == 2) {
                clearFootprint(cursorRow, cursorCol + 1);
            }
            cells[cursorRow][cursorCol] = next;
            if (next.width() == 2) {
                cells[cursorRow][cursorCol + 1] = Cell.continuation(next.style());
            }
            cursorCol += next.width();
        }
    }

    private void moveCursor(String parameters) {
        String[] values = parameters.split(";", -1);
        int row = values.length > 0 && !values[0].isEmpty() ? positive(values[0]) : 1;
        int col = values.length > 1 && !values[1].isEmpty() ? positive(values[1]) : 1;
        if (values.length > 2 || row > rows || col > cols) {
            throw new IllegalArgumentException("cursor position outside reference terminal: " + parameters);
        }
        cursorRow = row - 1;
        cursorCol = col - 1;
    }

    private void selectGraphicRendition(String parameters) {
        String[] values = parameters.isEmpty() ? new String[] {"0"} : parameters.split(";", -1);
        for (int index = 0; index < values.length; index++) {
            String value = values[index];
            int code = value.isEmpty() ? 0 : nonNegative(value);
            switch (code) {
                case 0 -> resetStyle();
                case 1 -> bold = true;
                case 22 -> bold = false;
                case 4 -> underline = true;
                case 24 -> underline = false;
                case 7 -> inverse = true;
                case 27 -> inverse = false;
                case 30, 31, 32, 33, 34, 35, 36, 37,
                     39, 90, 91, 92, 93, 94, 95, 96, 97 -> foreground = foreground(code);
                case 38, 48 -> {
                    CellColor selected;
                    if (index + 2 < values.length && values[index + 1].equals("5")) {
                        selected = switch (nonNegative(values[index + 2])) {
                            case 234 -> CellColor.GRAY_028;
                            case 236 -> CellColor.DARK_GRAY;
                            case 244 -> CellColor.GRAY_128;
                            case 247 -> CellColor.GRAY_158;
                            case 250 -> CellColor.GRAY_188;
                            case 253 -> CellColor.GRAY_218;
                            case 255 -> CellColor.GRAY_238;
                            default -> throw new IllegalArgumentException("unsupported indexed SGR: " + parameters);
                        };
                        index += 2;
                    } else if (index + 4 < values.length && values[index + 1].equals("2")) {
                        int red = nonNegative(values[index + 2]);
                        if (red != nonNegative(values[index + 3]) || red != nonNegative(values[index + 4]))
                            throw new IllegalArgumentException("unsupported nonneutral RGB SGR: " + parameters);
                        selected = switch (red) {
                            case 28 -> CellColor.GRAY_028;
                            case 48 -> CellColor.DARK_GRAY;
                            case 128 -> CellColor.GRAY_128;
                            case 158 -> CellColor.GRAY_158;
                            case 188 -> CellColor.GRAY_188;
                            case 218 -> CellColor.GRAY_218;
                            case 238 -> CellColor.GRAY_238;
                            default -> throw new IllegalArgumentException("unsupported RGB SGR: " + parameters);
                        };
                        index += 4;
                    } else throw new IllegalArgumentException("unsupported extended SGR: " + parameters);
                    if (code == 38) foreground = selected;
                    else background = selected;
                }
                case 40, 41, 42, 43, 44, 45, 46, 47,
                     49, 100, 101, 102, 103, 104, 105, 106, 107 -> background = background(code);
                default -> throw new IllegalArgumentException("unsupported SGR code: " + code);
            }
        }
    }

    private void deleteCharacters(int requested) {
        requireCursorInBounds();
        int count = Math.min(requested, cols - cursorCol);
        for (int col = cursorCol; col < cols - count; col++) {
            cells[cursorRow][col] = cells[cursorRow][col + count];
        }
        Cell blank = new Cell(" ", activeStyle());
        Arrays.fill(cells[cursorRow], cols - count, cols, blank);
    }

    private void insertCharacters(int requested) {
        requireCursorInBounds();
        int count = Math.min(requested, cols - cursorCol);
        for (int col = cols - 1; col >= cursorCol + count; col--) {
            cells[cursorRow][col] = cells[cursorRow][col - count];
        }
        Cell blank = new Cell(" ", activeStyle());
        Arrays.fill(cells[cursorRow], cursorCol, cursorCol + count, blank);
    }

    private void setScrollRegion(String parameters) {
        if (parameters.isEmpty()) {
            scrollTop = 0;
            scrollBottom = rows - 1;
            return;
        }
        String[] values = parameters.split(";", -1);
        if (values.length != 2) {
            throw new IllegalArgumentException("invalid scroll region: " + parameters);
        }
        int top = positive(values[0]);
        int bottom = positive(values[1]);
        if (top >= bottom || bottom > rows) {
            throw new IllegalArgumentException("invalid scroll region: " + parameters);
        }
        scrollTop = top - 1;
        scrollBottom = bottom - 1;
    }

    private void scrollUp(int requested) {
        int count = Math.min(requested, scrollBottom - scrollTop + 1);
        for (int row = scrollTop; row <= scrollBottom - count; row++) {
            cells[row] = cells[row + count].clone();
        }
        for (int row = scrollBottom - count + 1; row <= scrollBottom; row++) {
            fillRow(row);
        }
    }

    private void scrollDown(int requested) {
        int count = Math.min(requested, scrollBottom - scrollTop + 1);
        for (int row = scrollBottom; row >= scrollTop + count; row--) {
            cells[row] = cells[row - count].clone();
        }
        for (int row = scrollTop; row < scrollTop + count; row++) {
            fillRow(row);
        }
    }

    private void fillRow(int row) {
        Cell blank = new Cell(" ", activeStyle());
        Arrays.fill(cells[row], blank);
    }

    private void clearFootprint(int row, int col) {
        Cell current = cells[row][col];
        if (current.continuation() && col > 0 && cells[row][col - 1].width() == 2) {
            cells[row][col - 1] = Cell.EMPTY;
        } else if (current.width() == 2 && col + 1 < cols) {
            cells[row][col + 1] = Cell.EMPTY;
        }
        cells[row][col] = Cell.EMPTY;
    }

    private void requireCursorInBounds() {
        if (cursorRow < 0 || cursorRow >= rows || cursorCol < 0 || cursorCol >= cols) {
            throw new IllegalStateException("cursor outside reference terminal");
        }
    }

    private CellStyle activeStyle() {
        return new CellStyle(foreground, background, bold, underline, inverse);
    }

    private void resetStyle() {
        foreground = CellColor.DEFAULT;
        background = CellColor.DEFAULT;
        bold = false;
        underline = false;
        inverse = false;
    }

    private static int count(String parameters) {
        return parameters.isEmpty() ? 1 : positive(parameters);
    }

    private static int positive(String value) {
        int parsed = nonNegative(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException("expected a positive CSI parameter: " + value);
        }
        return parsed;
    }

    private static int nonNegative(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException("negative CSI parameter: " + value);
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("invalid CSI parameter: " + value, failure);
        }
    }

    private static CellColor foreground(int code) {
        return switch (code) {
            case 30 -> CellColor.BLACK;
            case 31 -> CellColor.RED;
            case 32 -> CellColor.GREEN;
            case 33 -> CellColor.YELLOW;
            case 34 -> CellColor.BLUE;
            case 35 -> CellColor.MAGENTA;
            case 36 -> CellColor.CYAN;
            case 37 -> CellColor.WHITE;
            case 39 -> CellColor.DEFAULT;
            case 90 -> CellColor.BRIGHT_BLACK;
            case 91 -> CellColor.BRIGHT_RED;
            case 92 -> CellColor.BRIGHT_GREEN;
            case 93 -> CellColor.BRIGHT_YELLOW;
            case 94 -> CellColor.BRIGHT_BLUE;
            case 95 -> CellColor.BRIGHT_MAGENTA;
            case 96 -> CellColor.BRIGHT_CYAN;
            case 97 -> CellColor.BRIGHT_WHITE;
            default -> throw new IllegalArgumentException("unsupported foreground SGR: " + code);
        };
    }

    private static CellColor background(int code) {
        return switch (code) {
            case 40 -> CellColor.BLACK;
            case 41 -> CellColor.RED;
            case 42 -> CellColor.GREEN;
            case 43 -> CellColor.YELLOW;
            case 44 -> CellColor.BLUE;
            case 45 -> CellColor.MAGENTA;
            case 46 -> CellColor.CYAN;
            case 47 -> CellColor.WHITE;
            case 49 -> CellColor.DEFAULT;
            case 100 -> CellColor.BRIGHT_BLACK;
            case 101 -> CellColor.BRIGHT_RED;
            case 102 -> CellColor.BRIGHT_GREEN;
            case 103 -> CellColor.BRIGHT_YELLOW;
            case 104 -> CellColor.BRIGHT_BLUE;
            case 105 -> CellColor.BRIGHT_MAGENTA;
            case 106 -> CellColor.BRIGHT_CYAN;
            case 107 -> CellColor.BRIGHT_WHITE;
            default -> throw new IllegalArgumentException("unsupported background SGR: " + code);
        };
    }
}
