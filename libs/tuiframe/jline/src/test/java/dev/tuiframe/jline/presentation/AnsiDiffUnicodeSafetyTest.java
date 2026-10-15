package dev.tuiframe.jline.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.screen.Frame;
import dev.tuiframe.screen.ScreenCursor;
import dev.tuiframe.screen.ScrollHint;
import dev.tuiframe.style.CellStyle;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class AnsiDiffUnicodeSafetyTest {
    @Test
    void invisibleDirectionalControlsCannotShiftFollowingTextOrReachTheTerminal() {
        int[] codePoints = {0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
            0x2060, 0x2061, 0x2062, 0x2063, 0x2064, 0x2066, 0x2067, 0x2068, 0x2069,
            0x206A, 0x206B, 0x206C, 0x206D, 0x206E, 0x206F};
        for (int codePoint : codePoints) {
            String control = Character.toString(codePoint);
            CellBuffer cells = new CellBuffer(1, 6);
            int endColumn = CellText.draw(new Canvas(cells), 0, 0, "a" + control + "bZ", CellStyle.DEFAULT);
            StringWriter output = new StringWriter();
            new ScreenPresenter(new PrintWriter(output), TerminalCapabilities.DEFAULT).present(new Frame(
                cells, ScreenCursor.visibleAt(new Point(endColumn, 0)), ScrollHint.none()
            ));

            String ansi = output.toString();
            assertEquals(4, endColumn);
            assertEquals("�", cells.get(0, 1).grapheme());
            assertEquals("b", cells.get(0, 2).grapheme());
            assertEquals("Z", cells.get(0, 3).grapheme());
            assertFalse(ansi.contains(control), "raw U+%04X".formatted(codePoint));
            assertTrue(ansi.contains("a�bZ  "));
            assertTrue(ansi.endsWith("\u001B[1;5H"));
        }
    }

    @Test
    void aSingleCellCannotExpandToMegabytesOfTerminalOutput() {
        CellBuffer current = new CellBuffer(1, 80);
        current.set(0, 0, "a" + "\u0301".repeat(1_000_000), CellStyle.DEFAULT);

        String ansi = present(current);

        assertTrue(ansi.length() < 1024);
        assertTrue(ansi.contains("�"));
    }

    @Test
    void emitsWideGraphemeOnceAndNeverEmitsContinuationPayload() {
        CellBuffer current = new CellBuffer(1, 4);
        current.set(0, 1, "中", CellStyle.DEFAULT);

        String ansi = present(current);

        assertEquals(1, occurrences(ansi, "中"));
    }

    @Test
    void ordinaryTextWithJoinersKeepsFollowingTextAndCursorInTheirActualColumns() {
        CellBuffer cells = new CellBuffer(1, 5);
        int endColumn = CellText.draw(new Canvas(cells), 0, 0, "a\u200DB", CellStyle.DEFAULT);
        StringWriter output = new StringWriter();
        ScreenPresenter presenter = new ScreenPresenter(new PrintWriter(output), TerminalCapabilities.DEFAULT);

        presenter.present(new Frame(
            cells,
            ScreenCursor.visibleAt(new Point(endColumn, 0)),
            ScrollHint.none()
        ));

        assertEquals(2, endColumn);
        assertEquals("B", cells.get(0, 1).grapheme());
        assertFalse(cells.get(0, 1).continuation());
        assertTrue(output.toString().contains("a\u200DB   "));
        assertTrue(output.toString().endsWith("\u001B[1;3H"));
    }

    @Test
    void documentControlPayloadIsRenderedVisiblyInsteadOfExecuted() {
        CellBuffer current = new CellBuffer(1, 8);
        current.set(0, 0, "\u001B]52;c;secret\u0007", CellStyle.DEFAULT);

        String ansi = present(current);

        assertFalse(ansi.contains("]52;c;secret"));
        assertFalse(ansi.contains("\u0007"));
        assertTrue(ansi.contains("�"));
    }

    @Test
    void changingWideRowToAsciiRedrawsACompleteSafeRow() {
        CellBuffer previous = new CellBuffer(1, 4);
        previous.set(0, 0, "中", CellStyle.DEFAULT);
        CellBuffer current = new CellBuffer(1, 4);
        current.set(0, 0, "a", CellStyle.DEFAULT);
        current.set(0, 1, "b", CellStyle.DEFAULT);

        StringWriter output = new StringWriter();
        ScreenPresenter presenter = new ScreenPresenter(
            new PrintWriter(output),
            TerminalCapabilities.DEFAULT.withColorEnabled(false)
        );
        presenter.present(frame(previous));
        output.getBuffer().setLength(0);

        presenter.present(frame(current));
        String ansi = output.toString();

        assertTrue(ansi.contains("ab"));
        assertFalse(ansi.contains("中"));
    }

    private static String present(CellBuffer buffer) {
        StringWriter output = new StringWriter();
        ScreenPresenter presenter = new ScreenPresenter(
            new PrintWriter(output),
            TerminalCapabilities.DEFAULT.withColorEnabled(false)
        );
        presenter.present(frame(buffer));
        return output.toString();
    }

    private static Frame frame(CellBuffer buffer) {
        return new Frame(
            buffer,
            new ScreenCursor(new Point(0, 0), false),
            ScrollHint.none()
        );
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
