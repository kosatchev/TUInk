package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.cell.Cell;
import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.CellText;
import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.style.CellColor;
import dev.tuink.terminal.style.CellStyle;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContextMenuCanvasTest {
    private static final CellStyle TOP = CellStyle.fg(CellColor.BRIGHT_WHITE);
    private static final CellStyle POPUP = CellStyle.fg(CellColor.WHITE);
    private static final CellStyle SELECTED = CellStyle.fg(CellColor.BRIGHT_CYAN);
    private static final CellStyle TOP_SELECTED = CellStyle.fg(CellColor.BRIGHT_BLUE);
    private static final CellStyle MONO = CellStyle.fg(CellColor.DEFAULT);
    private static final CellStyle ACCENT = CellStyle.fg(CellColor.BRIGHT_MAGENTA);

    @Test
    void contextMenuCanvasMatchesLegacyForUnicodeAndMnemonics() {
        MenuItem[] items = {
            new MenuItem("Open 中e\u0301", true),
            new MenuItem("Disabled", false)
        };
        int[] mnemonics = {0, -1};
        CellBuffer legacy = filled(6, 24);
        CellBuffer actual = filled(6, 24);

        ContextMenu.draw(
            legacy,
            24,
            true,
            2,
            1,
            0,
            items,
            mnemonics,
            true,
            POPUP,
            SELECTED,
            MONO
        );
        ContextMenu.draw(
            new Canvas(actual),
            6,
            24,
            true,
            2,
            1,
            0,
            items,
            mnemonics,
            true,
            POPUP,
            SELECTED,
            MONO
        );

        assertBuffersEqual(legacy, actual);
        assertTrue(actual.get(2, 4).style().underline());
        assertSame(actual.get(1, 3), actual.get(4, 3));
    }

    @Test
    void contextMenuProtectsWidePairsAtEffectiveEdgesAndRestoresCanvas() {
        CellBuffer buffer = filled(5, 16);
        buffer.set(2, 1, "中", ACCENT);
        buffer.set(2, 5, "😀", ACCENT);
        buffer.set(2, 11, "界", ACCENT);
        Cell leftLead = buffer.get(2, 1);
        Cell leftContinuation = buffer.get(2, 2);
        Cell rightLead = buffer.get(2, 11);
        Cell rightContinuation = buffer.get(2, 12);
        Canvas canvas = new Canvas(buffer);

        ContextMenu.draw(
            canvas,
            5,
            12,
            true,
            2,
            1,
            0,
            new MenuItem[] {new MenuItem("item", true)},
            new int[] {0},
            true,
            POPUP,
            SELECTED,
            MONO
        );

        assertSame(leftLead, buffer.get(2, 1));
        assertSame(leftContinuation, buffer.get(2, 2));
        assertSame(rightLead, buffer.get(2, 11));
        assertSame(rightContinuation, buffer.get(2, 12));
        assertEquals("t", buffer.get(2, 5).grapheme());
        assertEquals(SELECTED, buffer.get(2, 5).style());
        assertEquals(".", buffer.get(1, 13).grapheme());
        assertTrue(canvas.put(0, 0, "z", ACCENT));
        assertEquals("z", buffer.get(0, 0).grapheme());
        assertSignaturesMatchRebuiltBuffer(buffer);
    }

    @Test
    void menuBarCanvasMatchesLegacyTopSelectionPopupAndMnemonics() {
        int rows = 18;
        int cols = 60;
        MenuBar menu = new MenuBar(POPUP, SELECTED, TOP_SELECTED, MONO);
        String topBar = menu.buildTopBar(Path.of("demo.txt"), true, 0, 1, cols, false);
        CellBuffer legacy = filled(rows, cols);
        CellBuffer actual = filled(rows, cols);
        CellText.draw(legacy, 0, 0, topBar, TOP, cols);
        CellText.draw(actual, 0, 0, topBar, TOP, cols);
        menu.openFile();

        menu.draw(legacy, cols, true);
        menu.draw(new Canvas(actual), rows, cols, true, TOP);

        assertBuffersEqual(legacy, actual);
        assertEquals(TOP_SELECTED, actual.get(0, 0).style());
        assertTrue(actual.get(0, 2).style().underline());
        assertTrue(actual.get(2, 2).style().underline());
    }

    private static CellBuffer filled(int rows, int cols) {
        CellBuffer buffer = new CellBuffer(rows, cols);
        buffer.fill(new Cell(".", CellStyle.DEFAULT));
        return buffer;
    }

    private static void assertBuffersEqual(CellBuffer expected, CellBuffer actual) {
        assertEquals(expected.rows(), actual.rows());
        assertEquals(expected.cols(), actual.cols());
        for (int row = 0; row < expected.rows(); row++) {
            for (int col = 0; col < expected.cols(); col++) {
                assertEquals(expected.get(row, col), actual.get(row, col), col + "," + row);
            }
            assertEquals(expected.rowSignature1(row), actual.rowSignature1(row));
            assertEquals(expected.rowSignature2(row), actual.rowSignature2(row));
        }
    }

    private static void assertSignaturesMatchRebuiltBuffer(CellBuffer actual) {
        CellBuffer rebuilt = new CellBuffer(actual.rows(), actual.cols());
        for (int row = 0; row < actual.rows(); row++) {
            for (int col = 0; col < actual.cols(); col++) {
                Cell cell = actual.get(row, col);
                if (!cell.continuation()) {
                    rebuilt.set(row, col, cell.grapheme(), cell.style());
                }
            }
            assertEquals(rebuilt.rowSignature1(row), actual.rowSignature1(row));
            assertEquals(rebuilt.rowSignature2(row), actual.rowSignature2(row));
        }
    }
}
