package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.SearchOptions;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetThemeKeys;
import dev.tuink.tui.flow.SearchFocus;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchBarViewTest {
    private static final List<SearchFocus> TARGET_ORDER = List.of(
        SearchFocus.FIND,
        SearchFocus.CASE,
        SearchFocus.WORD,
        SearchFocus.WILDCARD,
        SearchFocus.ALL,
        SearchFocus.REPLACE,
        SearchFocus.NEXT_BUTTON,
        SearchFocus.PREV_BUTTON,
        SearchFocus.REPLACE_BUTTON,
        SearchFocus.ALL_BUTTON
    );
    private static final CellStyle PROMPT = new CellStyle(
        CellColor.BRIGHT_WHITE,
        CellColor.RED,
        false,
        false
    );
    private static final CellStyle NORMAL = CellStyle.fg(CellColor.WHITE);
    private static final CellStyle FOCUSED = new CellStyle(
        CellColor.BLACK,
        CellColor.BRIGHT_WHITE,
        true,
        false
    );
    private static final CellStyle DISABLED = CellStyle.fg(CellColor.BRIGHT_BLACK);
    private static final Theme THEME = role -> {
        if (WidgetThemeKeys.TEXT_FIELD_FOCUSED.equals(role)
            || WidgetThemeKeys.BUTTON_FOCUSED.equals(role)) {
            return FOCUSED;
        }
        if (WidgetThemeKeys.TEXT_FIELD_DISABLED.equals(role)
            || WidgetThemeKeys.BUTTON_DISABLED.equals(role)) {
            return DISABLED;
        }
        return NORMAL;
    };

    @Test
    void scopeHasTheExpectedFocusOrderAndNonOverlappingControlPlacements() {
        int columns = 80;
        int topRow = 2;
        SearchBarView.Placement placement = view(new TextField(), new TextField(), columns)
            .place(new Object(), topRow, viewport(5, columns));

        assertEquals(TARGET_ORDER, placement.focusScope().tabOrder());
        assertEquals(TARGET_ORDER, placement.focusScope().pointerTopmostFirst());
        assertEquals(new Rect(6, topRow, 39, 1), placement.targetBounds(SearchFocus.FIND));
        assertEquals(new Rect(9, topRow + 1, 33, 1), placement.targetBounds(SearchFocus.REPLACE));
        assertEquals(new Rect(45, topRow, 6, 1), placement.targetBounds(SearchFocus.CASE));
        assertEquals(new Rect(52, topRow, 8, 1), placement.targetBounds(SearchFocus.WORD));
        assertEquals(new Rect(61, topRow, 5, 1), placement.targetBounds(SearchFocus.WILDCARD));
        assertEquals(new Rect(67, topRow, 12, 1), placement.targetBounds(SearchFocus.ALL));
        assertEquals(new Rect(42, topRow + 1, 6, 1), placement.targetBounds(SearchFocus.NEXT_BUTTON));
        assertEquals(new Rect(49, topRow + 1, 6, 1), placement.targetBounds(SearchFocus.PREV_BUTTON));
        assertEquals(new Rect(56, topRow + 1, 9, 1), placement.targetBounds(SearchFocus.REPLACE_BUTTON));
        assertEquals(new Rect(66, topRow + 1, 13, 1), placement.targetBounds(SearchFocus.ALL_BUTTON));
        for (SearchFocus target : TARGET_ORDER) {
            RoutedTarget<SearchFocus, SearchBarView.Action> routed = target(placement, target);
            assertTrue(routed.enabled(), target.toString());
            assertTrue(routed.focusable(), target.toString());
        }
    }

    @Test
    void rasterSkeletonAndTenWidgetPaintersComposeEachValueExactlyOnce() {
        int columns = 80;
        int topRow = 2;
        TextField query = new TextField();
        query.set("needle");
        TextField replacement = new TextField();
        replacement.set("swap");
        SearchBarView view = SearchBarView.build(
            query,
            replacement,
            columns,
            true,
            SearchOptions.MatchMode.SUBSTRING,
            true
        );
        SearchBarView.Placement placement = view.place(
            new Object(),
            topRow,
            viewport(5, columns)
        );

        assertEquals(CellText.fit(SearchBarView.FIND_LABEL, columns), placement.row1());
        assertEquals(CellText.fit(SearchBarView.REPLACE_LABEL, columns), placement.row2());
        assertFalse(placement.row1().contains(query.text()));
        assertFalse(placement.row2().contains(replacement.text()));
        assertFalse(placement.text().contains("[Next]"));
        assertFalse(placement.text().contains("[Аа:x]"));

        FocusManager<SearchFocus> focus = new FocusManager<>();
        placement.activate(focus);
        CellBuffer buffer = new CellBuffer(5, columns);
        Canvas canvas = new Canvas(buffer);
        canvas.fill(0, topRow, columns, 2, PROMPT);
        CellText.draw(canvas, 0, topRow, placement.row1(), PROMPT);
        CellText.draw(canvas, 0, topRow + 1, placement.row2(), PROMPT);
        placement.paint(canvas, THEME, focus);

        String expectedRow1 = "Find: "
            + "needle"
            + " ".repeat(33)
            + "[Аа:x] [word: ] [*: ] [All tabs:x] ";
        String expectedRow2 = "Replace: "
            + "swap"
            + " ".repeat(29)
            + "[Next] [Prev] [Replace] [Replace all] ";
        assertEquals(expectedRow1, visibleCellRow(buffer, topRow));
        assertEquals(expectedRow2, visibleCellRow(buffer, topRow + 1));
        assertEquals(1, occurrences(visibleCellRow(buffer, topRow), query.text()));
        assertEquals(1, occurrences(visibleCellRow(buffer, topRow + 1), replacement.text()));
        assertEquals(PROMPT, buffer.get(topRow, 0).style());
        assertEquals(FOCUSED, buffer.get(topRow, 6).style());
        assertEquals(NORMAL, buffer.get(topRow + 1, 9).style());
        assertEquals(NORMAL, buffer.get(topRow, 45).style());
        assertTrue(canvas.put(0, 0, "Z", NORMAL), "widget paint must restore the parent clip");
    }

    @Test
    void cursorComesOnlyFromTheFocusedFieldAndUsesItsUnicodeViewport() {
        int columns = 50;
        int topRow = 2;
        TextField query = new TextField();
        query.set("αβ中😀long-tail");
        TextField replacement = new TextField();
        replacement.set("replacement-tail");
        SearchBarView.Placement placement = view(query, replacement, columns)
            .place(new Object(), topRow, viewport(5, columns));
        FocusManager<SearchFocus> focus = new FocusManager<>();
        placement.activate(focus);

        Rect queryBounds = placement.targetBounds(SearchFocus.FIND);
        Rect replacementBounds = placement.targetBounds(SearchFocus.REPLACE);
        assertEquals(
            new Point(queryBounds.rightExclusive() - 1, topRow),
            placement.cursorPosition(focus).orElseThrow()
        );

        focus.focus(placement.focusScope(), SearchFocus.REPLACE);
        assertEquals(
            new Point(replacementBounds.rightExclusive() - 1, topRow + 1),
            placement.cursorPosition(focus).orElseThrow()
        );

        focus.focus(placement.focusScope(), SearchFocus.CASE);
        assertTrue(placement.cursorPosition(focus).isEmpty());
    }

    @Test
    void tinyAndPartiallyClippedFieldsCannotOfferReplacementContext() {
        int columns = 10;
        int topRow = 2;
        Rect fullTinyClip = viewport(5, columns);
        SearchBarView.Placement tiny = view(new TextField(), new TextField(), columns)
            .place(new Object(), topRow, fullTinyClip);
        FocusManager<SearchFocus> tinyFocus = new FocusManager<>();
        tiny.activate(tinyFocus);

        assertEquals(4, tiny.targetBounds(SearchFocus.FIND).width());
        assertTrue(target(tiny, SearchFocus.FIND).enabled());
        assertFalse(target(tiny, SearchFocus.CASE).enabled());
        assertFalse(target(tiny, SearchFocus.WORD).enabled());
        assertFalse(target(tiny, SearchFocus.NEXT_BUTTON).enabled());
        assertFalse(target(tiny, SearchFocus.PREV_BUTTON).enabled());
        assertEquals(SearchFocus.FIND, tinyFocus.focused().orElseThrow());
        assertTrue(tiny.cursorPosition(tinyFocus).isPresent());

        Rect clipped = new Rect(0, 0, 4, 5);
        SearchBarView.Placement hidden = view(new TextField(), new TextField(), columns)
            .place(new Object(), topRow, clipped);
        FocusManager<SearchFocus> hiddenFocus = new FocusManager<>();
        hidden.activate(hiddenFocus);
        for (SearchFocus id : TARGET_ORDER) {
            Rect bounds = hidden.targetBounds(id);
            boolean expected = !bounds.isEmpty() && clipped.contains(bounds);
            assertEquals(expected, target(hidden, id).enabled(), id.toString());
            assertEquals(expected, target(hidden, id).focusable(), id.toString());
        }
        assertTrue(hiddenFocus.focused().isEmpty());
        assertTrue(hidden.cursorPosition(hiddenFocus).isEmpty());

        CellBuffer buffer = new CellBuffer(5, columns);
        hidden.paint(new Canvas(buffer), THEME, hiddenFocus);
        assertEquals(DISABLED, buffer.get(topRow, 6).style());
    }

    @Test
    void paintAndCursorLookupDoNotActivateOrMutateFocus() {
        TextField query = new TextField();
        query.set("query");
        SearchBarView.Placement placement = view(query, new TextField(), 60)
            .place(new Object(), 2, viewport(5, 60));
        FocusManager<SearchFocus> focus = new FocusManager<>();

        placement.paint(new Canvas(new CellBuffer(5, 60)), THEME, focus);

        assertTrue(focus.activeScopeId().isEmpty());
        assertTrue(focus.focused().isEmpty());
        assertTrue(placement.cursorPosition(focus).isEmpty());
        assertEquals("query", query.text());
    }

    private static SearchBarView view(
        TextField query,
        TextField replacement,
        int columns
    ) {
        return SearchBarView.build(
            query,
            replacement,
            columns,
            false,
            SearchOptions.MatchMode.SUBSTRING,
            false
        );
    }

    private static RoutedTarget<SearchFocus, SearchBarView.Action> target(
        SearchBarView.Placement placement,
        SearchFocus target
    ) {
        return placement.focusScope().target(target).orElseThrow();
    }

    private static Rect viewport(int rows, int columns) {
        return new Rect(0, 0, columns, rows);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String visibleCellRow(CellBuffer buffer, int row) {
        StringBuilder visible = new StringBuilder(buffer.cols());
        for (int column = 0; column < buffer.cols(); column++) {
            Cell cell = buffer.get(row, column);
            visible.append(cell.continuation() ? "»" : cell.grapheme());
        }
        return visible.toString();
    }
}
