package dev.tuink.terminal.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.cell.Cell;
import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.geometry.Point;
import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.geometry.Size;
import dev.tuink.terminal.input.KeyStroke;
import dev.tuink.terminal.input.PointerEvent;
import dev.tuink.terminal.input.TextInput;
import dev.tuink.terminal.style.CellColor;
import dev.tuink.terminal.style.CellStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ListViewTest {
    private static final Rect ROOT_CLIP = new Rect(0, 0, 80, 24);
    private static final FocusContext FOCUSED = new FocusContext(
        true,
        true,
        FocusSpace.root(ROOT_CLIP)
    );
    private static final FocusContext UNFOCUSED = new FocusContext(
        false,
        true,
        FocusSpace.root(ROOT_CLIP)
    );
    private static final FocusContext DISABLED = new FocusContext(
        false,
        false,
        FocusSpace.root(ROOT_CLIP)
    );

    private static final CellStyle NORMAL = CellStyle.fg(CellColor.WHITE);
    private static final CellStyle SELECTED = CellStyle.fg(CellColor.CYAN);
    private static final CellStyle FOCUSED_SELECTED = CellStyle.fg(CellColor.BRIGHT_GREEN);
    private static final CellStyle DISABLED_STYLE = CellStyle.fg(CellColor.BRIGHT_BLACK);
    private static final CellStyle TRACK = CellStyle.fg(CellColor.BLUE);
    private static final CellStyle THUMB = CellStyle.fg(CellColor.MAGENTA);
    private static final Theme THEME = role -> {
        if (WidgetThemeKeys.LIST_ITEM_SELECTED_FOCUSED.equals(role)) {
            return FOCUSED_SELECTED;
        }
        if (WidgetThemeKeys.LIST_ITEM_SELECTED.equals(role)) {
            return SELECTED;
        }
        if (WidgetThemeKeys.LIST_ITEM_DISABLED.equals(role)) {
            return DISABLED_STYLE;
        }
        if (WidgetThemeKeys.SCROLLBAR_TRACK.equals(role)) {
            return TRACK;
        }
        if (WidgetThemeKeys.SCROLLBAR_THUMB.equals(role)) {
            return THUMB;
        }
        return NORMAL;
    };

    @Test
    void measureFillsAvailableSpaceAndLayoutSnapshotsOnlyVisibleProviderRows() {
        Map<Integer, String> source = new HashMap<>();
        source.put(40, "old-40");
        source.put(41, "old-41");
        source.put(42, "old-42");
        List<Integer> requested = new ArrayList<>();
        ListView view = new ListView(
            100,
            index -> {
                requested.add(index);
                return source.getOrDefault(index, "unexpected-" + index);
            },
            new ListView.State(41, 40)
        );

        assertEquals(new Size(9, 3), view.measure(new Size(9, 3)));
        assertEquals(Size.ZERO, view.measure(Size.ZERO));
        assertTrue(requested.isEmpty(), "measure must not enumerate a virtual data source");

        ListView.Placement placement = view.layout(new Rect(1, 1, 9, 3));

        assertEquals(List.of(40, 41, 42), requested);
        source.put(40, "changed-after-layout");
        source.put(41, "changed-after-layout");
        CellBuffer first = filled(5, 12);
        placement.paint(new Canvas(first), THEME, WidgetState.FOCUSED);
        CellBuffer second = filled(5, 12);
        placement.paint(new Canvas(second), THEME, WidgetState.FOCUSED);

        assertTrue(cellText(first, 1, 1, 8).startsWith("old-40"));
        assertTrue(cellText(first, 2, 1, 8).startsWith("old-41"));
        assertEquals(List.of(40, 41, 42), requested, "paint must use the immutable layout snapshot");
        assertBuffersEqual(first, second);
    }

    @Test
    void translatedPaintIsUnicodeSafeAndUsesSelectionAndScrollbarRoles() {
        List<String> items = List.of("zero", "one", "A中B", "e\u0301-three", "four", "five");
        ListView.Placement placement = new ListView(
            items.size(),
            items::get,
            new ListView.State(3, 2)
        ).layout(new Rect(1, 1, 6, 3));
        CellBuffer focused = filled(6, 12);
        Canvas focusedCanvas = new Canvas(focused);
        focusedCanvas.translate(2, 1);

        placement.paint(focusedCanvas, THEME, WidgetState.FOCUSED);

        assertEquals("A", focused.get(2, 3).grapheme());
        assertEquals("中", focused.get(2, 4).grapheme());
        assertTrue(focused.get(2, 5).continuation());
        assertEquals("B", focused.get(2, 6).grapheme());
        assertEquals(NORMAL, focused.get(2, 3).style());
        assertEquals("e\u0301", focused.get(3, 3).grapheme());
        assertEquals(FOCUSED_SELECTED, focused.get(3, 3).style());

        int scrollbarColumn = 8;
        assertEquals(TRACK, focused.get(2, scrollbarColumn).style());
        assertEquals(THUMB, focused.get(3, scrollbarColumn).style());
        assertEquals(THUMB, focused.get(4, scrollbarColumn).style());
        assertTrue(focusedCanvas.put(0, 0, "z", NORMAL), "paint must restore Canvas translation state");
        assertEquals("z", focused.get(1, 2).grapheme());

        CellBuffer normal = filled(6, 12);
        Canvas normalCanvas = new Canvas(normal);
        normalCanvas.translate(2, 1);
        placement.paint(normalCanvas, THEME, WidgetState.NORMAL);
        assertEquals(SELECTED, normal.get(3, 3).style());

        CellBuffer disabled = filled(6, 12);
        Canvas disabledCanvas = new Canvas(disabled);
        disabledCanvas.translate(2, 1);
        placement.paint(disabledCanvas, THEME, WidgetState.DISABLED);
        assertEquals(DISABLED_STYLE, disabled.get(2, 3).style());
        assertEquals(DISABLED_STYLE, disabled.get(3, 3).style());

        Theme partialTheme = role -> WidgetThemeKeys.LIST_ITEM_SELECTED.equals(role)
            ? SELECTED
            : WidgetThemeKeys.LIST_ITEM.equals(role) ? NORMAL : null;
        CellBuffer fallback = filled(6, 12);
        Canvas fallbackCanvas = new Canvas(fallback);
        fallbackCanvas.translate(2, 1);
        placement.paint(fallbackCanvas, partialTheme, WidgetState.FOCUSED);
        assertEquals(
            SELECTED,
            fallback.get(3, 3).style(),
            "focused-selected must fall back through selected before normal"
        );
    }

    @Test
    void paintHonorsNarrowParentClipWithoutBreakingCrossingWideCellsAndRestoresIt() {
        ListView.Placement placement = new ListView(
            1,
            ignored -> "replacement",
            new ListView.State(0, 0)
        ).layout(new Rect(1, 1, 5, 1));
        CellBuffer buffer = filled(4, 10);
        buffer.set(2, 3, "中", SELECTED);
        Canvas canvas = new Canvas(buffer);
        canvas.translate(2, 1);
        assertTrue(canvas.clip(new Rect(2, 1, 2, 1)));

        placement.paint(canvas, THEME, WidgetState.FOCUSED);

        assertEquals("中", buffer.get(2, 3).grapheme());
        assertTrue(buffer.get(2, 4).continuation());
        assertEquals(SELECTED, buffer.get(2, 3).style());
        assertFalse(canvas.put(1, 1, "x", NORMAL), "the caller's narrow clip must remain active");
        assertTrue(canvas.put(3, 1, "z", NORMAL));
        assertEquals("z", buffer.get(2, 5).grapheme());
    }

    @Test
    void emptyAndTinyPlacementsDoNotEnumerateHiddenRowsOrPaintHalfAWideGrapheme() {
        AtomicInteger emptyReads = new AtomicInteger();
        ListView empty = new ListView(
            0,
            ignored -> {
                emptyReads.incrementAndGet();
                return "unreachable";
            },
            new ListView.State(-1, 0)
        );
        CellBuffer emptyBuffer = filled(2, 4);

        empty.layout(new Rect(2, 1, 0, 0)).paint(
            new Canvas(emptyBuffer),
            THEME,
            WidgetState.NORMAL
        );

        assertEquals(0, emptyReads.get());
        assertEquals(".", emptyBuffer.get(1, 2).grapheme());

        AtomicInteger tinyReads = new AtomicInteger();
        ListView tiny = new ListView(
            3,
            index -> {
                tinyReads.incrementAndGet();
                return index == 0 ? "中" : "row-" + index;
            },
            new ListView.State(0, 0)
        );
        CellBuffer tinyBuffer = filled(1, 1);

        tiny.layout(new Rect(0, 0, 1, 1)).paint(
            new Canvas(tinyBuffer),
            THEME,
            WidgetState.FOCUSED
        );

        assertEquals(1, tinyReads.get());
        assertEquals(" ", tinyBuffer.get(0, 0).grapheme());
        assertEquals(FOCUSED_SELECTED, tinyBuffer.get(0, 0).style());
        assertFalse(tinyBuffer.get(0, 0).continuation());

        ListView.Placement oneRow = new ListView(
            3,
            index -> "row-" + index,
            new ListView.State(0, 0)
        ).layout(new Rect(0, 0, 2, 1));
        assertEquals(new Rect(0, 0, 2, 1), oneRow.contentBounds());
        assertTrue(oneRow.scrollbarBounds().isEmpty(), "a one-cell track cannot scroll without drag");

        ListView.Placement invisible = new ListView(
            1,
            ignored -> "hidden",
            new ListView.State(0, 0)
        ).layout(new Rect(0, 0, 0, 2));
        assertIgnored(invisible.handle(KeyStroke.of(KeyStroke.Key.ENTER), FOCUSED));
    }

    @Test
    void placementNormalizesStaleSelectionAndViewportBeforeSnapshotAndRouting() {
        List<String> items = rows(5);
        List<Integer> requested = new ArrayList<>();
        ListView.Placement placement = new ListView(
            items.size(),
            index -> {
                requested.add(index);
                return items.get(index);
            },
            new ListView.State(99, 99)
        ).layout(new Rect(1, 1, 6, 2));

        assertEquals(List.of(3, 4), requested);
        assertEquals(new ListView.State(4, 3), placement.state());
        ListView.Activated activated = assertInstanceOf(
            ListView.Activated.class,
            placement.handle(KeyStroke.of(KeyStroke.Key.ENTER), FOCUSED).output().orElseThrow()
        );
        assertEquals(4, activated.itemIndex());
        assertState(
            new ListView.State(0, 0),
            placement.handle(KeyStroke.of(KeyStroke.Key.HOME), FOCUSED)
        );

        ListView.Placement empty = new ListView(
            0,
            ignored -> {
                throw new AssertionError("empty layout must not read its provider");
            },
            new ListView.State(42, 42)
        ).layout(new Rect(1, 1, 6, 2));
        InteractionResult<ListView.Action> emptyEnter = empty.handle(
            KeyStroke.of(KeyStroke.Key.ENTER),
            FOCUSED
        );
        assertTrue(emptyEnter.consumed());
        assertTrue(emptyEnter.output().isEmpty());
    }

    @Test
    void exactFocusedKeyboardProducesTypedNavigationAndActivation() {
        Rect bounds = new Rect(2, 1, 8, 3);

        assertState(
            new ListView.State(2, 2),
            handle(8, new ListView.State(3, 2), bounds, KeyStroke.of(KeyStroke.Key.ARROW_UP), FOCUSED)
        );
        assertState(
            new ListView.State(5, 3),
            handle(8, new ListView.State(4, 2), bounds, KeyStroke.of(KeyStroke.Key.ARROW_DOWN), FOCUSED)
        );
        assertState(
            new ListView.State(0, 0),
            handle(8, new ListView.State(3, 2), bounds, KeyStroke.of(KeyStroke.Key.HOME), FOCUSED)
        );
        assertState(
            new ListView.State(7, 5),
            handle(8, new ListView.State(3, 2), bounds, KeyStroke.of(KeyStroke.Key.END), FOCUSED)
        );

        assertState(
            new ListView.State(1, 1),
            handle(
                8,
                new ListView.State(4, 2),
                bounds,
                KeyStroke.of(KeyStroke.Key.PAGE_UP),
                FOCUSED
            )
        );
        assertState(
            new ListView.State(4, 2),
            handle(
                8,
                new ListView.State(1, 0),
                bounds,
                KeyStroke.of(KeyStroke.Key.PAGE_DOWN),
                FOCUSED
            )
        );

        InteractionResult<ListView.Action> activated = handle(
            8,
            new ListView.State(3, 2),
            bounds,
            KeyStroke.of(KeyStroke.Key.ENTER),
            FOCUSED
        );
        ListView.Activated action = assertInstanceOf(
            ListView.Activated.class,
            activated.output().orElseThrow()
        );
        assertEquals(3, action.itemIndex());
        assertTrue(activated.consumed());
    }

    @Test
    void unfocusedDisabledAndModifiedKeyboardEventsBubbleWithoutActions() {
        Rect bounds = new Rect(2, 1, 8, 3);
        ListView.State state = new ListView.State(3, 2);

        assertIgnored(handle(8, state, bounds, KeyStroke.of(KeyStroke.Key.ARROW_DOWN), UNFOCUSED));
        assertIgnored(handle(8, state, bounds, KeyStroke.of(KeyStroke.Key.ARROW_DOWN), DISABLED));
        assertIgnored(handle(
            8,
            state,
            bounds,
            KeyStroke.of(KeyStroke.Key.ARROW_DOWN, KeyStroke.Modifier.SHIFT),
            FOCUSED
        ));
        assertIgnored(handle(
            8,
            state,
            bounds,
            KeyStroke.of(KeyStroke.Key.HOME, KeyStroke.Modifier.CTRL),
            FOCUSED
        ));
        assertIgnored(handle(8, state, bounds, KeyStroke.of(KeyStroke.Key.TAB), FOCUSED));
        assertIgnored(handle(8, state, bounds, new TextInput("x"), FOCUSED));
    }

    @Test
    void leftRowPressMapsThroughFocusSpaceAndReturnsSelectionWithSelfFocus() {
        List<String> items = rows(8);
        ListView.Placement placement = new ListView(
            items.size(),
            items::get,
            new ListView.State(2, 2)
        ).layout(new Rect(2, 1, 6, 3));
        FocusContext translated = new FocusContext(
            false,
            true,
            new FocusSpace(new Point(10, 5), new Rect(0, 0, 20, 10))
        );

        InteractionResult<ListView.Action> pressed = placement.handle(
            pointer(7, 13, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT),
            translated
        );

        ListView.ItemPressed action = assertInstanceOf(
            ListView.ItemPressed.class,
            pressed.output().orElseThrow()
        );
        assertEquals(3, action.itemIndex());
        assertEquals(new ListView.State(3, 2), action.state());
        assertEquals(FocusRequest.SELF, pressed.focusRequest());
        assertTrue(pressed.consumed());

        assertIgnored(placement.handle(
            pointer(7, 13, PointerEvent.Kind.RELEASE, PointerEvent.Button.LEFT),
            translated
        ));
        assertIgnored(placement.handle(
            pointer(7, 13, PointerEvent.Kind.PRESS, PointerEvent.Button.RIGHT),
            translated
        ));
        assertIgnored(placement.handle(new PointerEvent(
            7,
            13,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT,
            Set.of(KeyStroke.Modifier.SHIFT)
        ), translated));
        assertIgnored(placement.handle(
            pointer(7, 18, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT),
            translated
        ));
        assertIgnored(placement.handle(
            pointer(7, 13, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT),
            new FocusContext(false, false, translated.space())
        ));
    }

    @Test
    void wheelAndVerticalScrollbarPressesProduceViewportStateWhileMissesBubble() {
        List<String> items = rows(10);
        Rect bounds = new Rect(2, 1, 6, 4);
        ListView.Placement placement = new ListView(
            items.size(),
            items::get,
            new ListView.State(2, 2)
        ).layout(bounds);

        assertState(
            new ListView.State(2, 3),
            placement.handle(
                pointer(2, 3, PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE),
                FOCUSED
            )
        );
        assertState(
            new ListView.State(2, 1),
            placement.handle(
                pointer(2, 3, PointerEvent.Kind.SCROLL_UP, PointerEvent.Button.NONE),
                FOCUSED
            )
        );

        int scrollbarColumn = bounds.rightExclusive() - 1;
        assertState(
            new ListView.State(2, 0),
            placement.handle(
                pointer(bounds.y(), scrollbarColumn, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT),
                FOCUSED
            )
        );
        assertState(
            new ListView.State(2, 5),
            placement.handle(
                pointer(
                    bounds.bottomExclusive() - 1,
                    scrollbarColumn,
                    PointerEvent.Kind.PRESS,
                    PointerEvent.Button.LEFT
                ),
                FOCUSED
            )
        );
        InteractionResult<ListView.Action> thumbPressed = placement.handle(
            pointer(bounds.y() + 2, scrollbarColumn, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT),
            FOCUSED
        );
        assertTrue(thumbPressed.consumed());
        assertTrue(thumbPressed.output().isEmpty(), "thumb press must not jump without drag capture");
        assertEquals(FocusRequest.SELF, thumbPressed.focusRequest());

        assertIgnored(placement.handle(new PointerEvent(
            2,
            3,
            PointerEvent.Kind.SCROLL_DOWN,
            PointerEvent.Button.NONE,
            Set.of(KeyStroke.Modifier.CTRL)
        ), FOCUSED));

        assertIgnored(placement.handle(
            pointer(2, bounds.rightExclusive(), PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE),
            FOCUSED
        ));
        assertIgnored(placement.handle(
            pointer(2, 3, PointerEvent.Kind.SCROLL_RIGHT, PointerEvent.Button.NONE),
            FOCUSED
        ));
        assertIgnored(placement.handle(
            pointer(2, 3, PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE),
            DISABLED
        ));
    }

    @Test
    void validatesConstructionStateAndVisibleProviderResults() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ListView(-1, ignored -> "row", ListView.State.initial())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ListView.State(-2, 0)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ListView.State(0, -1)
        );
        ListView nullRow = new ListView(1, ignored -> null, ListView.State.initial());
        assertThrows(
            NullPointerException.class,
            () -> nullRow.layout(new Rect(0, 0, 2, 1))
        );
    }

    @Test
    void boundedRowPaintIsSafeAtTheLargestRepresentableLocalCoordinate() {
        int x = Integer.MAX_VALUE - 1;
        ListView.Placement placement = new ListView(
            Integer.MAX_VALUE,
            ignored -> "ab" + "tail".repeat(10_000),
            new ListView.State(Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1)
        ).layout(new Rect(x, 0, 1, 1));
        CellBuffer buffer = filled(1, 1);
        Canvas canvas = new Canvas(buffer);
        canvas.translate(-x, 0);

        placement.paint(canvas, THEME, WidgetState.FOCUSED);

        assertEquals("a", buffer.get(0, 0).grapheme());
        assertEquals(FOCUSED_SELECTED, buffer.get(0, 0).style());
    }

    private static InteractionResult<ListView.Action> handle(
        int itemCount,
        ListView.State state,
        Rect bounds,
        dev.tuink.terminal.input.TuiInputEvent event,
        FocusContext context
    ) {
        List<String> items = rows(itemCount);
        return new ListView(itemCount, items::get, state).layout(bounds).handle(event, context);
    }

    private static void assertState(
        ListView.State expected,
        InteractionResult<ListView.Action> result
    ) {
        assertEquals(expected, stateFrom(result));
        assertTrue(result.consumed());
    }

    private static ListView.State stateFrom(InteractionResult<ListView.Action> result) {
        ListView.StateChanged changed = assertInstanceOf(
            ListView.StateChanged.class,
            result.output().orElseThrow()
        );
        return changed.state();
    }

    private static void assertIgnored(InteractionResult<ListView.Action> result) {
        assertFalse(result.consumed());
        assertTrue(result.output().isEmpty());
        assertEquals(FocusRequest.NONE, result.focusRequest());
    }

    private static List<String> rows(int count) {
        List<String> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add("row-" + index);
        }
        return List.copyOf(result);
    }

    private static PointerEvent pointer(
        int row,
        int column,
        PointerEvent.Kind kind,
        PointerEvent.Button button
    ) {
        return new PointerEvent(row, column, kind, button, Set.of());
    }

    private static CellBuffer filled(int rows, int columns) {
        CellBuffer buffer = new CellBuffer(rows, columns);
        buffer.fill(new Cell(".", CellStyle.DEFAULT));
        return buffer;
    }

    private static String cellText(CellBuffer buffer, int row, int left, int width) {
        StringBuilder result = new StringBuilder();
        for (int column = left; column < left + width; column++) {
            Cell cell = buffer.get(row, column);
            if (!cell.continuation()) {
                result.append(cell.grapheme());
            }
        }
        return result.toString();
    }

    private static void assertBuffersEqual(CellBuffer expected, CellBuffer actual) {
        assertEquals(expected.rows(), actual.rows());
        assertEquals(expected.cols(), actual.cols());
        for (int row = 0; row < expected.rows(); row++) {
            for (int column = 0; column < expected.cols(); column++) {
                assertEquals(expected.get(row, column), actual.get(row, column), column + "," + row);
            }
            assertEquals(expected.rowSignature1(row), actual.rowSignature1(row));
            assertEquals(expected.rowSignature2(row), actual.rowSignature2(row));
        }
    }
}
