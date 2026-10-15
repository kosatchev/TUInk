package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.scroll.Scrollbar;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WrappedTextViewportTest {
    private static final CellStyle STYLE = CellStyle.fg(CellColor.WHITE);
    private static final Theme THEME = role -> STYLE;
    private static final FocusContext FOCUSED = new FocusContext(
        true, true, FocusSpace.root(new Rect(0, 0, 80, 24))
    );

    @Test
    void reservesScrollbarSpaceBeforeWrappingAndEndReachesTheCompleteLastPage() {
        ListView.WrappedText source = ListView.wrappedText(List.of("0123456789abcdef", "tail"));
        Rect bounds = new Rect(0, 0, 6, 3);
        ListView.Placement initial = source.layout(bounds, ListView.State.initial());

        assertEquals(5, initial.rowCount());
        assertEquals(5, initial.contentBounds().width());
        assertTrue(initial.scrollbarBounds().isPresent());
        assertEquals(List.of("01234", "56789", "abcde"), paint(initial));

        ListView.State end = assertInstanceOf(
            ListView.StateChanged.class,
            initial.handle(KeyStroke.of(KeyStroke.Key.END), FOCUSED).output().orElseThrow()
        ).state();
        assertEquals(new ListView.State(-1, 2), end);
        ListView.Placement lastPage = source.layout(bounds, end, initial);
        assertEquals(List.of("abcde", "f    ", "tail "), paint(lastPage));
    }

    @Test
    void wrapsAtWordBoundariesAndDoesNotReserveAnUnneededScrollbar() {
        ListView.Placement words = ListView.wrappedText(List.of("alpha beta gamma")).layout(
            new Rect(0, 0, 7, 3), ListView.State.initial()
        );
        assertEquals(3, words.rowCount());
        assertTrue(words.scrollbarBounds().isEmpty());
        assertEquals(List.of("alpha  ", "beta   ", "gamma  "), paint(words));

        ListView.Placement exactFit = ListView.wrappedText(List.of("0123456789ab")).layout(
            new Rect(0, 0, 6, 2), ListView.State.initial()
        );
        assertEquals(2, exactFit.rowCount());
        assertEquals(6, exactFit.contentBounds().width());
        assertTrue(exactFit.scrollbarBounds().isEmpty());
    }

    @Test
    void preservesCombiningAndEmojiGraphemesAndTheLogicalLinesTabStops() {
        ListView.Placement unicode = ListView.wrappedText(List.of("A中e\u0301👩‍💻B")).layout(
            new Rect(0, 0, 4, 3), ListView.State.initial()
        );
        assertEquals(2, unicode.rowCount());
        assertEquals(List.of("A中e\u0301", "👩‍💻B ", "    "), paint(unicode));

        ListView.Placement tabs = ListView.wrappedText(List.of("abc\tZ")).layout(
            new Rect(0, 0, 3, 3), ListView.State.initial()
        );
        assertEquals(2, tabs.rowCount());
        assertEquals(List.of("abc", " Z ", "   "), paint(tabs));
    }

    @Test
    void tabExpansionPreservesOrphanDisplayCellsAcrossWrappingAndReflow() {
        for (String orphan : List.of("\u0301", "\u200D", "\uFE0F", "🏽")) {
            String display = orphan.equals("\u0301") ? "◌\u0301" : "�";
            ListView.WrappedText source = ListView.wrappedText(List.of("a\t" + orphan + "X"));
            ListView.Placement wide = source.layout(new Rect(0, 0, 8, 3), ListView.State.initial());
            assertEquals(List.of("a   " + display + "X  ", "        ", "        "), paint(wide));

            ListView.Placement wrapped = source.layout(new Rect(0, 0, 5, 3), wide.state(), wide);
            assertEquals(2, wrapped.rowCount());
            assertEquals(List.of("a    ", display + "X   ", "     "), paint(wrapped));

            ListView.Placement narrow = source.layout(new Rect(0, 0, 2, 4), wrapped.state(), wrapped);
            assertEquals(List.of("a ", "  ", display + "X", "  "), paint(narrow));
        }
    }

    @Test
    void resizingReflowsTheSuppliedScrollOffsetToItsLogicalPositionAndCancelsCapture() {
        ListView.WrappedText source = ListView.wrappedText(List.of("abcdefghijklmnopqrstuvwxyz0123"));
        ListView.Placement previous = source.layout(new Rect(0, 0, 6, 3), new ListView.State(-1, 2));
        assertEquals(6, previous.rowCount());
        assertEquals("klmno", paint(previous).getFirst());

        ListView.Placement resized = source.layout(
            new Rect(0, 0, 4, 3),
            new ListView.State(-1, 3, new Scrollbar.Drag(Scrollbar.Axis.VERTICAL, 0)),
            previous
        );
        assertEquals(10, resized.rowCount());
        assertEquals(new ListView.State(-1, 5), resized.state());
        assertEquals("pqr", paint(resized).getFirst());

        ListView.Placement sameWidth = source.layout(resized.bounds(), new ListView.State(-1, 6), resized);
        assertEquals(new ListView.State(-1, 6), sameWidth.state());
        assertEquals("stu", paint(sameWidth).getFirst());
    }

    @Test
    void reflowAlsoPreservesTheLogicalLineAndOnlyUsesPlacementsFromTheSameSource() {
        List<String> lines = List.of("abcdefghijklmnopqrst", "ABCDEFGHIJKLMNOPQRST");
        ListView.WrappedText source = ListView.wrappedText(lines);
        ListView.Placement previous = source.layout(new Rect(0, 0, 6, 2), new ListView.State(-1, 5));
        assertEquals("FGHIJ", paint(previous).getFirst());
        ListView.Placement resized = source.layout(new Rect(0, 0, 4, 2), previous.state(), previous);
        assertEquals(new ListView.State(-1, 8), resized.state());
        assertEquals("DEF", paint(resized).getFirst());

        ListView.WrappedText other = ListView.wrappedText(lines);
        ListView.Placement independent = other.layout(new Rect(0, 0, 4, 2), new ListView.State(-1, 5), previous);
        assertEquals(new ListView.State(-1, 5), independent.state());
    }

    @Test
    void sourceIsASnapshotAndEmptyOrTinyViewportsRetainCompleteGraphemes() {
        List<String> mutable = new ArrayList<>(List.of("中a", "", "end"));
        ListView.WrappedText source = ListView.wrappedText(mutable);
        mutable.set(0, "changed");
        ListView.Placement tiny = source.layout(new Rect(0, 0, 1, 2), ListView.State.initial());
        assertEquals(6, tiny.rowCount());
        assertTrue(tiny.scrollbarBounds().isEmpty());
        assertEquals(List.of("\uFFFD", "a"), paint(tiny));
        ListView.Placement wideEnough = source.layout(new Rect(0, 0, 2, 6), tiny.state(), tiny);
        assertEquals("中", paint(wideEnough).getFirst());

        ListView.Placement invisible = source.layout(new Rect(0, 0, 0, 0), ListView.State.initial());
        assertTrue(invisible.bounds().isEmpty());
        assertTrue(invisible.scrollbarBounds().isEmpty());
        ListView.Placement empty = ListView.wrappedText(List.of()).layout(new Rect(0, 0, 4, 2), new ListView.State(-1, 99));
        assertEquals(0, empty.rowCount());
        assertEquals(ListView.State.initial(), empty.state());
        assertEquals(List.of("    ", "    "), paint(empty));
    }

    private static List<String> paint(ListView.Placement placement) {
        CellBuffer buffer = new CellBuffer(placement.bounds().height(), placement.bounds().width());
        placement.paint(new Canvas(buffer), THEME, WidgetState.FOCUSED);
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < placement.contentBounds().height(); row++) {
            StringBuilder text = new StringBuilder();
            for (int column = 0; column < placement.contentBounds().width(); column++) {
                Cell cell = buffer.get(row, column);
                if (!cell.continuation()) {
                    text.append(cell.grapheme());
                }
            }
            rows.add(text.toString());
        }
        return rows;
    }
}
