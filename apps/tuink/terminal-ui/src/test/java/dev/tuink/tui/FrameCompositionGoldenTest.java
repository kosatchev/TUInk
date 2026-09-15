package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.MenuBar;
import dev.tuiframe.widget.Modal;
import dev.tuiframe.widget.PlacedModal;
import dev.tuiframe.widget.TabBar;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.widget.WidgetThemeKeys;
import dev.tuink.tui.ui.TabBarView;
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
    private static final Theme WIDGET_THEME = key -> {
        if (WidgetThemeKeys.MENU_BAR.equals(key)) {
            return TOP;
        }
        if (WidgetThemeKeys.TAB_BAR.equals(key)) {
            return TAB;
        }
        if (WidgetThemeKeys.TAB_ITEM.equals(key)) {
            return TAB;
        }
        if (WidgetThemeKeys.TAB_ITEM_ACTIVE.equals(key)) {
            return TAB_ACTIVE;
        }
        if (WidgetThemeKeys.MODAL_BODY.equals(key)) {
            return MODAL_BODY;
        }
        if (WidgetThemeKeys.MODAL_BORDER.equals(key)) {
            return MODAL_BORDER;
        }
        if (WidgetThemeKeys.MODAL_TITLE.equals(key)) {
            return MODAL_TITLE;
        }
        if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key)) {
            return ACTION;
        }
        return null;
    };

    @Test
    void compositedCanvasFrameMatchesIndependentGoldenScreen() {
        CellBuffer frame = new CellBuffer(ROWS, COLS);
        Canvas canvas = new Canvas(frame);
        canvas.fill(0, 0, COLS, ROWS, BACKGROUND);

        TopMenuView menu = TopMenuView.build(
            new UiSessionState(),
            List.of(
                new TopMenuView.TabItem(Path.of("α.txt"), false, false),
                new TopMenuView.TabItem(Path.of("中e\u0301"), true, true)
            ),
            1,
            "Cyan",
            new LegacyCommandBridge(),
            MenuBar.State.closed()
        );
        TopMenuView.TopBar topBar = menu.topBar(Path.of("中.txt"), true, COLS, false);
        CellText.draw(canvas, 0, 0, topBar.text(), TOP);

        TabBarView.Placement tabs = TabBarView.build(
            List.of(
                new TabBarView.TabItem(Path.of("α.txt"), false, false),
                new TabBarView.TabItem(Path.of("中e\u0301"), true, true)
            ),
            1,
            TabBar.State.initial()
        ).place(new Rect(0, 1, COLS, 1), new Rect(0, 0, COLS, ROWS));
        tabs.paint(canvas, WIDGET_THEME);

        CellText.draw(canvas, 2, 2, "editor: A中B e\u0301", CONTENT);
        CellText.draw(canvas, 1, 8, "status: ready", CONTENT);
        CellText.draw(canvas, 2, 9, "footer", CONTENT);
        menu.place(new Rect(0, 0, COLS, ROWS)).paint(canvas, WIDGET_THEME);

        Modal modal = new Modal(30, 5, " T中e\u0301 ");
        PlacedModal placedModal = modal.layout(new Rect(7, 3, 30, 5));
        placedModal.paint(canvas, WIDGET_THEME);
        Button action = new Button("OK中");
        action.layout(new Rect(30, 8, action.width(), 1))
            .paint(canvas, WIDGET_THEME, WidgetState.FOCUSED);

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
        assertTrue(placedModal.closeHit(3, 34));
        assertEquals(MODAL_TITLE, frame.get(3, 34).style());
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
