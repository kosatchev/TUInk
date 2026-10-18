package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.widget.TabBar;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetThemeKeys;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TabBarViewTest {
    private static final CellStyle BAR = CellStyle.fg(CellColor.WHITE);
    private static final CellStyle ITEM = CellStyle.fg(CellColor.CYAN);
    private static final CellStyle ACTIVE = CellStyle.fg(CellColor.BRIGHT_GREEN);
    private static final CellStyle DISABLED = CellStyle.fg(CellColor.BRIGHT_BLACK);
    private static final Theme THEME = role -> {
        if (WidgetThemeKeys.TAB_ITEM_ACTIVE.equals(role)) {
            return ACTIVE;
        }
        if (WidgetThemeKeys.TAB_ITEM_DISABLED.equals(role)) {
            return DISABLED;
        }
        if (WidgetThemeKeys.TAB_ITEM.equals(role)) {
            return ITEM;
        }
        return BAR;
    };

    @Test
    void encodedLabelsKeepWholeEscapesAtEveryWidthAndTheirVisibleCloseButtonClosesTheSameItem() {
        assumeTrue(File.separatorChar == '/');
        Path path = Path.of(URI.create("file:///tmp/long-%FF-%FE-example.txt"));
        for (int width = 7; width <= 44; width++) {
            Rect bounds = new Rect(0, 1, width, 1);
            TabBarView.Placement placed = TabBarView.build(
                List.of(new TabBarView.TabItem(path, true, true)), 0, TabBar.State.initial())
                .place(bounds, new Rect(0, 0, width, 3));
            CellBuffer buffer = filled(3, width);
            placed.paint(new Canvas(buffer), THEME);
            String text = row(buffer, 1, 0, width);
            for (int index = text.indexOf('%'); index >= 0; index = text.indexOf('%', index + 3)) {
                assertTrue(index + 2 < text.length(), text);
                assertTrue(Character.digit(text.charAt(index + 1), 16) >= 0, text);
                assertTrue(Character.digit(text.charAt(index + 2), 16) >= 0, text);
            }
            int close = text.indexOf(" x]");
            if (close >= 0) {
                assertEquals(new TabBar.ClosePressed(0, placed.state()),
                    placed.handle(pointer(1, close + 1)).output().orElseThrow());
            }
        }
    }

    @Test
    void commonFormatterKeepsEditorPathDirtyAndSearchSemanticsOutsideTheWidget() {
        assertEquals(
            TabBarView.NO_NAME_LABEL,
            TabBarView.formatTabTitle(null, false, false)
        );
        assertEquals(
            "#notes.txt*",
            TabBarView.formatTabTitle(Path.of("folder", "notes.txt"), true, true)
        );
        assertEquals(
            Path.of("/").toString(),
            TabBarView.formatTabTitle(Path.of("/"), false, false)
        );
    }

    @Test
    void exactPlacementPaintsFormattedLabelsAndActiveStateOnce() {
        Rect bounds = new Rect(2, 1, 31, 1);
        TabBarView.Placement placement = TabBarView.build(
            List.of(
                new TabBarView.TabItem(Path.of("one.txt"), false, false),
                new TabBarView.TabItem(Path.of("folder", "two.txt"), true, true)
            ),
            1,
            TabBar.State.initial()
        ).place(bounds, new Rect(0, 0, 36, 4));
        CellBuffer buffer = filled(4, 36);

        placement.paint(new Canvas(buffer), THEME);

        assertEquals(bounds, placement.bounds());
        assertEquals(TabBar.State.initial(), placement.state());
        assertTrue(placement.enabled());
        assertEquals("[one.txt x] [#two.txt* x]      ", row(buffer, 1, 2, 31));
        assertEquals(ITEM, buffer.get(1, 3).style());
        assertEquals(ACTIVE, buffer.get(1, 15).style());
        assertEquals(".", buffer.get(1, 1).grapheme());
        assertEquals(".", buffer.get(1, 33).grapheme());
    }

    @Test
    void buildCopiesEditorItemsAndPlacementNormalizesTheApplicationState() {
        ArrayList<TabBarView.TabItem> source = new ArrayList<>();
        source.add(new TabBarView.TabItem(Path.of("a"), false, false));
        source.add(new TabBarView.TabItem(Path.of("b"), false, false));
        TabBarView view = TabBarView.build(source, 1, new TabBar.State(99));
        source.clear();

        TabBarView.Placement placement = view.place(
            new Rect(0, 1, 8, 1),
            new Rect(0, 0, 8, 3)
        );
        CellBuffer buffer = filled(3, 8);
        placement.paint(new Canvas(buffer), THEME);

        assertEquals(new TabBar.State(1), placement.state());
        assertTrue(row(buffer, 1, 0, 8).contains("[b x]"));
    }

    @Test
    void partialInteractionClipDisablesTheExactPlacementAndItsPointerRouting() {
        Rect bounds = new Rect(2, 1, 8, 1);
        TabBarView.Placement placement = TabBarView.build(
            List.of(new TabBarView.TabItem(Path.of("a"), false, false)),
            0,
            TabBar.State.initial()
        ).place(bounds, new Rect(0, 0, 9, 3));
        CellBuffer buffer = filled(3, 12);

        placement.paint(new Canvas(buffer), THEME);

        assertFalse(placement.enabled());
        assertEquals(DISABLED, buffer.get(1, 3).style());
        assertFalse(placement.handle(pointer(1, 3)).consumed());
        assertTrue(placement.handle(pointer(1, 3)).output().isEmpty());
        assertFalse(placement.contains(pointer(1, 9)), "the parent clip excludes the right edge");
    }

    @Test
    void interactionEligibilityUsesTheFinalSingleRowPlacement() {
        TabBarView.Placement placement = TabBarView.build(
            List.of(new TabBarView.TabItem(Path.of("a"), false, false)),
            0,
            TabBar.State.initial()
        ).place(new Rect(2, 1, 8, 3), new Rect(0, 0, 12, 2));

        assertEquals(new Rect(2, 1, 8, 1), placement.bounds());
        assertTrue(placement.enabled());
        assertTrue(placement.handle(pointer(1, 3)).consumed());
    }

    @Test
    void revealingPlacementKeepsTheActiveTabPaintAndHitTargetTogether() {
        TabBarView view = TabBarView.build(
            List.of(
                new TabBarView.TabItem(Path.of("file0.txt"), false, false),
                new TabBarView.TabItem(Path.of("file1.txt"), false, false),
                new TabBarView.TabItem(Path.of("file2.txt"), false, false),
                new TabBarView.TabItem(Path.of("file3.txt"), false, false)
            ),
            3,
            TabBar.State.initial()
        );
        Rect bounds = new Rect(0, 1, 24, 1);
        TabBarView.Placement placement = view.placeRevealingActive(bounds, new Rect(0, 0, 24, 3));
        CellBuffer buffer = filled(3, 24);
        placement.paint(new Canvas(buffer), THEME);

        assertEquals(new TabBar.State(3), placement.state());
        assertTrue(row(buffer, 1, 0, 24).contains("[file3.txt x]"));
        assertEquals(ACTIVE, buffer.get(1, 2).style());
        assertEquals(
            new TabBar.TabPressed(3, placement.state()),
            placement.handle(pointer(1, 2)).output().orElseThrow()
        );
        assertEquals(TabBar.State.initial(), view.place(bounds, new Rect(0, 0, 24, 3)).state());
    }

    @Test
    void dirtyAndSearchMarkersAndRenamesKeepTheVisibleActiveTabInView() {
        Rect bounds = new Rect(0, 1, 33, 1);
        Rect clip = new Rect(0, 0, 33, 3);
        List<TabBarView.TabItem> originalItems = List.of(
            new TabBarView.TabItem(null, false, false),
            new TabBarView.TabItem(null, false, false),
            new TabBarView.TabItem(null, false, false)
        );
        TabBarView.Placement previous = TabBarView.build(originalItems, 1, TabBar.State.initial())
            .place(bounds, clip);

        for (List<TabBarView.TabItem> changed : List.of(
            List.of(originalItems.get(0), new TabBarView.TabItem(null, true, false), originalItems.get(2)),
            List.of(new TabBarView.TabItem(null, false, true), originalItems.get(1), originalItems.get(2)),
            List.of(originalItems.get(0), new TabBarView.TabItem(Path.of("longer-name.txt"), false, false), originalItems.get(2))
        )) {
            TabBarView.Placement next = TabBarView.build(changed, 1, previous.state())
                .placeKeepingActiveVisible(bounds, clip, previous);

            assertEquals(new TabBar.State(1), next.state());
            assertEquals(
                new TabBar.TabPressed(1, next.state()),
                next.handle(pointer(1, 2)).output().orElseThrow()
            );
            CellBuffer buffer = filled(3, 33);
            next.paint(new Canvas(buffer), THEME);
            assertEquals(ACTIVE, buffer.get(1, 2).style());

            TabBarView.Placement clean = TabBarView.build(originalItems, 1, next.state())
                .placeKeepingActiveVisible(bounds, clip, next);
            assertEquals(next.state(), clean.state(), "shrinking labels should retain the current viewport");
        }
    }

    private static PointerEvent pointer(int row, int column) {
        return new PointerEvent(
            row,
            column,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT,
            Set.of()
        );
    }

    private static CellBuffer filled(int rows, int columns) {
        CellBuffer buffer = new CellBuffer(rows, columns);
        buffer.fill(new Cell(".", CellStyle.DEFAULT));
        return buffer;
    }

    private static String row(CellBuffer buffer, int row, int start, int width) {
        StringBuilder result = new StringBuilder(width);
        for (int column = start; column < start + width; column++) {
            Cell cell = buffer.get(row, column);
            if (!cell.continuation()) {
                result.append(cell.grapheme());
            }
        }
        return result.toString();
    }
}
