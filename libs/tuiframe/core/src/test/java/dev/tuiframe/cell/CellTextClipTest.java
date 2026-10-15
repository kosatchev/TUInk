package dev.tuiframe.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalText;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CellTextClipTest {
    @Test
    void indexedAndStreamingSlicesPreserveTheOriginalDisplayAtEveryBoundary() {
        Random random = new Random(0x51ceL);
        List<String> atoms = List.of("a", "Z", "\t", "界", "e\u0301", "👩‍💻", "🇬🇧", "\u0301",
            "\u200d", "\u0001", "\r\n", "\ud800", "\udc00", "1\ufe0f\u20e3", "क्ष");
        for (int example = 0; example < 80; example++) {
            StringBuilder source = new StringBuilder();
            for (int atom = 0; atom < 8; atom++) source.append(atoms.get(random.nextInt(atoms.size())));
            String text = source.toString();
            TerminalText.Metrics metrics = TerminalText.metrics(text, 4);
            int width = metrics.visualWidth();
            for (int left = 0; left <= width + 1; left++) {
                for (int cells = 1; cells <= 7; cells++) {
                    String streaming = CellText.clip(text, left, cells);
                    assertEquals(streaming, CellText.clipIndexed(metrics, left, cells),
                        "left=" + left + ", cells=" + cells + ", source=" + text);
                    CellBuffer expected = new CellBuffer(1, cells);
                    CellText.draw(new Canvas(expected), -left, 0, text, CellStyle.DEFAULT);
                    CellBuffer actual = new CellBuffer(1, cells);
                    CellText.draw(new Canvas(actual), 0, 0, streaming, CellStyle.DEFAULT);
                    for (int column = 0; column < cells; column++) {
                        assertEquals(expected.get(0, column), actual.get(0, column),
                            "left=" + left + ", cells=" + cells + ", column=" + column + ", source=" + text);
                    }
                }
            }
        }
    }

    @Test
    void indexedSlicesUseTheirOriginalTabWidthAndSupportExtremeViewportCoordinates() {
        assertEquals(" 界", CellText.clipIndexed(TerminalText.metrics("a\t界Z", 2), 1, 3));
        assertEquals("", CellText.clip("abc", Integer.MAX_VALUE, 20));
        assertEquals("", CellText.clipIndexed(TerminalText.metrics("abc", 4), Integer.MAX_VALUE, 20));
        assertEquals("", CellText.clip(null, 0, 20));
        assertEquals("abc", CellText.clip("abc", -3, Integer.MAX_VALUE));
    }
}
