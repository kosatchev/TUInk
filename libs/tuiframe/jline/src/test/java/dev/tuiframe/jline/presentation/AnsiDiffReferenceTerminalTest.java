package dev.tuiframe.jline.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.screen.ScrollHint;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import org.junit.jupiter.api.Test;

class AnsiDiffReferenceTerminalTest {
    private static final CellStyle CYAN = CellStyle.fg(CellColor.BRIGHT_CYAN);
    private static final CellStyle ACCENT = new CellStyle(
        CellColor.BRIGHT_MAGENTA,
        CellColor.BLUE,
        true,
        true
    );

    @Test
    void fullAndChangedWideUnicodeFramesReplayToTheirExactTargets() {
        CellBuffer previous = rows(
            "abcdefghij",
            "klmnopqrst",
            "uvwxyz0123"
        );
        previous.set(0, 1, "中", CYAN);
        previous.set(1, 4, "e\u0301", ACCENT);
        previous.set(2, 6, "😀", CYAN);

        AnsiDiff diff = new AnsiDiff();
        ReferenceTerminal terminal = new ReferenceTerminal(previous.rows(), previous.cols());
        String initialPatch = diff.diff(
            null,
            previous,
            TerminalCapabilities.DEFAULT,
            ScrollHint.none()
        );

        terminal.apply(initialPatch);
        assertMatches(previous, terminal);

        CellBuffer current = rows(
            "abcdefghij",
            "klmnopqrst",
            "uvwxyz0123"
        );
        current.set(0, 1, "X", ACCENT);
        current.set(0, 2, "Y", CYAN);
        current.set(1, 4, "界", CYAN);
        current.set(2, 6, "A", ACCENT);
        current.set(2, 7, "B", ACCENT);
        String patch = diff.diff(
            previous,
            current,
            TerminalCapabilities.DEFAULT,
            ScrollHint.none()
        );

        terminal.apply(patch);

        assertMatches(current, terminal);
        assertFalse(patch.contains("\u001B[1P"));
        assertFalse(patch.contains("\u001B[1@"));
        assertFalse(patch.contains("\u001B[1S"));
        assertFalse(patch.contains("\u001B[1T"));
    }

    @Test
    void ordinaryDisjointCellAndStyleDiffReplaysToTheTarget() {
        CellBuffer previous = rows("abcdefghij", "0123456789", "ABCDEFGHIJ");
        previous.set(0, 2, "c", CYAN);
        previous.set(2, 7, "H", ACCENT);
        CellBuffer current = rows("abXdefghij", "01234Y6789", "ABCDEFGzIJ");
        current.set(0, 2, "X", ACCENT);
        current.set(1, 5, "Y", CYAN);
        current.set(2, 7, "z", CellStyle.DEFAULT);

        AnsiDiff diff = new AnsiDiff();
        ReferenceTerminal terminal = terminalAt(previous, diff);
        String patch = diff.diff(
            previous,
            current,
            TerminalCapabilities.DEFAULT,
            ScrollHint.none()
        );

        terminal.apply(patch);

        assertMatches(current, terminal);
        assertFalse(patch.contains("\u001B[1P"));
        assertFalse(patch.contains("\u001B[1@"));
        assertFalse(patch.contains("\u001B[1S"));
        assertFalse(patch.contains("\u001B[1T"));
    }

    @Test
    void collidingEmojiHashesStillReplayTheChangedFrame() {
        CellBuffer previous = rows("abcdefgh");
        CellBuffer current = rows("abcdefgh");
        previous.set(0, 2, "😀", CYAN);
        current.set(0, 2, "🧡", CYAN);
        assertEquals(previous.get(0, 2).hashCode(), current.get(0, 2).hashCode());
        assertEquals(previous.rowSignature1(0), current.rowSignature1(0));
        assertEquals(previous.rowSignature2(0), current.rowSignature2(0));

        AnsiDiff diff = new AnsiDiff();
        ReferenceTerminal terminal = terminalAt(previous, diff);
        terminal.apply(diff.diff(
            previous,
            current,
            TerminalCapabilities.DEFAULT,
            ScrollHint.none()
        ));

        assertMatches(current, terminal);
    }

