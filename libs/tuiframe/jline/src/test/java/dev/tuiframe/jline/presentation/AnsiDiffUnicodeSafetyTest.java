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
