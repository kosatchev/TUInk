package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import dev.tuiframe.input.TextInput;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MenuBarTest {
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

    private static final CellStyle BAR = CellStyle.fg(CellColor.WHITE);
    private static final CellStyle ACTIVE_BAR = CellStyle.fg(CellColor.BRIGHT_BLUE);
    private static final CellStyle DISABLED_BAR = CellStyle.fg(CellColor.BRIGHT_BLACK);
    private static final CellStyle POPUP = CellStyle.fg(CellColor.BRIGHT_WHITE);
    private static final CellStyle ITEM = CellStyle.fg(CellColor.CYAN);
    private static final CellStyle SELECTED = CellStyle.fg(CellColor.BRIGHT_GREEN);
    private static final CellStyle DISABLED_ITEM = CellStyle.fg(CellColor.BRIGHT_BLACK);
    private static final CellStyle ACCENT = CellStyle.fg(CellColor.BRIGHT_MAGENTA);
    private static final Theme THEME = role -> {
        if (WidgetThemeKeys.MENU_BAR.equals(role)) {
            return BAR;
        }
        if (WidgetThemeKeys.MENU_BAR_ACTIVE.equals(role)) {
            return ACTIVE_BAR;
        }
        if (WidgetThemeKeys.MENU_BAR_DISABLED.equals(role)) {
            return DISABLED_BAR;
        }
        if (WidgetThemeKeys.MENU_POPUP.equals(role)) {
            return POPUP;
        }
        if (WidgetThemeKeys.MENU_ITEM.equals(role)) {
            return ITEM;
        }
        if (WidgetThemeKeys.MENU_ITEM_SELECTED.equals(role)) {
            return SELECTED;
        }
        if (WidgetThemeKeys.MENU_ITEM_DISABLED.equals(role)) {
            return DISABLED_ITEM;
        }
        return null;
    };

    @Test
    void copiesTheDescriptorGraphAndValidatesThePublicModel() {
        List<MenuBar.Entry<String>> entrySource = new ArrayList<>();
        entrySource.add(item("old-value", "Old label", 0, true));
        MenuBar.Menu<String> original = new MenuBar.Menu<>("File", 1, entrySource);
        entrySource.set(0, item("changed-value", "Changed label", 0, true));
        entrySource.add(item("late-value", "Late label", 0, true));

        List<MenuBar.Menu<String>> menuSource = new ArrayList<>();
        menuSource.add(original);
        MenuBar<String> bar = new MenuBar<>(menuSource, MenuBar.State.open(0));
        menuSource.set(0, menu("Replacement", item("replacement", "Replacement", 0, true)));
        menuSource.add(menu("Late", item("late-menu", "Late menu", 0, true)));

        assertEquals(new Size(18, 6), bar.measure(new Size(18, 6)));
        assertEquals(Size.ZERO, bar.measure(Size.ZERO));
        MenuBar.Placement<String> placement = bar.layout(new Rect(0, 0, 18, 6));
        assertEquals(new MenuBar.State(0, 0, 0), placement.state());
        assertEquals("old-value", invokedValue(placement.handle(key(KeyStroke.Key.ENTER), FOCUSED)));

        CellBuffer buffer = filled(7, 20);
        placement.paint(new Canvas(buffer), THEME, WidgetState.FOCUSED);
        assertEquals("File", cellText(buffer, 0, 0, 4));
        assertEquals("Old label", cellText(buffer, 2, 2, 9));

        List<MenuBar.Menu<String>> nullMenu = new ArrayList<>();
        nullMenu.add(null);
        List<MenuBar.Entry<String>> nullEntry = new ArrayList<>();
        nullEntry.add(null);
        assertThrows(NullPointerException.class, () -> new MenuBar<String>(null, MenuBar.State.closed()));
        assertThrows(NullPointerException.class, () -> new MenuBar<>(List.of(), null));
        assertThrows(NullPointerException.class, () -> new MenuBar<>(nullMenu, MenuBar.State.closed()));
        assertThrows(NullPointerException.class, () -> new MenuBar.Menu<String>(null, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MenuBar.Menu<>("", 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MenuBar.Menu<>("File", -2, List.of()));
        assertThrows(NullPointerException.class, () -> new MenuBar.Menu<String>("File", 0, null));
        assertThrows(NullPointerException.class, () -> new MenuBar.Menu<>("File", 0, nullEntry));
        assertThrows(NullPointerException.class, () -> new MenuBar.Item<String>(null, "Item", 0, true));
        assertThrows(NullPointerException.class, () -> new MenuBar.Item<>("value", null, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new MenuBar.Item<>("value", "", 0, true));
        assertThrows(IllegalArgumentException.class, () -> new MenuBar.Item<>("value", "Item", -2, true));
        assertThrows(IllegalArgumentException.class, () -> new MenuBar.State(-2, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new MenuBar.State(0, -2, 0));
        assertThrows(IllegalArgumentException.class, () -> new MenuBar.State(0, -1, -1));
        assertThrows(IllegalArgumentException.class, () -> new MenuBar.State(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MenuBar.State(-1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> MenuBar.State.open(-1));
        assertThrows(NullPointerException.class, () -> new MenuBar.StateChanged<String>(null));
        assertThrows(NullPointerException.class, () -> new MenuBar.Invoked<String>(null, MenuBar.State.closed()));
        assertThrows(NullPointerException.class, () -> new MenuBar.Invoked<>("value", null));
        assertThrows(
            IllegalArgumentException.class,
            () -> new MenuBar.Invoked<>("value", MenuBar.State.open(0))
        );
        assertThrows(NullPointerException.class, () -> bar.measure(null));
        assertThrows(NullPointerException.class, () -> bar.layout(null));
    }

    @Test
    void paintsVerbatimTopLabelsAtVisualStartsWithThemeRolesAndMnemonics() {
        List<MenuBar.Menu<String>> menus = List.of(
            menu(
                "File",
                1,
                item("open", "Open", 0, true),
                item("disabled", "Disabled", 0, false),
                new MenuBar.Separator<>()
            ),
            menu("Edit", 0, item("undo", "Undo", 0, true))
        );
        MenuBar.Placement<String> placement = placement(
            menus,
            MenuBar.State.open(0),
            new Rect(2, 1, 24, 7)
        );
        CellBuffer buffer = filled(9, 30);

        placement.paint(new Canvas(buffer), THEME, WidgetState.FOCUSED);

        assertEquals("File", cellText(buffer, 1, 2, 4));
        assertEquals(".", buffer.get(1, 6).grapheme(), "the inter-menu gap is not label text");
        assertEquals("Edit", cellText(buffer, 1, 7, 4));
        assertEquals(ACTIVE_BAR, buffer.get(1, 2).style());
        assertEquals(underlined(ACTIVE_BAR), buffer.get(1, 3).style());
        assertEquals(underlined(BAR), buffer.get(1, 7).style());
        assertEquals(new Rect(2, 2, 12, 5), placement.popupBounds().orElseThrow());
        assertEquals("┌", buffer.get(2, 2).grapheme());
        assertEquals("┐", buffer.get(2, 13).grapheme());
        assertEquals(POPUP, buffer.get(2, 2).style());
        assertEquals("Open", cellText(buffer, 3, 4, 4));
        assertEquals(SELECTED, buffer.get(3, 5).style());
        assertEquals(underlined(SELECTED), buffer.get(3, 4).style());
        assertEquals(underlined(DISABLED_ITEM), buffer.get(4, 4).style());
        assertEquals("─", buffer.get(5, 3).grapheme());
        assertEquals(POPUP, buffer.get(5, 3).style());
        assertEquals("└", buffer.get(6, 2).grapheme());
        assertEquals("┘", buffer.get(6, 13).grapheme());

        CellBuffer disabled = filled(9, 30);
        placement.paint(new Canvas(disabled), THEME, WidgetState.DISABLED);
        assertEquals(DISABLED_BAR, disabled.get(1, 2).style());
        assertEquals(underlined(DISABLED_BAR), disabled.get(1, 3).style());
        assertEquals(underlined(DISABLED_ITEM), disabled.get(3, 4).style());
        assertEquals(underlined(DISABLED_ITEM), disabled.get(4, 4).style());
        assertEquals(POPUP, disabled.get(5, 3).style());
    }

    @Test
    void keyboardSwitchesMenusSkipsDisabledRowsAndSeparatorsAndReturnsTypedActions() {
        List<MenuBar.Menu<String>> menus = List.of(
            menu(
                "File",
                item("disabled-first", "Disabled first", 0, false),
                new MenuBar.Separator<>(),
                item("alpha", "Alpha", 0, true),
                item("disabled-middle", "Disabled middle", 0, false),
                item("beta", "Beta", 0, true)
            ),
            menu("Edit", item("gamma", "Gamma", 0, true))
        );
        Rect bounds = new Rect(0, 0, 30, 10);
        MenuBar.Placement<String> open = placement(menus, MenuBar.State.open(0), bounds);

        assertEquals(new MenuBar.State(0, 2, 0), open.state());
        assertIgnored(placement(menus, MenuBar.State.closed(), bounds).handle(
            key(KeyStroke.Key.ARROW_DOWN),
            FOCUSED
        ));
        assertIgnored(open.handle(key(KeyStroke.Key.ARROW_DOWN), UNFOCUSED));

        MenuBar.State down = stateChange(open.handle(key(KeyStroke.Key.ARROW_DOWN), FOCUSED));
        assertEquals(new MenuBar.State(0, 4, 0), down);
        MenuBar.State wrappedDown = stateChange(
            placement(menus, down, bounds).handle(key(KeyStroke.Key.ARROW_DOWN), FOCUSED)
        );
        assertEquals(new MenuBar.State(0, 2, 0), wrappedDown);
        MenuBar.State wrappedUp = stateChange(
            placement(menus, wrappedDown, bounds).handle(key(KeyStroke.Key.ARROW_UP), FOCUSED)
        );
        assertEquals(new MenuBar.State(0, 4, 0), wrappedUp);

        MenuBar.State edit = stateChange(open.handle(key(KeyStroke.Key.ARROW_RIGHT), FOCUSED));
        assertEquals(new MenuBar.State(1, 0, 0), edit);
        MenuBar.State file = stateChange(
            placement(menus, edit, bounds).handle(key(KeyStroke.Key.ARROW_RIGHT), FOCUSED)
        );
        assertEquals(new MenuBar.State(0, 2, 0), file);
        assertEquals(
            MenuBar.State.closed(),
            stateChange(open.handle(key(KeyStroke.Key.ESCAPE), FOCUSED))
        );
        assertEquals("alpha", invokedValue(open.handle(key(KeyStroke.Key.ENTER), FOCUSED)));

        InteractionResult<MenuBar.Action<String>> unsupported = open.handle(
            key(KeyStroke.Key.TAB),
            FOCUSED
        );
        assertCaptured(unsupported);
    }

    @Test
    void dynamicViewportNeverInvokesHiddenRowsAndOrdinalRequiresAVisibleRebuild() {
        List<MenuBar.Menu<String>> menus = List.of(menu(
            "Menu",
            item("zero", "Zero", 0, true),
            item("one", "One", 0, true),
            item("two", "Two", 0, true),
            item("hidden", "Hidden", 0, true),
            item("four", "Four", 0, true)
        ));
        Rect shortBounds = new Rect(0, 0, 14, 5);
        MenuBar.Placement<String> first = placement(menus, MenuBar.State.open(0), shortBounds);

        assertEquals(new MenuBar.State(0, 0, 0), first.state());
        assertEquals(new Rect(0, 1, 10, 4), first.popupBounds().orElseThrow());
        assertCaptured(first.handle(new TextInput("h"), FOCUSED));

        MenuBar.State revealFourth = stateChange(first.handle(new TextInput("4"), FOCUSED));
        assertEquals(new MenuBar.State(0, 3, 2), revealFourth);
        assertEquals("zero", invokedValue(first.handle(key(KeyStroke.Key.ENTER), FOCUSED)));

        MenuBar.Placement<String> revealed = placement(menus, revealFourth, shortBounds);
        assertEquals(new Rect(0, 1, 10, 4), revealed.popupBounds().orElseThrow());
        assertEquals("hidden", invokedValue(revealed.handle(new TextInput("4"), FOCUSED)));
        assertEquals("hidden", invokedValue(revealed.handle(key(KeyStroke.Key.ENTER), FOCUSED)));

        MenuBar.Placement<String> noRows = placement(
            menus,
            new MenuBar.State(0, 3, 0),
            new Rect(0, 0, 14, 3)
        );
        assertTrue(noRows.popupBounds().isEmpty());
        assertCaptured(noRows.handle(key(KeyStroke.Key.ENTER), FOCUSED));
        assertCaptured(noRows.handle(new TextInput("h"), FOCUSED));
        assertCaptured(noRows.handle(new TextInput("4"), FOCUSED));
    }

    @Test
    void pointerUsesExactTopPopupAndFrameGeometryAndOpenMenuCapturesTheWholeBounds() {
        List<MenuBar.Menu<String>> menus = List.of(
            menu(
                "File",
                item("enabled", "Enabled", 0, true),
                item("disabled", "Disabled", 0, false),
                new MenuBar.Separator<>(),
                item("last", "Last", 0, true)
            ),
            menu("Edit", item("edit", "Edit item", 0, true))
        );
        Rect bounds = new Rect(2, 1, 22, 8);
        MenuBar.Placement<String> closed = placement(menus, MenuBar.State.closed(), bounds);

        assertTrue(closed.hit(new Point(2, 1)));
        assertFalse(closed.hit(new Point(6, 1)));
        assertEquals(
            new MenuBar.State(0, 0, 0),
            stateChange(closed.handle(press(1, 2), UNFOCUSED))
        );
        assertEquals(
            new MenuBar.State(1, 0, 0),
            stateChange(closed.handle(press(1, 7), UNFOCUSED))
        );
        assertIgnored(closed.handle(press(1, 6), UNFOCUSED));

        MenuBar.Placement<String> open = placement(menus, MenuBar.State.open(0), bounds);
        Rect popup = open.popupBounds().orElseThrow();
        assertEquals(new Rect(2, 2, 12, 6), popup);
        assertTrue(open.hit(new Point(23, 8)), "an open menu captures its complete placement bounds");
        assertEquals(MenuBar.State.closed(), stateChange(open.handle(press(1, 2), UNFOCUSED)));
        assertEquals(
            new MenuBar.State(1, 0, 0),
            stateChange(open.handle(press(1, 7), UNFOCUSED))
        );
        assertEquals("enabled", invokedValue(open.handle(press(3, 3), UNFOCUSED)));
        assertCaptured(open.handle(press(4, 3), UNFOCUSED));
        assertCaptured(open.handle(press(5, 3), UNFOCUSED));
        assertCaptured(open.handle(press(2, 2), UNFOCUSED));
        assertCaptured(open.handle(press(7, 13), UNFOCUSED));
        assertEquals(MenuBar.State.closed(), stateChange(open.handle(press(8, 23), UNFOCUSED)));

        assertCaptured(open.handle(modifiedPress(8, 23, KeyStroke.Modifier.SHIFT), UNFOCUSED));
        assertCaptured(open.handle(
            pointer(8, 23, PointerEvent.Kind.RELEASE, PointerEvent.Button.LEFT),
            UNFOCUSED
        ));
        assertCaptured(open.handle(
            pointer(8, 23, PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE),
            UNFOCUSED
        ));
        assertCaptured(open.handle(
            pointer(8, 23, PointerEvent.Kind.PRESS, PointerEvent.Button.RIGHT),
            UNFOCUSED
        ));
        assertIgnored(open.handle(press(0, 2), UNFOCUSED));
        assertIgnored(closed.handle(modifiedPress(1, 2, KeyStroke.Modifier.CTRL), UNFOCUSED));
    }

    @Test
    void wideAndCombiningMnemonicsPaintActivateAndOwnTheExactCursorCell() {
        List<MenuBar.Menu<String>> menus = List.of(menu(
            "文e\u0301",
            1,
            item("wide", "中x", 0, true),
            item("combining", "A中e\u0301Z", 2, true)
        ));
        Rect bounds = new Rect(1, 1, 20, 7);
        MenuBar.Placement<String> combining = placement(
            menus,
            new MenuBar.State(0, 1, 0),
            bounds
        );
        CellBuffer buffer = filled(9, 24);

        combining.paint(new Canvas(buffer), THEME, WidgetState.FOCUSED);

        assertEquals("文", buffer.get(1, 1).grapheme());
        assertTrue(buffer.get(1, 2).continuation());
        assertEquals("e\u0301", buffer.get(1, 3).grapheme());
        assertEquals(underlined(ACTIVE_BAR), buffer.get(1, 3).style());
        assertEquals("A", buffer.get(4, 3).grapheme());
        assertEquals("中", buffer.get(4, 4).grapheme());
        assertTrue(buffer.get(4, 5).continuation());
        assertEquals("e\u0301", buffer.get(4, 6).grapheme());
        assertEquals(underlined(SELECTED), buffer.get(4, 6).style());
        assertEquals(new Point(6, 4), combining.cursorPosition(FOCUSED).orElseThrow());
        assertTrue(combining.cursorPosition(UNFOCUSED).isEmpty());
        assertEquals("combining", invokedValue(combining.handle(new TextInput("E\u0301"), FOCUSED)));
        assertEquals("combining", invokedValue(combining.handle(new TextInput("\u00c9"), FOCUSED)));

        MenuBar.Placement<String> wide = placement(menus, MenuBar.State.open(0), bounds);
        assertEquals(new Point(3, 3), wide.cursorPosition(FOCUSED).orElseThrow());
        assertEquals("wide", invokedValue(wide.handle(new TextInput("中"), FOCUSED)));
    }

    @Test
    void translatedPaintHonorsTheCallerNarrowClipProtectsWidePairsAndRestoresCanvasState() {
        MenuBar.Placement<String> placement = placement(
            List.of(menu("Menu", item("alpha", "Alpha", 0, true))),
            MenuBar.State.open(0),
            new Rect(1, 1, 10, 5)
        );
        CellBuffer buffer = filled(8, 16);
        buffer.set(2, 3, "中", ACCENT);
        Cell wideLead = buffer.get(2, 3);
        Cell wideContinuation = buffer.get(2, 4);
        Canvas canvas = new Canvas(buffer);
        canvas.translate(2, 1);
        assertTrue(canvas.clip(new Rect(2, 1, 2, 3)));

        placement.paint(canvas, THEME, WidgetState.FOCUSED);

        assertSame(wideLead, buffer.get(2, 3));
        assertSame(wideContinuation, buffer.get(2, 4));
        assertEquals("n", buffer.get(2, 5).grapheme());
        assertEquals(ACTIVE_BAR, buffer.get(2, 5).style());
        assertEquals(".", buffer.get(2, 6).grapheme());
        assertFalse(canvas.put(1, 1, "x", ACCENT), "the caller's narrow clip must remain active");
        assertTrue(canvas.put(3, 1, "z", ACCENT));
        assertEquals("z", buffer.get(2, 5).grapheme(), "the caller's translation must remain active");
    }

    @Test
    void zeroOneCellAndSubMinimumWidthPlacementsStaySafeAndCannotInvokeHiddenItems() {
        List<MenuBar.Menu<String>> oneMenu = List.of(menu(
            "M",
            0,
            item("item", "Item", 0, true)
        ));
        MenuBar.Placement<String> zeroHeight = placement(
            oneMenu,
            MenuBar.State.open(0),
            new Rect(1, 1, 8, 0)
        );
        MenuBar.Placement<String> zeroWidth = placement(
            oneMenu,
            MenuBar.State.open(0),
            new Rect(1, 1, 0, 4)
        );
        CellBuffer untouched = filled(4, 12);
        zeroHeight.paint(new Canvas(untouched), THEME, WidgetState.FOCUSED);
        zeroWidth.paint(new Canvas(untouched), THEME, WidgetState.FOCUSED);
        assertEquals(".", untouched.get(1, 1).grapheme());
        assertIgnored(zeroHeight.handle(key(KeyStroke.Key.ENTER), FOCUSED));
        assertIgnored(zeroWidth.handle(press(1, 1), UNFOCUSED));

        MenuBar.Placement<String> oneCell = placement(
            oneMenu,
            MenuBar.State.open(0),
            new Rect(0, 0, 1, 1)
        );
        CellBuffer single = filled(1, 1);
        oneCell.paint(new Canvas(single), THEME, WidgetState.FOCUSED);
        assertEquals("M", single.get(0, 0).grapheme());
        assertEquals(underlined(ACTIVE_BAR), single.get(0, 0).style());
        assertTrue(oneCell.popupBounds().isEmpty());
        assertTrue(oneCell.cursorPosition(FOCUSED).isEmpty());
        assertCaptured(oneCell.handle(key(KeyStroke.Key.ENTER), FOCUSED));
        assertEquals(MenuBar.State.closed(), stateChange(oneCell.handle(press(0, 0), UNFOCUSED)));

        List<MenuBar.Menu<String>> narrowMenus = List.of(menu(
            "Menu",
            item("item", "Item", 0, true)
        ));
        MenuBar.Placement<String> narrow = placement(
            narrowMenus,
            MenuBar.State.open(0),
            new Rect(0, 0, 5, 5)
        );
        assertEquals(new MenuBar.State(0, 0, 0), narrow.state());
        assertTrue(narrow.popupBounds().isEmpty());
        assertTrue(narrow.cursorPosition(FOCUSED).isEmpty());
        assertCaptured(narrow.handle(key(KeyStroke.Key.ENTER), FOCUSED));
        assertCaptured(narrow.handle(new TextInput("i"), FOCUSED));
        assertCaptured(narrow.handle(new TextInput("1"), FOCUSED));
        assertEquals(MenuBar.State.closed(), stateChange(narrow.handle(press(4, 4), UNFOCUSED)));
    }

    @Test
    void extremeRepresentableRectanglesLayoutPaintAndRouteWithoutCoordinateOverflow() {
        List<MenuBar.Menu<String>> menus = List.of(menu(
            "M",
            0,
            item("item", "Item", 0, true)
        ));
        Rect maximum = new Rect(Integer.MAX_VALUE - 12, Integer.MAX_VALUE - 6, 12, 6);
        MenuBar.Placement<String> high = placement(menus, MenuBar.State.open(0), maximum);
        Rect popup = high.popupBounds().orElseThrow();
        assertEquals(Integer.MAX_VALUE, maximum.rightExclusive());
        assertEquals(Integer.MAX_VALUE, maximum.bottomExclusive());
        assertTrue(popup.rightExclusive() <= Integer.MAX_VALUE);
        assertTrue(popup.bottomExclusive() <= Integer.MAX_VALUE);

        Canvas canvas = new Canvas(filled(2, 3));
        high.paint(canvas, THEME, WidgetState.FOCUSED);
        assertTrue(canvas.put(0, 0, "z", ACCENT), "paint must restore Canvas after an empty intersection");
        FocusContext extremeContext = new FocusContext(
            false,
            true,
            FocusSpace.root(maximum)
        );
        assertEquals(
            MenuBar.State.closed(),
            stateChange(high.handle(press(maximum.y(), maximum.x()), extremeContext))
        );

        Rect minimum = new Rect(Integer.MIN_VALUE, Integer.MIN_VALUE, 1, 1);
        MenuBar.Placement<String> low = placement(menus, MenuBar.State.closed(), minimum);
        Canvas lowCanvas = new Canvas(filled(1, 1));
        low.paint(lowCanvas, THEME, WidgetState.NORMAL);
        assertTrue(lowCanvas.put(0, 0, "q", ACCENT));
    }

    @Test
    void stalePlacementKeepsItsExactStateUntilTheApplicationRebuildsIt() {
        List<MenuBar.Menu<String>> menus = List.of(menu(
            "Menu",
            item("first", "First", 0, true),
            item("second", "Second", 0, true)
        ));
        Rect bounds = new Rect(0, 0, 16, 6);
        MenuBar.Placement<String> old = placement(menus, MenuBar.State.open(0), bounds);

        MenuBar.State moved = stateChange(old.handle(key(KeyStroke.Key.ARROW_DOWN), FOCUSED));
        assertEquals(new MenuBar.State(0, 1, 0), moved);
        assertEquals("first", invokedValue(old.handle(key(KeyStroke.Key.ENTER), FOCUSED)));

        MenuBar.Placement<String> rebuilt = placement(menus, moved, bounds);
        assertEquals(new MenuBar.State(0, 0, 0), old.state());
        assertEquals(new Point(2, 2), old.cursorPosition(FOCUSED).orElseThrow());
        assertEquals(new Point(2, 3), rebuilt.cursorPosition(FOCUSED).orElseThrow());
        assertEquals("second", invokedValue(rebuilt.handle(key(KeyStroke.Key.ENTER), FOCUSED)));
        assertEquals("first", invokedValue(old.handle(new TextInput("f"), FOCUSED)));
        assertEquals("second", invokedValue(rebuilt.handle(new TextInput("s"), FOCUSED)));
    }

    private static MenuBar.Placement<String> placement(
        List<MenuBar.Menu<String>> menus,
        MenuBar.State state,
        Rect bounds
    ) {
        return new MenuBar<>(menus, state).layout(bounds);
    }

    @SafeVarargs
    private static MenuBar.Menu<String> menu(String label, MenuBar.Entry<String>... entries) {
        return menu(label, 0, entries);
    }

    @SafeVarargs
    private static MenuBar.Menu<String> menu(
        String label,
        int mnemonic,
        MenuBar.Entry<String>... entries
    ) {
        return new MenuBar.Menu<>(label, mnemonic, List.of(entries));
    }

    private static MenuBar.Item<String> item(
        String value,
        String label,
        int mnemonic,
        boolean enabled
    ) {
        return new MenuBar.Item<>(value, label, mnemonic, enabled);
    }

    private static KeyStroke key(KeyStroke.Key key) {
        return KeyStroke.of(key);
    }

    private static PointerEvent press(int row, int column) {
        return pointer(row, column, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT);
    }

    private static PointerEvent modifiedPress(
        int row,
        int column,
        KeyStroke.Modifier modifier
    ) {
        return new PointerEvent(
            row,
            column,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT,
            Set.of(modifier)
        );
    }

    private static PointerEvent pointer(
        int row,
        int column,
        PointerEvent.Kind kind,
        PointerEvent.Button button
    ) {
        return new PointerEvent(row, column, kind, button, Set.of());
    }

    private static MenuBar.Action<String> output(
        InteractionResult<MenuBar.Action<String>> result
    ) {
        assertTrue(result.consumed());
        assertEquals(FocusRequest.NONE, result.focusRequest());
        return result.output().orElseThrow();
    }

    private static MenuBar.State stateChange(
        InteractionResult<MenuBar.Action<String>> result
    ) {
        MenuBar.Action<String> action = output(result);
        assertTrue(action instanceof MenuBar.StateChanged<?>);
        return ((MenuBar.StateChanged<?>) action).state();
    }

    private static Object invokedValue(
        InteractionResult<MenuBar.Action<String>> result
    ) {
        MenuBar.Action<String> action = output(result);
        assertTrue(action instanceof MenuBar.Invoked<?>);
        MenuBar.Invoked<?> invoked = (MenuBar.Invoked<?>) action;
        assertEquals(MenuBar.State.closed(), invoked.state());
        return invoked.value();
    }

    private static void assertCaptured(InteractionResult<MenuBar.Action<String>> result) {
        assertTrue(result.consumed());
        assertEquals(FocusRequest.NONE, result.focusRequest());
        assertTrue(result.output().isEmpty());
    }

    private static void assertIgnored(InteractionResult<MenuBar.Action<String>> result) {
        assertFalse(result.consumed());
        assertEquals(FocusRequest.NONE, result.focusRequest());
        assertTrue(result.output().isEmpty());
    }

    private static CellStyle underlined(CellStyle style) {
        return style.underline()
            ? style
            : new CellStyle(style.fg(), style.bg(), style.bold(), true);
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
