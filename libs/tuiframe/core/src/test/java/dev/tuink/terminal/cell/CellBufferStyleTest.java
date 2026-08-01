package dev.tuink.terminal.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.tuink.terminal.style.CellColor;
import dev.tuink.terminal.style.CellStyle;
import org.junit.jupiter.api.Test;

class CellBufferStyleTest {
    private static final CellStyle EMPHASIS = new CellStyle(
        CellColor.BRIGHT_CYAN,
        CellColor.BLUE,
        true,
        true
    );

    @Test
    void wideCellKeepsTheSameStyleOnLeadAndContinuation() {
        CellBuffer buffer = new CellBuffer(1, 4);

        buffer.set(0, 1, "中", EMPHASIS);

        assertEquals(EMPHASIS, buffer.get(0, 1).style());
        assertEquals(EMPHASIS, buffer.get(0, 2).style());
    }

    @Test
    void wideFillKeepsStyleOnLeadsContinuationsAndNarrowFallback() {
        CellBuffer buffer = new CellBuffer(1, 5);

        buffer.fill(new Cell("界", EMPHASIS));

        for (int col = 0; col < buffer.cols(); col++) {
            assertEquals(EMPHASIS, buffer.get(0, col).style(), "column " + col);
        }
    }

    @Test
    void rowSignatureChangesWhenOnlyStyleChanges() {
        CellBuffer buffer = new CellBuffer(1, 1);
        buffer.set(0, 0, "x", CellStyle.DEFAULT);
        long defaultSignature1 = buffer.rowSignature1(0);
        long defaultSignature2 = buffer.rowSignature2(0);

        buffer.set(0, 0, "x", EMPHASIS);

        assertNotEquals(defaultSignature1, buffer.rowSignature1(0));
        assertNotEquals(defaultSignature2, buffer.rowSignature2(0));
    }

    @Test
    void equalButDistinctStyleKeepsCellAndRowSignaturesEqual() {
        CellStyle equalStyle = new CellStyle(
            EMPHASIS.fg(),
            EMPHASIS.bg(),
            EMPHASIS.bold(),
            EMPHASIS.underline()
        );
        CellBuffer first = new CellBuffer(1, 1);
        CellBuffer second = new CellBuffer(1, 1);

        first.set(0, 0, "x", EMPHASIS);
        second.set(0, 0, "x", equalStyle);

        assertEquals(first.get(0, 0), second.get(0, 0));
        assertEquals(first.rowSignature1(0), second.rowSignature1(0));
        assertEquals(first.rowSignature2(0), second.rowSignature2(0));
    }
}
