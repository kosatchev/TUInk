package dev.tuiframe.scroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import org.junit.jupiter.api.Test;

class ScrollbarTest {
    private static final CellStyle TRACK = CellStyle.fg(CellColor.BRIGHT_BLACK);
    private static final CellStyle THUMB = CellStyle.fg(CellColor.BRIGHT_WHITE);

    @Test
    void horizontalPainterUsesSharedThumbGeometry() {
        CellBuffer buffer = new CellBuffer(1, 5);

        HorizontalScrollbar.draw(new Canvas(buffer), new Rect(0, 0, 5, 1), 10, 5, ".", TRACK, "#", THUMB);

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

        VerticalScrollbar.draw(new Canvas(buffer), new Rect(0, 0, 1, 5), 10, 0, ".", TRACK, "#", THUMB);

        for (int row = 0; row < 3; row++) {
            assertEquals("#", buffer.get(row, 0).grapheme());
        }
        assertEquals(".", buffer.get(4, 0).grapheme());
    }

    @Test
    void canvasPainterClipsHorizontalTrackWithoutChangingThumbGeometry() {
        CellBuffer buffer = new CellBuffer(2, 8);
        buffer.fill(new Cell(".", CellStyle.DEFAULT));
        Canvas canvas = new Canvas(buffer);
        assertTrue(canvas.clip(new Rect(2, 1, 3, 1)));

        HorizontalScrollbar.draw(
            canvas,
            new Rect(0, 1, 8, 1),
            16,
            0,
            "-",
            TRACK,
            "#",
            THUMB
        );

        assertEquals(".", buffer.get(1, 1).grapheme());
        assertEquals("#", buffer.get(1, 2).grapheme());
        assertEquals("#", buffer.get(1, 3).grapheme());
        assertEquals("-", buffer.get(1, 4).grapheme());
        assertEquals(".", buffer.get(1, 5).grapheme());
    }

    @Test
    void canvasPainterClipsVerticalTrackAndValidatesTrackShape() {
        CellBuffer buffer = new CellBuffer(7, 3);
        buffer.fill(new Cell(".", CellStyle.DEFAULT));
        Canvas canvas = new Canvas(buffer);
        assertTrue(canvas.clip(new Rect(1, 2, 1, 3)));

        VerticalScrollbar.draw(
            canvas,
            new Rect(1, 0, 1, 7),
            14,
            7,
            "|",
            TRACK,
            "#",
            THUMB
        );

        assertEquals(".", buffer.get(1, 1).grapheme());
        assertEquals("|", buffer.get(2, 1).grapheme());
        assertEquals("#", buffer.get(3, 1).grapheme());
        assertEquals("#", buffer.get(4, 1).grapheme());
        assertEquals(".", buffer.get(5, 1).grapheme());
        assertThrows(
            IllegalArgumentException.class,
            () -> VerticalScrollbar.draw(
                canvas,
                new Rect(0, 0, 2, 2),
                10,
                0,
                ".",
                TRACK,
                "#",
                THUMB
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> HorizontalScrollbar.draw(
                canvas,
                new Rect(0, 0, 2, 2),
                10,
                0,
                ".",
                TRACK,
                "#",
                THUMB
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> HorizontalScrollbar.draw(
                canvas,
                new Rect(0, 0, 3, 1),
                6,
                0,
                "ab",
                TRACK,
                "#",
                THUMB
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> VerticalScrollbar.draw(
                canvas,
                new Rect(0, 0, 1, 3),
                6,
                0,
                ".",
                TRACK,
                "ab",
                THUMB
            )
        );
    }

    @Test
    void canvasPainterRejectsWideTrackAndThumbGraphemes() {
        Canvas canvas = new Canvas(new CellBuffer(3, 3));

        assertThrows(
            IllegalArgumentException.class,
            () -> HorizontalScrollbar.draw(
                canvas,
                new Rect(0, 0, 3, 1),
                6,
                0,
                "中",
                TRACK,
                "#",
                THUMB
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> HorizontalScrollbar.draw(
                canvas,
                new Rect(0, 0, 3, 1),
                6,
                0,
                ".",
                TRACK,
                "😀",
                THUMB
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> VerticalScrollbar.draw(
                canvas,
                new Rect(0, 0, 1, 3),
                6,
                0,
                "界",
                TRACK,
                "#",
                THUMB
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> VerticalScrollbar.draw(
                canvas,
                new Rect(0, 0, 1, 3),
                6,
                0,
                ".",
                TRACK,
                "😀",
                THUMB
            )
        );
    }

    @Test
    void canvasPainterPreservesWideCellsCrossingTrackEdges() {
        CellBuffer buffer = new CellBuffer(1, 7);
        buffer.fill(new Cell(".", CellStyle.DEFAULT));
        buffer.set(0, 0, "中", TRACK);
        buffer.set(0, 5, "界", TRACK);

        HorizontalScrollbar.draw(
            new Canvas(buffer),
            new Rect(1, 0, 5, 1),
            5,
            0,
            "-",
            TRACK,
            "#",
            THUMB
        );

        assertEquals("中", buffer.get(0, 0).grapheme());
        assertTrue(buffer.get(0, 1).continuation());
        assertEquals("#", buffer.get(0, 2).grapheme());
        assertEquals("#", buffer.get(0, 3).grapheme());
        assertEquals("#", buffer.get(0, 4).grapheme());
        assertEquals("界", buffer.get(0, 5).grapheme());
        assertTrue(buffer.get(0, 6).continuation());
    }

    @Test
    void canvasPainterRestoresCallerClipAfterPaintAndDisjointTrack() {
        CellBuffer buffer = new CellBuffer(3, 5);
        Canvas canvas = new Canvas(buffer);
        assertTrue(canvas.clip(new Rect(0, 0, 4, 3)));

        HorizontalScrollbar.draw(
            canvas,
            new Rect(1, 1, 2, 1),
            4,
            0,
            "-",
            TRACK,
            "#",
            THUMB
        );
        assertTrue(canvas.put(3, 2, "x", THUMB));

        VerticalScrollbar.draw(
            canvas,
            new Rect(4, 0, 1, 3),
            6,
            0,
            "|",
            TRACK,
            "#",
            THUMB
        );
        assertTrue(canvas.put(0, 0, "y", THUMB));
    }
}
