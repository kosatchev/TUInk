package dev.tuink.terminal.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.cell.Cell;
import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.geometry.Point;
import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.geometry.Size;
import dev.tuink.terminal.input.KeyStroke;
import dev.tuink.terminal.input.PasteEvent;
import dev.tuink.terminal.input.PointerEvent;
import dev.tuink.terminal.input.TextInput;
import dev.tuink.terminal.style.CellColor;
import dev.tuink.terminal.style.CellStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TabBarTest {
    private static final Rect ROOT_CLIP = new Rect(0, 0, 80, 24);
    private static final FocusContext POINTER_CONTEXT = new FocusContext(
        false,
        true,
        FocusSpace.root(ROOT_CLIP)
    );
    private static final FocusContext DISABLED_CONTEXT = new FocusContext(
        false,
        false,
        FocusSpace.root(ROOT_CLIP)
    );

    private static final CellStyle BAR = CellStyle.fg(CellColor.WHITE);
    private static final CellStyle ITEM = CellStyle.fg(CellColor.CYAN);
    private static final CellStyle ACTIVE = CellStyle.fg(CellColor.BRIGHT_GREEN);
    private static final CellStyle DISABLED = CellStyle.fg(CellColor.BRIGHT_BLACK);
    private static final CellStyle ACCENT = CellStyle.fg(CellColor.BRIGHT_MAGENTA);
    private static final Theme THEME = role -> {
        if (WidgetThemeKeys.TAB_BAR.equals(role)) {
            return BAR;
        }
        if (WidgetThemeKeys.TAB_ITEM.equals(role)) {
            return ITEM;
        }
        if (WidgetThemeKeys.TAB_ITEM_ACTIVE.equals(role)) {
            return ACTIVE;
        }
        if (WidgetThemeKeys.TAB_ITEM_DISABLED.equals(role)) {
            return DISABLED;
        }
        return null;
    };

    @Test
    void validatesAndCopiesTheGenericModelAndMeasuresOneRow() {
        List<TabBar.Item> source = new ArrayList<>();
        source.add(new TabBar.Item("alpha", true));
        TabBar bar = new TabBar(source, 0, TabBar.State.initial());
        source.set(0, new TabBar.Item("changed", true));
        source.add(new TabBar.Item("late", true));

        assertEquals(new Size(12, 1), bar.measure(new Size(12, 4)));
        assertEquals(Size.ZERO, bar.measure(Size.ZERO));
        TabBar.Placement placement = bar.layout(new Rect(1, 2, 12, 4));
        assertEquals(new Rect(1, 2, 12, 1), placement.bounds());

        CellBuffer buffer = filled(5, 16);
        placement.paint(new Canvas(buffer), THEME, WidgetState.FOCUSED);
        assertEquals("[alpha x]", cellText(buffer, 2, 1, 9));

        assertThrows(
            IllegalArgumentException.class,
            () -> new TabBar(List.of(), 0, TabBar.State.initial())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new TabBar(List.of(new TabBar.Item("a", true)), -2, TabBar.State.initial())
        );
        assertThrows(NullPointerException.class, () -> new TabBar.Item(null, true));
        assertThrows(IllegalArgumentException.class, () -> new TabBar.State(-1));
    }

    @Test
    void paintsUnicodeControlsAndTabsWithStableWholeTabPhaseAndThemeRoles() {
        TabBar.Placement placement = new TabBar(
            List.of(
                new TabBar.Item("\u0001\t中e\u0301", true),
                new TabBar.Item("b", false)
            ),
            0,
            TabBar.State.initial()
        ).layout(new Rect(2, 1, 18, 1));
        CellBuffer focused = filled(5, 28);
        Canvas canvas = new Canvas(focused);
        canvas.translate(3, 1);

        placement.paint(canvas, THEME, WidgetState.FOCUSED);

        assertEquals("[", focused.get(2, 5).grapheme());
        assertEquals("␁", focused.get(2, 6).grapheme());
        assertEquals(" ", focused.get(2, 7).grapheme());
        assertEquals(" ", focused.get(2, 8).grapheme());
        assertEquals("中", focused.get(2, 9).grapheme());
        assertTrue(focused.get(2, 10).continuation());
        assertEquals("e\u0301", focused.get(2, 11).grapheme());
        assertEquals("x", focused.get(2, 13).grapheme());
        assertEquals(ACTIVE, focused.get(2, 5).style());
        assertEquals(ACTIVE, focused.get(2, 10).style());
        assertEquals("[", focused.get(2, 16).grapheme());
        assertEquals(ITEM, focused.get(2, 16).style());
        assertEquals(BAR, focused.get(2, 20).style());
        assertTrue(canvas.put(0, 0, "z", ACCENT), "paint must restore Canvas translation and clip");
        assertEquals("z", focused.get(1, 3).grapheme());

        CellBuffer disabled = filled(5, 28);
        Canvas disabledCanvas = new Canvas(disabled);
        disabledCanvas.translate(3, 1);
        placement.paint(disabledCanvas, THEME, WidgetState.DISABLED);
        assertEquals(DISABLED, disabled.get(2, 5).style());
        assertEquals(DISABLED, disabled.get(2, 16).style());
        assertEquals(BAR, disabled.get(2, 20).style());

        Theme partial = role -> WidgetThemeKeys.TAB_BAR.equals(role)
            ? BAR
            : WidgetThemeKeys.TAB_ITEM.equals(role) ? ITEM : null;
        CellBuffer fallback = filled(5, 28);
        Canvas fallbackCanvas = new Canvas(fallback);
        fallbackCanvas.translate(3, 1);
        placement.paint(fallbackCanvas, partial, WidgetState.FOCUSED);
        assertEquals(ITEM, fallback.get(2, 5).style());
    }

    @Test
    void overflowUsesExactPreparedGeometryForPaintAndPointerActions() {
        List<TabBar.Item> items = simpleItems("a", "b", "c");
        TabBar.Placement first = new TabBar(
            items,
            0,
            TabBar.State.initial()
        ).layout(new Rect(0, 0, 14, 1));
        CellBuffer buffer = filled(2, 16);

        first.paint(new Canvas(buffer), THEME, WidgetState.NORMAL);

        assertEquals(" ", buffer.get(0, 0).grapheme());
        assertEquals("[a x]", cellText(buffer, 0, 1, 5));
        assertEquals("[b x]", cellText(buffer, 0, 7, 5));
        assertEquals(">", buffer.get(0, 13).grapheme());
        assertEquals(ACTIVE, buffer.get(0, 1).style());
        assertEquals(ITEM, buffer.get(0, 7).style());

        assertEquals(
            new TabBar.TabPressed(0, TabBar.State.initial()),
            output(first.handle(press(0, 2), POINTER_CONTEXT), TabBar.TabPressed.class)
        );
        assertEquals(
            new TabBar.ClosePressed(0, TabBar.State.initial()),
            output(first.handle(press(0, 4), POINTER_CONTEXT), TabBar.ClosePressed.class)
        );
        assertEquals(
            new TabBar.StateChanged(new TabBar.State(1)),
            output(first.handle(press(0, 13), POINTER_CONTEXT), TabBar.StateChanged.class)
        );
        assertIgnored(first.handle(press(0, 0), POINTER_CONTEXT));
        assertIgnored(first.handle(press(0, 6), POINTER_CONTEXT));

        TabBar.Placement scrolled = new TabBar(
            items,
            1,
            new TabBar.State(1)
        ).layout(new Rect(0, 0, 14, 1));
        CellBuffer scrolledBuffer = filled(2, 16);
        scrolled.paint(new Canvas(scrolledBuffer), THEME, WidgetState.NORMAL);
        assertEquals("<", scrolledBuffer.get(0, 0).grapheme());
        assertEquals("[b x]", cellText(scrolledBuffer, 0, 1, 5));
        assertEquals("[c x]", cellText(scrolledBuffer, 0, 7, 5));
        assertEquals(" ", scrolledBuffer.get(0, 13).grapheme());
        assertEquals(
            new TabBar.StateChanged(TabBar.State.initial()),
            output(scrolled.handle(press(0, 0), POINTER_CONTEXT), TabBar.StateChanged.class)
        );
    }

    @Test
    void wheelIsPointerLocalBidirectionalAndConsumedAtBoundaries() {
        List<TabBar.Item> items = simpleItems("a", "b", "c");
        TabBar.Placement middle = new TabBar(
            items,
            1,
            new TabBar.State(1)
        ).layout(new Rect(2, 1, 8, 1));

        assertStateChange(0, middle.handle(scroll(1, 4, PointerEvent.Kind.SCROLL_UP), POINTER_CONTEXT));
        assertStateChange(0, middle.handle(scroll(1, 4, PointerEvent.Kind.SCROLL_LEFT), POINTER_CONTEXT));
        assertStateChange(2, middle.handle(scroll(1, 4, PointerEvent.Kind.SCROLL_DOWN), POINTER_CONTEXT));
        assertStateChange(2, middle.handle(scroll(1, 4, PointerEvent.Kind.SCROLL_RIGHT), POINTER_CONTEXT));

        TabBar.Placement first = new TabBar(
            items,
            0,
            TabBar.State.initial()
        ).layout(new Rect(2, 1, 8, 1));
        InteractionResult<TabBar.Action> atLeft = first.handle(
            scroll(1, 4, PointerEvent.Kind.SCROLL_UP),
            POINTER_CONTEXT
        );
        assertTrue(atLeft.consumed());
        assertTrue(atLeft.output().isEmpty());

        TabBar.Placement last = new TabBar(
            items,
            2,
            new TabBar.State(2)
        ).layout(new Rect(2, 1, 8, 1));
        InteractionResult<TabBar.Action> atRight = last.handle(
            scroll(1, 4, PointerEvent.Kind.SCROLL_DOWN),
            POINTER_CONTEXT
        );
        assertTrue(atRight.consumed());
        assertTrue(atRight.output().isEmpty());

        TabBar.Placement allFit = new TabBar(
            items,
            0,
            new TabBar.State(2)
        ).layout(new Rect(2, 1, 24, 1));
        assertEquals(TabBar.State.initial(), allFit.state());
        InteractionResult<TabBar.Action> noOverflow = allFit.handle(
            scroll(1, 4, PointerEvent.Kind.SCROLL_DOWN),
            POINTER_CONTEXT
        );
        assertTrue(noOverflow.consumed());
        assertTrue(noOverflow.output().isEmpty());

        assertIgnored(middle.handle(scroll(2, 4, PointerEvent.Kind.SCROLL_DOWN), POINTER_CONTEXT));
        assertIgnored(middle.handle(scroll(1, 4, PointerEvent.Kind.SCROLL_DOWN), DISABLED_CONTEXT));
    }

    @Test
    void pointerMappingHonorsTranslationAndParentClipWithoutRequestingFocus() {
        TabBar.Placement placement = new TabBar(
            simpleItems("a", "b", "c"),
            0,
            TabBar.State.initial()
        ).layout(new Rect(2, 1, 14, 1));
        FocusContext translated = new FocusContext(
            false,
            true,
            new FocusSpace(new Point(10, 5), new Rect(0, 0, 20, 4))
        );
        InteractionResult<TabBar.Action> close = placement.handle(press(6, 16), translated);

        assertEquals(
            new TabBar.ClosePressed(0, TabBar.State.initial()),
            output(close, TabBar.ClosePressed.class)
        );
        assertEquals(FocusRequest.NONE, close.focusRequest());

        FocusContext clipped = new FocusContext(
            false,
            true,
            new FocusSpace(new Point(10, 5), new Rect(0, 0, 6, 4))
        );
        assertIgnored(placement.handle(press(6, 16), clipped));
    }

    @Test
    void oversizedAndTinyTabsRemainVisibleWithoutSplittingWideGraphemes() {
        String longLabel = "a".repeat(100_000) + "中";
        TabBar.Placement oversized = new TabBar(
            List.of(new TabBar.Item(longLabel, true)),
            0,
            TabBar.State.initial()
        ).layout(new Rect(0, 0, 8, 1));
        CellBuffer large = filled(2, 10);

        oversized.paint(new Canvas(large), THEME, WidgetState.FOCUSED);

        assertEquals("[aaaa x]", cellText(large, 0, 0, 8));
        assertEquals(
            new TabBar.ClosePressed(0, TabBar.State.initial()),
            output(oversized.handle(press(0, 6), POINTER_CONTEXT), TabBar.ClosePressed.class)
        );
        assertFalse(large.get(0, 7).continuation());

        TabBar.Placement crossingWide = new TabBar(
            List.of(new TabBar.Item("中", true)),
            0,
            TabBar.State.initial()
        ).layout(new Rect(0, 0, 5, 1));
        CellBuffer wide = filled(2, 7);
        crossingWide.paint(new Canvas(wide), THEME, WidgetState.FOCUSED);
        assertEquals("[ x] ", cellText(wide, 0, 0, 5));
        for (int col = 0; col < 5; col++) {
            assertFalse(wide.get(0, col).continuation());
        }

        TabBar.Placement oneCell = new TabBar(
            simpleItems("a", "b"),
            0,
            TabBar.State.initial()
        ).layout(new Rect(0, 0, 1, 1));
        CellBuffer tiny = filled(1, 1);
        oneCell.paint(new Canvas(tiny), THEME, WidgetState.FOCUSED);
        assertEquals("[", tiny.get(0, 0).grapheme());
        assertEquals(ACTIVE, tiny.get(0, 0).style());
        assertEquals(
            new TabBar.TabPressed(0, TabBar.State.initial()),
            output(oneCell.handle(press(0, 0), POINTER_CONTEXT), TabBar.TabPressed.class)
        );
        assertStateChange(
            1,
            oneCell.handle(scroll(0, 0, PointerEvent.Kind.SCROLL_DOWN), POINTER_CONTEXT)
        );
    }

    @Test
    void strictParentClipPreservesCrossingWideCellsAndCanvasState() {
        TabBar.Placement placement = new TabBar(
            List.of(new TabBar.Item("alpha", true)),
            0,
            TabBar.State.initial()
        ).layout(new Rect(1, 1, 8, 3));
        CellBuffer buffer = filled(5, 15);
        buffer.set(2, 3, "中", ACCENT);
        Cell lead = buffer.get(2, 3);
        Cell continuation = buffer.get(2, 4);
        Canvas canvas = new Canvas(buffer);
        canvas.translate(2, 1);
        assertTrue(canvas.clip(new Rect(2, 1, 4, 1)));

        placement.paint(canvas, THEME, WidgetState.FOCUSED);

        assertSame(lead, buffer.get(2, 3));
        assertSame(continuation, buffer.get(2, 4));
        assertEquals(ACTIVE, buffer.get(2, 5).style());
        assertEquals(".", buffer.get(2, 8).grapheme());
        assertFalse(canvas.put(1, 1, "q", ACCENT), "the caller's parent clip must remain active");
        assertTrue(canvas.put(4, 1, "z", ACCENT));
        assertEquals("z", buffer.get(2, 6).grapheme());
        assertEquals(new Rect(1, 1, 8, 1), placement.bounds());
    }

    @Test
    void heightZeroAndEmptyModelsDoNotPaintOrHandleAndExtremeBoundsStaySafe() {
        TabBar.Placement zeroHeight = new TabBar(
            List.of(new TabBar.Item("hidden", true)),
            0,
            TabBar.State.initial()
        ).layout(new Rect(1, 1, 8, 0));
        CellBuffer zero = filled(3, 12);
        zeroHeight.paint(new Canvas(zero), THEME, WidgetState.FOCUSED);
        assertEquals(".", zero.get(1, 1).grapheme());
        assertIgnored(zeroHeight.handle(press(1, 2), POINTER_CONTEXT));

        TabBar.Placement empty = new TabBar(
            List.of(),
            -1,
            new TabBar.State(99)
        ).layout(new Rect(1, 1, 8, 1));
        assertEquals(TabBar.State.initial(), empty.state());
        InteractionResult<TabBar.Action> emptyWheel = empty.handle(
            scroll(1, 2, PointerEvent.Kind.SCROLL_DOWN),
            POINTER_CONTEXT
        );
        assertIgnored(emptyWheel);

        TabBar.Placement extreme = new TabBar(
            simpleItems("a", "b"),
            0,
            TabBar.State.initial()
        ).layout(new Rect(Integer.MAX_VALUE - 8, Integer.MAX_VALUE - 1, 8, 1));
        Canvas canvas = new Canvas(filled(2, 4));
        extreme.paint(canvas, THEME, WidgetState.FOCUSED);
        assertTrue(canvas.put(0, 0, "z", ACCENT));
    }

    @Test
    void nonPointerInputAndNonPressPointerKindsBubble() {
        TabBar.Placement placement = new TabBar(
            simpleItems("a", "b"),
            0,
            TabBar.State.initial()
        ).layout(new Rect(0, 0, 8, 1));
        FocusContext focused = new FocusContext(true, true, FocusSpace.root(ROOT_CLIP));

        assertIgnored(placement.handle(KeyStroke.of(KeyStroke.Key.ENTER), focused));
        assertIgnored(placement.handle(KeyStroke.of(KeyStroke.Key.TAB), focused));
        assertIgnored(placement.handle(new TextInput("x"), focused));
        assertIgnored(placement.handle(new PasteEvent("text"), focused));
        assertIgnored(placement.handle(pointer(0, 2, PointerEvent.Kind.RELEASE, PointerEvent.Button.LEFT), focused));
        assertIgnored(placement.handle(pointer(0, 2, PointerEvent.Kind.PRESS, PointerEvent.Button.RIGHT), focused));
        assertIgnored(placement.handle(pointer(0, 2, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT), focused));
        assertIgnored(placement.handle(
            new PointerEvent(
                0,
                2,
                PointerEvent.Kind.PRESS,
                PointerEvent.Button.LEFT,
                Set.of(KeyStroke.Modifier.SHIFT)
            ),
            focused
        ));
        assertIgnored(placement.handle(
            new PointerEvent(
                0,
                2,
                PointerEvent.Kind.SCROLL_DOWN,
                PointerEvent.Button.NONE,
                Set.of(KeyStroke.Modifier.CTRL)
            ),
            focused
        ));
    }

    @Test
    void staleStateIsNormalizedAndOldPlacementsKeepTheirExactFrame() {
        List<TabBar.Item> items = simpleItems("a", "b", "c");
        TabBar.Placement old = new TabBar(
            items,
            0,
            TabBar.State.initial()
        ).layout(new Rect(0, 0, 8, 1));
        TabBar.Placement fresh = new TabBar(
            items,
            1,
            new TabBar.State(99)
        ).layout(new Rect(0, 0, 8, 1));

        assertEquals(new TabBar.State(2), fresh.state());
        assertEquals(
            new TabBar.TabPressed(0, TabBar.State.initial()),
            output(old.handle(press(0, 2), POINTER_CONTEXT), TabBar.TabPressed.class)
        );
        assertEquals(
            new TabBar.TabPressed(2, new TabBar.State(2)),
            output(fresh.handle(press(0, 2), POINTER_CONTEXT), TabBar.TabPressed.class)
        );
    }

    private static List<TabBar.Item> simpleItems(String... labels) {
        List<TabBar.Item> items = new ArrayList<>(labels.length);
        for (String label : labels) {
            items.add(new TabBar.Item(label, true));
        }
        return List.copyOf(items);
    }

    private static PointerEvent press(int row, int column) {
        return pointer(row, column, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT);
    }

    private static PointerEvent scroll(int row, int column, PointerEvent.Kind kind) {
        return pointer(row, column, kind, PointerEvent.Button.NONE);
    }

    private static PointerEvent pointer(
        int row,
        int column,
        PointerEvent.Kind kind,
        PointerEvent.Button button
    ) {
        return new PointerEvent(row, column, kind, button, Set.of());
    }

    private static <A extends TabBar.Action> A output(
        InteractionResult<TabBar.Action> result,
        Class<A> type
    ) {
        assertTrue(result.consumed());
        assertEquals(FocusRequest.NONE, result.focusRequest());
        return assertInstanceOf(type, result.output().orElseThrow());
    }

    private static void assertStateChange(
        int expectedFirstVisible,
        InteractionResult<TabBar.Action> result
    ) {
        TabBar.StateChanged changed = output(result, TabBar.StateChanged.class);
        assertEquals(new TabBar.State(expectedFirstVisible), changed.state());
    }

    private static void assertIgnored(InteractionResult<TabBar.Action> result) {
        assertFalse(result.consumed());
        assertEquals(FocusRequest.NONE, result.focusRequest());
        assertTrue(result.output().isEmpty());
    }

    private static CellBuffer filled(int rows, int columns) {
        CellBuffer buffer = new CellBuffer(rows, columns);
        buffer.fill(new Cell(".", CellStyle.DEFAULT));
        return buffer;
    }

    private static String cellText(CellBuffer buffer, int row, int startColumn, int width) {
        StringBuilder out = new StringBuilder(width);
        for (int column = startColumn; column < startColumn + width; column++) {
            Cell cell = buffer.get(row, column);
            if (!cell.continuation()) {
                out.append(cell.grapheme());
            }
        }
        return out.toString();
    }
}
