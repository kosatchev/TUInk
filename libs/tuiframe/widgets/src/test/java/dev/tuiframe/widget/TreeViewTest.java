package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.geometry.Size;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.scroll.Scrollbar;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class TreeViewTest {
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
    private static final CellStyle PARENT_BODY = new CellStyle(CellColor.WHITE, CellColor.BLACK, false, false);
    private static final CellStyle PARENT_BORDER = new CellStyle(CellColor.BLUE, CellColor.RED, true, false);
    private static final CellStyle SCROLL_STYLE = new CellStyle(CellColor.BLUE, CellColor.BLACK, true, false);
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
        if (WidgetThemeKeys.MODAL_BODY.equals(role)) {
            return PARENT_BODY;
        }
        if (WidgetThemeKeys.MODAL_BORDER.equals(role)) {
            return PARENT_BORDER;
        }
        return NORMAL;
    };

    @Test
    void snapshotsDescriptorsAndStateOnlyLayoutReusesPreparedProjection() {
        List<TreeView.Item<String>> backing = new ArrayList<>(List.of(
            item("old-root", "root", 0, TreeView.Kind.COLLAPSED),
            item("old-child", "child", 1, TreeView.Kind.LEAF),
            item("old-last", "last", 0, TreeView.Kind.LEAF)
        ));
        AtomicInteger reads = new AtomicInteger();
        List<TreeView.Item<String>> observed = new AbstractList<>() {
            @Override
            public TreeView.Item<String> get(int index) {
                reads.incrementAndGet();
                return backing.get(index);
            }

            @Override
            public int size() {
                return backing.size();
            }
        };
        TreeView<String> tree = new TreeView<>(observed, TreeView.State.initial());
        int readsAfterConstruction = reads.get();
        backing.set(0, item("new-root", "changed", 0, TreeView.Kind.EXPANDED));

        assertEquals(new Size(9, 3), tree.measure(new Size(9, 3)));
        TreeView.Placement<String> initial = tree.layout(new Rect(0, 0, 9, 3));
        TreeView.Placement<String> selected = tree.layout(
            new Rect(0, 0, 9, 3),
            new TreeView.State(1, 0, 0)
        );

        assertEquals(readsAfterConstruction, reads.get(), "state-only layout must not reread descriptors");
        assertEquals(new TreeView.State(0, 0, 0), initial.state());
        assertEquals(new TreeView.State(1, 0, 0), selected.state());
        TreeView.Activated<String> activated = assertInstanceOf(
            TreeView.Activated.class,
            initial.handle(KeyStroke.of(KeyStroke.Key.ENTER), FOCUSED).output().orElseThrow()
        );
        assertEquals("old-root", activated.value());

        CellBuffer buffer = filled(3, 9);
        initial.paint(new Canvas(buffer), THEME, WidgetState.FOCUSED);
        assertEquals("▶ root", cellText(buffer, 0, 0, 6));
    }

    @Test
    void pinnedTrailingTextStaysFixedWhileOnlyHierarchyLabelScrolls() {
        List<TreeView.Item<String>> items = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            items.add(new TreeView.Item<>(
                "v" + index,
                index == 0 ? "abcdefghij" : "row-" + index,
                0,
                index == 0 ? TreeView.Kind.COLLAPSED : TreeView.Kind.LEAF,
                "|META"
            ));
        }
        TreeView<String> tree = new TreeView<>(items, TreeView.State.initial());
        Rect bounds = new Rect(0, 0, 10, 4);
        TreeView.Placement<String> left = tree.layout(bounds, new TreeView.State(0, 0, 0));
        TreeView.Placement<String> scrolled = tree.layout(bounds, new TreeView.State(0, 0, 3));

        assertEquals(new Rect(0, 0, 9, 3), scrolled.contentBounds());
        assertEquals(new Rect(0, 0, 4, 3), scrolled.leadingBounds());
        assertEquals(new Rect(4, 0, 5, 3), scrolled.trailingBounds().orElseThrow());
        assertEquals(new Rect(9, 0, 1, 3), scrolled.verticalScrollbarBounds().orElseThrow());
        assertEquals(new Rect(0, 3, 4, 1), scrolled.horizontalScrollbarBounds().orElseThrow());
        assertEquals(12, scrolled.horizontalExtent());

        CellBuffer leftBuffer = filled(4, 10);
        left.paint(new Canvas(leftBuffer), THEME, WidgetState.FOCUSED);
        CellBuffer scrolledBuffer = filled(4, 10);
        scrolled.paint(new Canvas(scrolledBuffer), THEME, WidgetState.FOCUSED);

        assertEquals("▶ ab|META", cellText(leftBuffer, 0, 0, 9));
        assertEquals("bcde|META", cellText(scrolledBuffer, 0, 0, 9));
        assertEquals("|META", cellText(leftBuffer, 0, 4, 5));
        assertEquals("|META", cellText(scrolledBuffer, 0, 4, 5));
        assertEquals(FOCUSED_SELECTED, scrolledBuffer.get(0, 8).style());
        assertEquals(SCROLL_STYLE, scrolledBuffer.get(2, 9).style());
        assertEquals(SCROLL_STYLE, scrolledBuffer.get(3, 1).style());
    }

    @Test
    void exactKeyboardNavigationProducesTypedStateActivationAndExpansion() {
        List<TreeView.Item<String>> items = List.of(
            item("root", "long-root-label", 0, TreeView.Kind.COLLAPSED),
            item("child", "child", 1, TreeView.Kind.LEAF),
            item("dir", "dir", 1, TreeView.Kind.EXPANDED),
            item("grandchild", "grandchild", 2, TreeView.Kind.LEAF),
            item("sibling", "sibling", 0, TreeView.Kind.LEAF)
        );
        Rect bounds = new Rect(0, 0, 7, 3);

        TreeView.ExpansionRequested<String> expand = expansionFrom(
            placement(items, new TreeView.State(0, 0, 0), bounds)
                .handle(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT), FOCUSED)
        );
        assertEquals("root", expand.value());
        assertEquals(0, expand.itemIndex());
        assertTrue(expand.expand());

        TreeView.ExpansionRequested<String> collapse = expansionFrom(
            placement(items, new TreeView.State(2, 1, 0), bounds)
                .handle(KeyStroke.of(KeyStroke.Key.ARROW_LEFT), FOCUSED)
        );
        assertEquals("dir", collapse.value());
        assertFalse(collapse.expand());

        assertState(
            new TreeView.State(2, 2, 0),
            placement(items, new TreeView.State(3, 2, 0), bounds)
                .handle(KeyStroke.of(KeyStroke.Key.ARROW_LEFT), FOCUSED)
        );
        assertState(
            new TreeView.State(3, 2, 0),
            placement(items, new TreeView.State(2, 1, 0), bounds)
                .handle(KeyStroke.of(KeyStroke.Key.ARROW_DOWN), FOCUSED)
        );

        TreeView.Activated<String> activated = assertInstanceOf(
            TreeView.Activated.class,
            placement(items, new TreeView.State(1, 0, 0), bounds)
                .handle(KeyStroke.of(KeyStroke.Key.ENTER), FOCUSED)
                .output()
                .orElseThrow()
        );
        assertEquals("child", activated.value());
        assertEquals(1, activated.itemIndex());

        assertState(
            new TreeView.State(0, 0, 2),
            placement(items, new TreeView.State(0, 0, 0), bounds)
                .handle(
                    KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT),
                    FOCUSED
                )
        );
        assertIgnored(placement(items, new TreeView.State(0, 0, 0), bounds).handle(
            KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.CTRL),
            FOCUSED
        ));
        assertIgnored(placement(items, new TreeView.State(0, 0, 0), bounds).handle(
            KeyStroke.of(KeyStroke.Key.ARROW_DOWN),
            UNFOCUSED
        ));
    }

    @Test
    void translatedPointerPressesDistinguishDisclosureAndRowBody() {
        List<TreeView.Item<String>> items = rows(10);
        TreeView.Placement<String> placement = placement(
            items,
            new TreeView.State(0, 0, 0),
            new Rect(2, 1, 8, 3)
        );
        FocusContext translated = new FocusContext(
            false,
            true,
            new FocusSpace(new Point(10, 5), new Rect(0, 0, 20, 10))
        );

        InteractionResult<TreeView.Action<String>> disclosureResult = placement.handle(
            press(6, 12),
            translated
        );
        TreeView.ExpansionRequested<String> disclosure = expansionFrom(disclosureResult);
        assertEquals("row-0", disclosure.value());
        assertTrue(disclosure.expand());
        assertEquals(new TreeView.State(0, 0, 0), disclosure.state());
        assertEquals(FocusRequest.SELF, disclosureResult.focusRequest());

        InteractionResult<TreeView.Action<String>> bodyResult = placement.handle(
            press(7, 15),
            translated
        );
        TreeView.ItemPressed<String> body = assertInstanceOf(
            TreeView.ItemPressed.class,
            bodyResult.output().orElseThrow()
        );
        assertEquals("row-1", body.value());
        assertEquals(1, body.itemIndex());
        assertEquals(new TreeView.State(1, 0, 0), body.state());
        assertEquals(FocusRequest.SELF, bodyResult.focusRequest());

        assertIgnored(placement.handle(new PointerEvent(
            7,
            15,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT,
            Set.of(KeyStroke.Modifier.SHIFT)
        ), translated));
        assertIgnored(placement.handle(press(7, 20), translated));
        assertIgnored(placement.handle(press(7, 15), DISABLED));
    }

    @Test
    void wheelAndTrackClicksUpdateBothApplicationOwnedViewports() {
        TreeView.Placement<String> vertical = placement(
            rows(10),
            new TreeView.State(0, 0, 0),
            new Rect(2, 1, 8, 3)
        );
        assertState(
            new TreeView.State(0, 1, 0),
            vertical.handle(
                pointer(2, 3, PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE),
                FOCUSED
            )
        );
        assertState(
            new TreeView.State(0, 2, 0),
            vertical.handle(press(3, 9), FOCUSED)
        );
        assertIgnored(vertical.handle(
            pointer(3, 9, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT),
            FOCUSED
        ));

        List<TreeView.Item<String>> longRow = List.of(
            item("long", "abcdefghijklmnop", 0, TreeView.Kind.LEAF)
        );
        TreeView.Placement<String> horizontal = placement(
            longRow,
            new TreeView.State(0, 0, 0),
            new Rect(2, 1, 8, 3)
        );
        assertEquals(new Rect(2, 3, 8, 1), horizontal.horizontalScrollbarBounds().orElseThrow());
        assertState(
            new TreeView.State(0, 0, 7),
            horizontal.handle(press(3, 9), FOCUSED)
        );
        assertIgnored(horizontal.handle(
            pointer(3, 9, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT),
            FOCUSED
        ));
        assertState(
            new TreeView.State(0, 0, 2),
            horizontal.handle(
                pointer(1, 3, PointerEvent.Kind.SCROLL_RIGHT, PointerEvent.Button.NONE),
                FOCUSED
            )
        );
    }

    @Test
    void capturedThumbDragPreservesGrabPointAndWorksOutsideParentClipUntilRelease() {
        List<TreeView.Item<String>> items = rows(100);
        Rect bounds = new Rect(2, 2, 10, 10);
        TreeView.Placement<String> initial = placement(items, new TreeView.State(4, 37, 0), bounds);
        Rect track = initial.verticalScrollbarBounds().orElseThrow();
        Point thumb = new Point(track.x(), track.y() + 4);
        TreeView.State captured = stateFrom(initial.handle(press(thumb.y(), thumb.x()), FOCUSED));
        assertEquals(new Scrollbar.Drag(Scrollbar.Axis.VERTICAL, 0), captured.drag());
        assertEquals(37, captured.firstVisibleIndex());

        TreeView.Placement<String> dragging = placement(items, captured, bounds);
        FocusContext narrowParent = new FocusContext(false, true, FocusSpace.root(bounds));
        TreeView.State stationary = stateFrom(dragging.handle(
            pointer(thumb.y(), thumb.x(), PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT), narrowParent
        ));
        assertEquals(captured, stationary, "a stationary pointer must preserve the unrounded scroll offset");
        TreeView.State bottom = stateFrom(dragging.handle(
            pointer(40, 50, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT), narrowParent
        ));
        assertEquals(90, bottom.firstVisibleIndex());
        assertEquals(captured.drag(), bottom.drag());
        TreeView.State released = stateFrom(placement(items, bottom, bounds).handle(
            pointer(40, 50, PointerEvent.Kind.RELEASE, PointerEvent.Button.LEFT), narrowParent
        ));
        assertEquals(new TreeView.State(4, 90, 0), released);
        assertIgnored(placement(items, released, bounds).handle(
            pointer(0, 0, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT), narrowParent
        ));
    }

    @Test
    void horizontalCaptureSurvivesPlacementsAndResetsWhenItsTrackDisappears() {
        List<TreeView.Item<String>> items = List.of(item("long", "x".repeat(100), 0, TreeView.Kind.PLAIN));
        Rect bounds = new Rect(2, 2, 10, 4);
        TreeView.Placement<String> initial = placement(items, new TreeView.State(0, 0, 37), bounds);
        Rect track = initial.horizontalScrollbarBounds().orElseThrow();
        TreeView.State captured = stateFrom(initial.handle(press(track.y(), track.x() + 4), FOCUSED));
        assertEquals(new Scrollbar.Drag(Scrollbar.Axis.HORIZONTAL, 0), captured.drag());
        TreeView.State right = stateFrom(placement(items, captured, bounds).handle(
            pointer(0, 50, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT), FOCUSED
        ));
        assertEquals(90, right.horizontalOffset());
        assertEquals(captured.drag(), right.drag());
        assertEquals(new TreeView.State(0, 0, 0), placement(items, captured, new Rect(0, 0, 100, 4)).state());
    }

    @Test
    void releaseWhileDisabledAndResizeCancelCaptureBeforeFurtherDrags() {
        List<TreeView.Item<String>> items = rows(100);
        Rect bounds = new Rect(2, 2, 10, 10);
        TreeView.State captured = new TreeView.State(4, 37, 0, new Scrollbar.Drag(Scrollbar.Axis.VERTICAL, 0));
        TreeView.Placement<String> dragging = placement(items, captured, bounds);
        TreeView.State released = stateFrom(dragging.handle(
            pointer(40, 50, PointerEvent.Kind.RELEASE, PointerEvent.Button.LEFT), DISABLED
        ));
        assertEquals(new TreeView.State(4, 37, 0), released);
        assertIgnored(placement(items, released, bounds).handle(
            pointer(8, 11, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT), FOCUSED
        ));

        TreeView.State resized = stateFrom(dragging.handle(new ResizeEvent(20, 60), DISABLED));
        assertEquals(released, resized);
        TreeView.Placement<String> afterResize = placement(items, resized, new Rect(2, 2, 10, 8));
        assertTrue(afterResize.verticalScrollbarBounds().isPresent());
        assertIgnored(afterResize.handle(pointer(8, 11, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT), FOCUSED));
        assertNull(stateFrom(dragging.handle(KeyStroke.of(KeyStroke.Key.ARROW_DOWN), FOCUSED)).drag());
    }

    @Test
    void scrollbarsFollowParentBodyAndBorderAcrossFocusStates() {
        TreeView.Placement<String> tree = placement(rows(100), new TreeView.State(0, 37, 0), new Rect(0, 0, 10, 10));
        CellStyle body = new CellStyle(CellColor.YELLOW, CellColor.CYAN, false, false);
        CellStyle border = new CellStyle(CellColor.MAGENTA, CellColor.RED, true, true);
        CellStyle inherited = new CellStyle(CellColor.MAGENTA, CellColor.CYAN, true, true);
        for (WidgetState state : List.of(WidgetState.FOCUSED, WidgetState.NORMAL, WidgetState.DISABLED)) {
            CellBuffer buffer = filled(10, 10);
            tree.paint(new Canvas(buffer), THEME.withSurface(body, border), state);
            for (int row = 0; row < 10; row++) {
                assertEquals(inherited, buffer.get(row, 9).style());
            }
        }
    }

    @Test
    void cursorIsExactVisibleActionableCellAndNeverClampsAnOffscreenSelection() {
        List<TreeView.Item<String>> items = List.of(
            item("dir", "dir", 1, TreeView.Kind.COLLAPSED),
            item("leaf", "leaf", 2, TreeView.Kind.LEAF)
        );
        Rect bounds = new Rect(0, 0, 12, 2);
        TreeView.Placement<String> branch = placement(
            items,
            new TreeView.State(0, 0, 0),
            bounds
        );
        TreeView.Placement<String> leaf = placement(
            items,
            new TreeView.State(1, 0, 0),
            bounds
        );

        assertEquals(new Point(2, 0), branch.cursorPosition(FOCUSED).orElseThrow());
        assertEquals(new Point(6, 1), leaf.cursorPosition(FOCUSED).orElseThrow());
        assertTrue(branch.cursorPosition(UNFOCUSED).isEmpty());
        assertTrue(branch.cursorPosition(DISABLED).isEmpty());
        assertTrue(branch.cursorPosition(new FocusContext(
            true,
            true,
            FocusSpace.root(new Rect(0, 0, 2, 2))
        )).isEmpty());

        TreeView.Placement<String> offscreen = placement(
            items,
            new TreeView.State(0, 1, 0),
            new Rect(0, 0, 12, 1)
        );
        assertTrue(offscreen.cursorPosition(FOCUSED).isEmpty());
        TreeView.Placement<String> emptyWidth = placement(
            items,
            new TreeView.State(0, 0, 0),
            new Rect(0, 0, 0, 2)
        );
        assertTrue(emptyWidth.cursorPosition(FOCUSED).isEmpty());

        TreeView.Placement<String> plain = placement(
            List.of(item("parent", "..", 0, TreeView.Kind.PLAIN)),
            new TreeView.State(0, 0, 0),
            new Rect(0, 0, 4, 1)
        );
        assertEquals(2, plain.horizontalExtent());
        assertEquals(new Point(0, 0), plain.cursorPosition(FOCUSED).orElseThrow());
        TreeView.ItemPressed<?> plainPress = assertInstanceOf(
            TreeView.ItemPressed.class,
            plain.handle(press(0, 0), FOCUSED).output().orElseThrow()
        );
        assertEquals("parent", plainPress.value());
    }

    @Test
    void tinyGeometryNeverReservesAnUnusableOneCellScrollbarTrack() {
        TreeView.Placement<String> oneRow = placement(
            rows(4),
            new TreeView.State(0, 0, 0),
            new Rect(0, 0, 5, 1)
        );
        assertTrue(oneRow.verticalScrollbarBounds().isEmpty());

        TreeView.Placement<String> oneLeadingCell = placement(
            List.of(new TreeView.Item<>(
                "long",
                "long-label",
                0,
                TreeView.Kind.LEAF,
                "|D"
            )),
            new TreeView.State(0, 0, 0),
            new Rect(0, 0, 3, 2)
        );
        assertEquals(1, oneLeadingCell.leadingBounds().width());
        assertTrue(oneLeadingCell.horizontalScrollbarBounds().isEmpty());

        TreeView.Placement<String> competing = placement(
            List.of(
                item("0", "long-label", 0, TreeView.Kind.LEAF),
                item("1", "long-label", 0, TreeView.Kind.LEAF),
                item("2", "long-label", 0, TreeView.Kind.LEAF)
            ),
            new TreeView.State(0, 0, 0),
            new Rect(0, 0, 2, 2)
        );
        assertEquals(new Rect(1, 0, 1, 2), competing.verticalScrollbarBounds().orElseThrow());
        assertTrue(competing.horizontalScrollbarBounds().isEmpty());
    }

    @Test
    void unicodeTinyAndTranslatedClipsNeverPaintHalfWideCellsAndRestoreCanvas() {
        TreeView.Placement<String> tiny = placement(
            List.of(item("wide", "中", 0, TreeView.Kind.LEAF)),
            new TreeView.State(0, 0, 0),
            new Rect(0, 0, 3, 1)
        );
        CellBuffer tinyBuffer = filled(1, 3);
        tiny.paint(new Canvas(tinyBuffer), THEME, WidgetState.FOCUSED);
        assertEquals("   ", cellText(tinyBuffer, 0, 0, 3));
        assertFalse(tinyBuffer.get(0, 2).continuation());
        assertEquals(FOCUSED_SELECTED, tinyBuffer.get(0, 2).style());

        TreeView.Placement<String> translated = placement(
            List.of(item("unicode", "A中e\u0301Z", 0, TreeView.Kind.LEAF)),
            new TreeView.State(0, 0, 0),
            new Rect(1, 1, 7, 1)
        );
        CellBuffer buffer = filled(5, 12);
        buffer.set(2, 3, "界", SELECTED);
        Canvas canvas = new Canvas(buffer);
        canvas.translate(2, 1);
        assertTrue(canvas.clip(new Rect(2, 1, 3, 1)));

        translated.paint(canvas, THEME, WidgetState.FOCUSED);

        assertEquals("界", buffer.get(2, 3).grapheme());
        assertTrue(buffer.get(2, 4).continuation());
        assertFalse(canvas.put(1, 1, "x", NORMAL));
        assertTrue(canvas.put(4, 1, "z", NORMAL));
        assertEquals("z", buffer.get(2, 6).grapheme());
    }

    @Test
    void emptyInvalidAndExtremeInputsRemainSafe() {
        TreeView<String> empty = new TreeView<>(List.of(), new TreeView.State(99, 99, 99));
        TreeView.Placement<String> emptyPlacement = empty.layout(new Rect(0, 0, 4, 2));
        assertEquals(TreeView.State.initial(), emptyPlacement.state());
        InteractionResult<TreeView.Action<String>> emptyEnter = emptyPlacement.handle(
            KeyStroke.of(KeyStroke.Key.ENTER),
            FOCUSED
        );
        assertTrue(emptyEnter.consumed());
        assertTrue(emptyEnter.output().isEmpty());
        assertIgnored(emptyPlacement.handle(new TextInput("x"), FOCUSED));

        assertThrows(
            NullPointerException.class,
            () -> new TreeView.Item<String>(null, "label", 0, TreeView.Kind.LEAF)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new TreeView.Item<>("value", "label", -1, TreeView.Kind.LEAF)
        );
        assertThrows(IllegalArgumentException.class, () -> new TreeView.State(-2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TreeView.State(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TreeView.State(0, 0, -1));

        int x = Integer.MAX_VALUE - 4;
        TreeView.Placement<String> extreme = placement(
            List.of(item("deep", "tail".repeat(10_000), Integer.MAX_VALUE, TreeView.Kind.EXPANDED)),
            new TreeView.State(0, 0, Integer.MAX_VALUE),
            new Rect(x, 0, 4, 2)
        );
        assertEquals(Integer.MAX_VALUE, extreme.horizontalExtent());
        CellBuffer extremeBuffer = filled(1, 4);
        Canvas extremeCanvas = new Canvas(extremeBuffer);
        extremeCanvas.translate(-x, 0);

        extreme.paint(extremeCanvas, THEME, WidgetState.FOCUSED);

        assertEquals(FOCUSED_SELECTED, extremeBuffer.get(0, 0).style());
        assertTrue(extreme.cursorPosition(new FocusContext(
            true,
            true,
            FocusSpace.root(new Rect(x, 0, 4, 2))
        )).isEmpty());
    }

    private static TreeView.Placement<String> placement(
        List<TreeView.Item<String>> items,
        TreeView.State state,
        Rect bounds
    ) {
        return new TreeView<>(items, state).layout(bounds);
    }

    private static TreeView.Item<String> item(
        String value,
        String label,
        int depth,
        TreeView.Kind kind
    ) {
        return new TreeView.Item<>(value, label, depth, kind);
    }

    private static List<TreeView.Item<String>> rows(int count) {
        ArrayList<TreeView.Item<String>> rows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            rows.add(item(
                "row-" + index,
                "r" + index,
                index == 0 ? 0 : 1,
                index == 0 ? TreeView.Kind.COLLAPSED : TreeView.Kind.LEAF
            ));
        }
        return List.copyOf(rows);
    }

    private static TreeView.ExpansionRequested<String> expansionFrom(
        InteractionResult<TreeView.Action<String>> result
    ) {
        assertTrue(result.consumed());
        return (TreeView.ExpansionRequested<String>) assertInstanceOf(
            TreeView.ExpansionRequested.class,
            result.output().orElseThrow()
        );
    }

    private static void assertState(
        TreeView.State expected,
        InteractionResult<TreeView.Action<String>> result
    ) {
        assertTrue(result.consumed());
        TreeView.StateChanged<?> changed = assertInstanceOf(
            TreeView.StateChanged.class,
            result.output().orElseThrow()
        );
        assertEquals(expected, changed.state());
    }

    private static TreeView.State stateFrom(InteractionResult<TreeView.Action<String>> result) {
        assertTrue(result.consumed());
        return assertInstanceOf(TreeView.StateChanged.class, result.output().orElseThrow()).state();
    }

    private static void assertIgnored(InteractionResult<TreeView.Action<String>> result) {
        assertFalse(result.consumed());
        assertTrue(result.output().isEmpty());
        assertEquals(FocusRequest.NONE, result.focusRequest());
    }

    private static PointerEvent press(int row, int column) {
        return pointer(row, column, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT);
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
}