    @Test
    void verticalScrollChecksCollidingRowsBeforeSkippingTheirText() {
        CellBuffer previous = rows(
            "00000000", "11111111", "22222222", "33333333", "44444444", "55555555"
        );
        previous.set(2, 2, "😀", CYAN);
        CellBuffer current = new CellBuffer(6, 8);
        copyRow(previous, 1, current, 0);
        copyRow(previous, 2, current, 1);
        copyRow(previous, 3, current, 2);
        copyRow(previous, 4, current, 3);
        copyRow(previous, 5, current, 4);
        putAscii(current, 5, "EEEEEEEE");
        current.set(1, 2, "🧡", CYAN);
        assertEquals(previous.get(2, 2).hashCode(), current.get(1, 2).hashCode());

        AnsiDiff diff = new AnsiDiff();
        ReferenceTerminal terminal = terminalAt(previous, diff);
        String patch = diff.diff(
            previous,
            current,
            TerminalCapabilities.DEFAULT,
            new ScrollHint.Vertical(new Rect(1, 0, 6, 6))
        );

        assertTrue(patch.contains("\u001B[1S"));
        terminal.apply(patch);
        assertMatches(current, terminal);
    }

    @Test
    void collidingGraphemesCannotCreateAFalseScrollMatch() {
        CellBuffer previous = rows("aaaa", "aaaa", "aaaa", "aaaa");
        CellBuffer current = rows("aaaa", "aaaa", "aaaa", "aaaa");
        for (int row = 0; row < previous.rows(); row++) {
            previous.set(row, 1, "a\u0301\u0322", CYAN);
            current.set(row, 1, "a\u0302\u0303", CYAN);
        }
        assertEquals(previous.get(0, 1).hashCode(), current.get(0, 1).hashCode());

        AnsiDiff diff = new AnsiDiff();
        ReferenceTerminal terminal = terminalAt(previous, diff);
        String patch = diff.diff(
            previous,
            current,
            TerminalCapabilities.DEFAULT,
            new ScrollHint.Vertical(new Rect(0, 0, 4, 4))
        );

        assertFalse(patch.contains("\u001B[1S"));
        assertFalse(patch.contains("\u001B[1T"));
        terminal.apply(patch);
        assertMatches(current, terminal);
    }

    @Test
    void horizontalDeleteAndInsertFastPathsReplayToTheirTargets() {
        CellBuffer previous = rows("0123456789", "ABabcdefCD", "9876543210");
        Rect region = new Rect(2, 1, 6, 1);

        CellBuffer deleted = rows("0123456789", "ABbcdefXCD", "9876543210");
        deleted.set(1, 7, "X", ACCENT);
        AnsiDiff deleteDiff = new AnsiDiff();
        ReferenceTerminal deleteTerminal = terminalAt(previous, deleteDiff);
        String deletePatch = deleteDiff.diff(
            previous,
            deleted,
            TerminalCapabilities.DEFAULT,
            new ScrollHint.Horizontal(region, 1)
        );

        assertTrue(deletePatch.contains("\u001B[1P"));
        deleteTerminal.apply(deletePatch);
        assertMatches(deleted, deleteTerminal);

        CellBuffer inserted = rows("0123456789", "ABXabcdeCD", "9876543210");
        inserted.set(1, 2, "X", CYAN);
        AnsiDiff insertDiff = new AnsiDiff();
        ReferenceTerminal insertTerminal = terminalAt(previous, insertDiff);
        String insertPatch = insertDiff.diff(
            previous,
            inserted,
            TerminalCapabilities.DEFAULT,
            new ScrollHint.Horizontal(region, -1)
        );

        assertTrue(insertPatch.contains("\u001B[1@"));
        insertTerminal.apply(insertPatch);
        assertMatches(inserted, insertTerminal);
    }

