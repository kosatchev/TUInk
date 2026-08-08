package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.cell.Cell;
import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.style.CellColor;
import dev.tuink.terminal.style.CellStyle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreeViewCanvasTest {
    private static final CellStyle NORMAL = CellStyle.fg(CellColor.WHITE);
    private static final CellStyle SELECTED = CellStyle.fg(CellColor.BRIGHT_CYAN);
    private static final CellStyle SELECTED_MONO = new CellStyle(
        CellColor.DEFAULT,
        CellColor.DEFAULT,
        true,
        false
    );
    private static final CellStyle TRACK = CellStyle.fg(CellColor.BRIGHT_BLUE);
    private static final CellStyle THUMB = CellStyle.fg(CellColor.BRIGHT_MAGENTA);
    private static final CellStyle ACCENT = CellStyle.fg(CellColor.BRIGHT_YELLOW);

    @TempDir
    Path root;

    @Test
    void canvasPainterMatchesLegacyForNormalUnicodeRows() throws IOException {
        Files.createFile(root.resolve("alpha.txt"));
        Files.createFile(root.resolve("中e\u0301.txt"));
        TreeView legacyTree = tree();
        TreeView canvasTree = tree();
        CellBuffer legacy = filled(7, 26);
        CellBuffer actual = filled(7, 26);

        legacyTree.draw(
            legacy,
            22,
            2,
            20,
            1,
            4,
            true,
            NORMAL,
            SELECTED,
            SELECTED_MONO,
            TRACK,
            THUMB
        );
        canvasTree.draw(
            new Canvas(actual),
            7,
            22,
            2,
            20,
            1,
            4,
            true,
            NORMAL,
            SELECTED,
            SELECTED_MONO,
            TRACK,
            THUMB
        );

        assertBuffersEqual(legacy, actual);
        assertEquals(".", actual.get(0, 0).grapheme());
        assertEquals(".", actual.get(1, 22).grapheme());
        assertEquals(".", actual.get(6, 0).grapheme());
    }

    @Test
    void translatedParentClipAndDeclaredFrameAreHonoredAndStateIsRestored() throws IOException {
        Files.createFile(root.resolve("alpha.txt"));
        TreeView tree = tree();
        CellBuffer buffer = filled(7, 18);
        Canvas canvas = new Canvas(buffer);
        canvas.translate(2, 1);
        assertTrue(canvas.clip(1, 1, 8, 3));

        tree.draw(
            canvas,
            5,
            12,
            0,
            11,
            1,
            3,
            true,
            NORMAL,
            SELECTED,
            SELECTED_MONO,
            TRACK,
            THUMB
        );

        assertEquals(" ", buffer.get(2, 3).grapheme());
        assertEquals(SELECTED, buffer.get(2, 3).style());
        assertEquals(".", buffer.get(2, 2).grapheme());
        assertEquals(".", buffer.get(2, 11).grapheme());
        assertEquals(".", buffer.get(1, 3).grapheme());

        assertTrue(canvas.put(1, 1, "z", ACCENT));
        assertEquals("z", buffer.get(2, 3).grapheme());
    }

    @Test
    void unicodeCombiningAndTabsKeepTextRelativeGeometry() throws IOException {
        Files.createFile(root.resolve("a\t中e\u0301"));
        TreeView tree = tree();
        CellBuffer buffer = filled(4, 18);

        tree.draw(
            new Canvas(buffer),
            4,
            16,
            0,
            14,
            0,
            3,
            true,
            NORMAL,
            SELECTED,
            SELECTED_MONO,
            TRACK,
            THUMB
        );

        assertEquals(" ", buffer.get(1, 1).grapheme());
        assertEquals(" ", buffer.get(1, 2).grapheme());
        assertEquals(" ", buffer.get(1, 3).grapheme());
        assertEquals("a", buffer.get(1, 4).grapheme());
        assertEquals(" ", buffer.get(1, 5).grapheme());
        assertEquals("中", buffer.get(1, 6).grapheme());
        assertTrue(buffer.get(1, 7).continuation());
        assertEquals("e\u0301", buffer.get(1, 8).grapheme());
        assertEquals(NORMAL, buffer.get(1, 7).style());
        assertSignaturesMatchRebuiltBuffer(buffer);
    }

    @Test
    void strictTextBoundsPreserveWidePairsCrossingBothEdges() {
        TreeView tree = tree();
        CellBuffer buffer = filled(3, 11);
        buffer.set(1, 1, "中", ACCENT);
        buffer.set(1, 7, "界", ACCENT);
        Cell leftLead = buffer.get(1, 1);
        Cell leftContinuation = buffer.get(1, 2);
        Cell rightLead = buffer.get(1, 7);
        Cell rightContinuation = buffer.get(1, 8);

        tree.draw(
            new Canvas(buffer),
            3,
            10,
            1,
            8,
            1,
            1,
            true,
            NORMAL,
            SELECTED,
            SELECTED_MONO,
            TRACK,
            THUMB
        );

        assertSame(leftLead, buffer.get(1, 1));
        assertSame(leftContinuation, buffer.get(1, 2));
        assertSame(rightLead, buffer.get(1, 7));
        assertSame(rightContinuation, buffer.get(1, 8));
        assertEquals(".", buffer.get(1, 9).grapheme());
        assertSignaturesMatchRebuiltBuffer(buffer);
    }

    @Test
    void bothScrollbarsShareTheCallerCanvasAndReuseTrackCells() throws IOException {
        for (int i = 0; i < 8; i++) {
            Files.createFile(root.resolve("long-file-name-" + i + "-0123456789.txt"));
        }
        TreeView tree = tree();
        CellBuffer buffer = filled(7, 15);
        Canvas canvas = new Canvas(buffer);

        tree.draw(
            canvas,
            7,
            15,
            1,
            12,
            1,
            4,
            true,
            NORMAL,
            SELECTED,
            SELECTED_MONO,
            TRACK,
            THUMB
        );

        assertEquals("█", buffer.get(1, 11).grapheme());
        assertEquals("│", buffer.get(2, 11).grapheme());
        assertSame(buffer.get(2, 11), buffer.get(3, 11));
        assertEquals(TRACK, buffer.get(2, 11).style());
        assertEquals("█", buffer.get(4, 2).grapheme());
        assertEquals("─", buffer.get(4, 6).grapheme());
        assertSame(buffer.get(4, 6), buffer.get(4, 7));
        assertEquals(TRACK, buffer.get(4, 6).style());
        assertSignaturesMatchRebuiltBuffer(buffer);

        assertTrue(canvas.put(0, 0, "z", ACCENT));
        assertEquals("z", buffer.get(0, 0).grapheme());
    }

    @Test
    void declaredFrameClipPreventsPaintIntoExtraBufferRowsAndColumns() throws IOException {
        Files.createFile(root.resolve("alpha.txt"));
        TreeView tree = tree();
        CellBuffer buffer = filled(5, 12);

        tree.draw(
            new Canvas(buffer),
            2,
            5,
            0,
            10,
            1,
            3,
            true,
            NORMAL,
            SELECTED,
            SELECTED_MONO,
            TRACK,
            THUMB
        );

        assertEquals(" ", buffer.get(1, 1).grapheme());
        assertEquals(SELECTED, buffer.get(1, 1).style());
        assertEquals(".", buffer.get(1, 5).grapheme());
        assertEquals(".", buffer.get(2, 1).grapheme());
        assertEquals(".", buffer.get(4, 11).grapheme());
    }

    @Test
    void emptyLayoutDoesNotPaintAndExceptionalPaintRestoresCanvasState() {
        TreeView tree = tree();
        CellBuffer buffer = filled(4, 12);
        Canvas canvas = new Canvas(buffer);
        long signature1 = buffer.rowSignature1(1);
        long signature2 = buffer.rowSignature2(1);

        tree.draw(
            canvas,
            4,
            10,
            1,
            9,
            1,
            0,
            true,
            NORMAL,
            SELECTED,
            SELECTED_MONO,
            TRACK,
            THUMB
        );

        assertEquals(signature1, buffer.rowSignature1(1));
        assertEquals(signature2, buffer.rowSignature2(1));
        canvas.translate(1, 1);
        assertTrue(canvas.clip(0, 0, 9, 3));

        assertThrows(
            IllegalArgumentException.class,
            () -> tree.draw(
                canvas,
                3,
                9,
                0,
                8,
                0,
                2,
                true,
                NORMAL,
                null,
                SELECTED_MONO,
                TRACK,
                THUMB
            )
        );

        assertTrue(canvas.put(0, 0, "z", ACCENT));
        assertEquals("z", buffer.get(1, 1).grapheme());
    }

    private TreeView tree() {
        return new TreeView(root, false, false);
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
