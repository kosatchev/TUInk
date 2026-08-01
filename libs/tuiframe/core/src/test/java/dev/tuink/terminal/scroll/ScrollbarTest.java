package dev.tuink.terminal.scroll;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.style.CellColor;
import dev.tuink.terminal.style.CellStyle;
import org.junit.jupiter.api.Test;

class ScrollbarTest {
    private static final CellStyle TRACK = CellStyle.fg(CellColor.BRIGHT_BLACK);
    private static final CellStyle THUMB = CellStyle.fg(CellColor.BRIGHT_WHITE);

    @Test
    void horizontalPainterUsesSharedThumbGeometry() {
        CellBuffer buffer = new CellBuffer(1, 5);

        HorizontalScrollbar.draw(buffer, 0, 0, 5, 10, 5, ".", TRACK, "#", THUMB);

        assertEquals(".", buffer.get(0, 0).grapheme());
        assertEquals(".", buffer.get(0, 1).grapheme());
        for (int col = 2; col < 5; col++) {
            assertEquals("#", buffer.get(0, col).grapheme());
            assertEquals(THUMB, buffer.get(0, col).style());
        }
    }

    @Test
    void verticalPainterUsesTheSameGeometry() {
        CellBuffer buffer = new CellBuffer(5, 1);

        VerticalScrollbar.draw(buffer, 0, 0, 5, 10, 0, ".", TRACK, "#", THUMB);

        for (int row = 0; row < 3; row++) {
            assertEquals("#", buffer.get(row, 0).grapheme());
        }
        assertEquals(".", buffer.get(4, 0).grapheme());
    }
}
