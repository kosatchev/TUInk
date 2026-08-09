package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.cell.Cell;
import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.CellText;
import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.style.CellColor;
import dev.tuink.terminal.style.CellStyle;
import dev.tuink.terminal.widget.Button;
import dev.tuink.tui.ui.MenuBar;
import dev.tuink.terminal.widget.Modal;
import dev.tuink.tui.ui.TabBar;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrameCompositionGoldenTest {
    private static final int ROWS = 10;
    private static final int COLS = 44;

    private static final CellStyle BACKGROUND = new CellStyle(
        CellColor.BRIGHT_BLACK,
        CellColor.BLACK,
        false,
        false
    );
    private static final CellStyle CONTENT = CellStyle.fg(CellColor.WHITE);
    private static final CellStyle TOP = new CellStyle(
        CellColor.BRIGHT_WHITE,
        CellColor.BLUE,
        true,
        false
    );
    private static final CellStyle TAB = CellStyle.fg(CellColor.CYAN);
    private static final CellStyle TAB_ACTIVE = new CellStyle(
        CellColor.BRIGHT_CYAN,
        CellColor.BLACK,
        true,
        false
    );
    private static final CellStyle POPUP = CellStyle.fg(CellColor.WHITE);
    private static final CellStyle POPUP_SELECTED = CellStyle.fg(CellColor.BRIGHT_CYAN);
    private static final CellStyle TOP_SELECTED = CellStyle.fg(CellColor.BRIGHT_BLUE);
    private static final CellStyle MONO_SELECTED = new CellStyle(
        CellColor.DEFAULT,
        CellColor.DEFAULT,
        true,
        false
    );
    private static final CellStyle MODAL_BODY = new CellStyle(
        CellColor.WHITE,
        CellColor.BRIGHT_BLACK,
        false,
        false
    );
    private static final CellStyle MODAL_BORDER = CellStyle.fg(CellColor.BRIGHT_BLUE);
    private static final CellStyle MODAL_TITLE = new CellStyle(
        CellColor.BRIGHT_YELLOW,
        CellColor.DEFAULT,
        true,
        false
    );
    private static final CellStyle ACTION = new CellStyle(
        CellColor.BLACK,
        CellColor.BRIGHT_CYAN,
        true,
        false
    );

    @Test
    void compositedCanvasFrameMatchesIndependentGoldenScreen() {
        CellBuffer frame = new CellBuffer(ROWS, COLS);
        Canvas canvas = new Canvas(frame);
        canvas.fill(0, 0, COLS, ROWS, BACKGROUND);

        MenuBar menu = new MenuBar(POPUP, POPUP_SELECTED, TOP_SELECTED, MONO_SELECTED);
        String topBar = menu.buildTopBar(Path.of("中.txt"), true, 0, 2, COLS, false);
        CellText.draw(canvas, 0, 0, topBar, TOP);

        TabBar tabs = new TabBar(TAB, CellStyle.DEFAULT, TAB_ACTIVE, MONO_SELECTED);
        tabs.draw(
            canvas,
            COLS,
            List.of(
                new TabBar.TabItem(Path.of("α.txt"), false, false),
                new TabBar.TabItem(Path.of("中e\u0301"), true, true)
            ),
            1,
            true
        );

        CellText.draw(canvas, 2, 2, "editor: A中B e\u0301", CONTENT);
        CellText.draw(canvas, 1, 8, "status: ready", CONTENT);
        CellText.draw(canvas, 2, 9, "footer", CONTENT);
        menu.draw(canvas, ROWS, COLS, true, TOP);

        Modal modal = new Modal(
            3,
            7,
            30,
            5,
            " T中e\u0301 ",
            MODAL_BODY,
            MODAL_BORDER,
            MODAL_TITLE
        );
        modal.draw(canvas);
        new Button(8, 30, "OK中").draw(canvas, ACTION);

        assertEquals(
            List.of(
                "·File···Edit···View···Tab···Settings···Help·",
                "[α.txt·x]·[#中»e\u0301*·x]·························",
                "··editor:·A中»B·e\u0301····························",
                "·······┌─·T中»e\u0301·───────────────────X─┐·······",
                "·······│····························│·······",
                "·······│····························│·······",
                "·······│····························│·······",
                "·······└────────────────────────────┘·······",
                "·status:·ready················[OK中»]········",
                "··footer····································"
            ),
            visibleCellRows(frame)
        );

        assertEquals(TOP, frame.get(0, 0).style());
        assertTrue(frame.get(0, 2).style().underline());
        assertEquals(TAB, frame.get(1, 1).style());
        assertEquals(TAB_ACTIVE, frame.get(1, 12).style());
        assertTrue(frame.get(1, 13).continuation());
        assertEquals(CONTENT, frame.get(2, 11).style());
        assertTrue(frame.get(2, 12).continuation());
        assertEquals(MODAL_BORDER, frame.get(3, 7).style());
        assertEquals(MODAL_TITLE, frame.get(3, 11).style());
        assertEquals(MODAL_TITLE, frame.get(3, modal.closeButton().col()).style());
        assertEquals(MODAL_BODY, frame.get(4, 8).style());
        assertEquals(BACKGROUND, frame.get(8, 29).style());
        assertEquals(ACTION, frame.get(8, 30).style());
        assertFalse(frame.get(8, 33).continuation());
        assertTrue(frame.get(8, 34).continuation());
        assertSignaturesMatchRebuiltFrame(frame);
    }

    private static List<String> visibleCellRows(CellBuffer frame) {
        List<String> rows = new ArrayList<>(frame.rows());
        for (int row = 0; row < frame.rows(); row++) {
            StringBuilder visible = new StringBuilder(frame.cols());
            for (int col = 0; col < frame.cols(); col++) {
                Cell cell = frame.get(row, col);
                if (cell.continuation()) {
                    visible.append('»');
                } else if (" ".equals(cell.grapheme())) {
                    visible.append('·');
                } else {
                    visible.append(cell.grapheme());
                }
            }
            rows.add(visible.toString());
        }
        return rows;
    }

    private static void assertSignaturesMatchRebuiltFrame(CellBuffer frame) {
        CellBuffer rebuilt = new CellBuffer(frame.rows(), frame.cols());
        for (int row = 0; row < frame.rows(); row++) {
            for (int col = 0; col < frame.cols(); col++) {
                Cell cell = frame.get(row, col);
                if (!cell.continuation()) {
                    rebuilt.set(row, col, cell.grapheme(), cell.style());
                }
            }
            assertEquals(rebuilt.rowSignature1(row), frame.rowSignature1(row), "signature1 row " + row);
            assertEquals(rebuilt.rowSignature2(row), frame.rowSignature2(row), "signature2 row " + row);
        }
    }
}