    @Test
    void verticalScrollUpAndDownFastPathsReplayToTheirTargets() {
        CellBuffer previous = rows(
            "00000000",
            "11111111",
            "22222222",
            "33333333",
            "44444444",
            "55555555"
        );
        previous.set(2, 2, "中", CYAN);
        previous.set(3, 4, "😀", ACCENT);
        previous.set(4, 1, "e\u0301", CYAN);
        Rect region = new Rect(1, 1, 6, 4);

        CellBuffer scrolledUp = new CellBuffer(6, 8);
        copyRow(previous, 0, scrolledUp, 0);
        copyRow(previous, 2, scrolledUp, 1);
        copyRow(previous, 3, scrolledUp, 2);
        copyRow(previous, 4, scrolledUp, 3);
        putAscii(scrolledUp, 4, "EEEEEEEE");
        scrolledUp.set(4, 2, "界", ACCENT);
        copyRow(previous, 5, scrolledUp, 5);
        scrolledUp.set(2, 0, "X", CYAN);
        scrolledUp.set(2, 7, "Y", ACCENT);

        AnsiDiff diff = new AnsiDiff();
        ReferenceTerminal terminal = terminalAt(previous, diff);
        String upPatch = diff.diff(
            previous,
            scrolledUp,
            TerminalCapabilities.DEFAULT,
            new ScrollHint.Vertical(region)
        );

        assertTrue(upPatch.contains("\u001B[2;5r"));
        assertTrue(upPatch.contains("\u001B[1S"));
        terminal.apply(upPatch);
        assertMatches(scrolledUp, terminal);

        CellBuffer scrolledDown = new CellBuffer(6, 8);
        copyRow(scrolledUp, 0, scrolledDown, 0);
        putAscii(scrolledDown, 1, "NNNNNNNN");
        scrolledDown.set(1, 3, "中", CYAN);
        copyRow(scrolledUp, 1, scrolledDown, 2);
        copyRow(scrolledUp, 2, scrolledDown, 3);
        copyRow(scrolledUp, 3, scrolledDown, 4);
        copyRow(scrolledUp, 5, scrolledDown, 5);
        scrolledDown.set(3, 0, "Z", ACCENT);
        scrolledDown.set(3, 7, "Q", CYAN);
        String downPatch = diff.diff(
            scrolledUp,
            scrolledDown,
            TerminalCapabilities.DEFAULT,
            new ScrollHint.Vertical(region)
        );

        assertTrue(downPatch.contains("\u001B[2;5r"));
        assertTrue(downPatch.contains("\u001B[1T"));
        terminal.apply(downPatch);
        assertMatches(scrolledDown, terminal);
    }

    private static ReferenceTerminal terminalAt(CellBuffer initial, AnsiDiff diff) {
        ReferenceTerminal terminal = new ReferenceTerminal(initial.rows(), initial.cols());
        terminal.apply(diff.diff(
            null,
            initial,
            TerminalCapabilities.DEFAULT,
            ScrollHint.none()
        ));
        assertMatches(initial, terminal);
        return terminal;
    }

    private static CellBuffer rows(String... rows) {
        int cols = rows[0].length();
        CellBuffer buffer = new CellBuffer(rows.length, cols);
        for (int row = 0; row < rows.length; row++) {
            if (rows[row].length() != cols) {
                throw new IllegalArgumentException("all fixture rows must have equal ASCII width");
            }
            putAscii(buffer, row, rows[row]);
        }
        return buffer;
    }

    private static void putAscii(CellBuffer buffer, int row, String text) {
        for (int col = 0; col < text.length(); col++) {
            buffer.set(row, col, String.valueOf(text.charAt(col)), CellStyle.DEFAULT);
        }
    }

    private static void copyRow(CellBuffer source, int sourceRow, CellBuffer target, int targetRow) {
        for (int col = 0; col < source.cols(); col++) {
            Cell cell = source.get(sourceRow, col);
            if (!cell.continuation()) {
                target.set(targetRow, col, cell.grapheme(), cell.style());
            }
        }
    }

    private static void assertMatches(CellBuffer expected, ReferenceTerminal actual) {
        assertEquals(expected.rows(), actual.rows());
        assertEquals(expected.cols(), actual.cols());
        for (int row = 0; row < expected.rows(); row++) {
            for (int col = 0; col < expected.cols(); col++) {
                assertEquals(expected.get(row, col), actual.cell(row, col), col + "," + row);
            }
        }
    }
}
