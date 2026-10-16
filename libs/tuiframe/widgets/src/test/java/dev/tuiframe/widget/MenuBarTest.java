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
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.scroll.Scrollbar;
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
        assertThrows(IllegalArgumentException.class, () -> new MenuBar.State(
            0, 0, 0, new Scrollbar.Drag(Scrollbar.Axis.HORIZONTAL, 0)
        ));
        assertThrows(IllegalArgumentException.class, () -> new MenuBar.State(
            -1, -1, 0, new Scrollbar.Drag(Scrollbar.Axis.VERTICAL, 0)
        ));
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
        assertEquals(" ", buffer.get(1, 6).grapheme(), "the menu owns the background between headings");
        assertEquals(BAR, buffer.get(1, 6).style());
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
    void headingWidthIncludesVisualLabelsAndGapsAndPaintingPreservesTheRemainder() {
        MenuBar<String> bar = new MenuBar<>(List.of(
            menu("中e\u0301", item("one", "One", 0, true)),
            menu("More", item("two", "Two", 0, true))
        ), MenuBar.State.closed());
        assertEquals(8, bar.preferredBarWidth());
        CellBuffer buffer = filled(5, 18);
        bar.layout(new Rect(2, 1, 14, 4)).paint(new Canvas(buffer), THEME, WidgetState.NORMAL);
        assertEquals(" ", buffer.get(1, 5).grapheme());
        assertEquals(BAR, buffer.get(1, 5).style());
        assertEquals(".", buffer.get(1, 10).grapheme(), "metadata after the last heading belongs to the caller");
        assertEquals(".", buffer.get(2, 2).grapheme(), "a closed menu must not clear the document layer");
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
        assertEquals(new Rect(0, 1, 11, 4), first.popupBounds().orElseThrow());
        assertCaptured(first.handle(new TextInput("h"), FOCUSED));

        MenuBar.State revealFourth = stateChange(first.handle(new TextInput("4"), FOCUSED));
        assertEquals(new MenuBar.State(0, 3, 2), revealFourth);
        assertEquals("zero", invokedValue(first.handle(key(KeyStroke.Key.ENTER), FOCUSED)));

        MenuBar.Placement<String> revealed = placement(menus, revealFourth, shortBounds);
        assertEquals(new Rect(0, 1, 11, 4), revealed.popupBounds().orElseThrow());
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
    void clippedItemsUseDisabledStylesAndCannotActivateUntilTheirFullLabelFits() {
        List<MenuBar.Menu<String>> menus = List.of(menu("Menu",
            item("confirm", "Confirm on close", 0, true),
            item("go", "Go", 0, true),
            item("off", "Off", 0, false)
        ));
        MenuBar.Placement<String> narrow = placement(menus, MenuBar.State.open(0), new Rect(0, 0, 8, 6));
        List<TuiInputEvent> confirmInputs = List.of(
            key(KeyStroke.Key.ENTER), new TextInput("1"), new TextInput("c"), press(2, 2)
        );

        for (TuiInputEvent event : confirmInputs) {
            assertCaptured(narrow.handle(event, FOCUSED));
        }
        assertTrue(narrow.cursorPosition(FOCUSED).isEmpty());
        CellBuffer clipped = filled(6, 8);
        narrow.paint(new Canvas(clipped), THEME, WidgetState.FOCUSED);
        assertEquals("Conf", cellText(clipped, 2, 2, 4));
        assertEquals(underlined(DISABLED_ITEM), clipped.get(2, 2).style());
        assertEquals(DISABLED_ITEM, clipped.get(2, 3).style());
        assertEquals(underlined(ITEM), clipped.get(3, 2).style());
        assertEquals(underlined(DISABLED_ITEM), clipped.get(4, 2).style());

        MenuBar.State down = stateChange(narrow.handle(key(KeyStroke.Key.ARROW_DOWN), FOCUSED));
        assertEquals(1, down.selectedEntryIndex());
        MenuBar.Placement<String> go = placement(menus, down, narrow.bounds());
        for (TuiInputEvent event : List.of(
            key(KeyStroke.Key.ENTER), new TextInput("2"), new TextInput("g"), press(3, 2)
        )) {
            assertEquals("go", invokedValue(go.handle(event, FOCUSED)));
        }
        assertEquals(MenuBar.State.closed(), stateChange(narrow.handle(key(KeyStroke.Key.ESCAPE), FOCUSED)));

        MenuBar.Placement<String> expanded = placement(menus, narrow.state(), new Rect(0, 0, 24, 6));
        for (TuiInputEvent event : confirmInputs) {
            assertEquals("confirm", invokedValue(expanded.handle(event, FOCUSED)));
        }
        assertEquals(new Point(2, 2), expanded.cursorPosition(FOCUSED).orElseThrow());
        CellBuffer restored = filled(6, 24);
        expanded.paint(new Canvas(restored), THEME, WidgetState.FOCUSED);
        assertEquals(underlined(SELECTED), restored.get(2, 2).style());
        assertEquals(underlined(DISABLED_ITEM), restored.get(4, 2).style());
        assertCaptured(expanded.handle(new TextInput("o"), FOCUSED));
        assertCaptured(expanded.handle(press(4, 2), FOCUSED));
    }

    @Test
    void activationRequiresCompleteGraphemesAndTabExpansionInsideThePopup() {
        record Label(String text, int mnemonic, String input, int width) {}
        for (Label label : List.of(
            new Label("中e\u0301Z", 1, "É", 4),
            new Label("😀Z", 0, "😀", 3),
            new Label("A\tB", 0, "a", 5)
        )) {
            List<MenuBar.Menu<String>> menus = List.of(menu("M",
                item("value", label.text(), label.mnemonic(), true)));
            MenuBar.Placement<String> clipped = placement(menus, MenuBar.State.open(0),
                new Rect(0, 0, label.width() + 3, 5));
            MenuBar.Placement<String> complete = placement(menus, clipped.state(),
                new Rect(0, 0, label.width() + 4, 5));
            for (TuiInputEvent event : List.of(
                key(KeyStroke.Key.ENTER), new TextInput("1"), new TextInput(label.input()), press(2, 2)
            )) {
                assertCaptured(clipped.handle(event, FOCUSED));
                assertEquals("value", invokedValue(complete.handle(event, FOCUSED)));
            }
            assertTrue(clipped.cursorPosition(FOCUSED).isEmpty());
            assertTrue(complete.cursorPosition(FOCUSED).isPresent());
        }
    }

    @Test
    void aScrollbarCanMakeAnOtherwiseFittingLabelUnavailableAndOrdinalsOnlyRevealIt() {
        List<MenuBar.Menu<String>> menus = List.of(menu("M",
            item("four", "Four", 0, true), item("go", "Go", 0, true),
            item("no", "No", 0, true), item("last", "Last", 0, true)
        ));
        Rect shortBounds = new Rect(0, 0, 8, 5);
        MenuBar.Placement<String> initial = placement(menus, MenuBar.State.open(0), shortBounds);
        assertTrue(initial.scrollbar().isPresent());
        assertCaptured(initial.handle(key(KeyStroke.Key.ENTER), FOCUSED));
        assertCaptured(initial.handle(new TextInput("f"), FOCUSED));

        MenuBar.State reveal = stateChange(initial.handle(new TextInput("4"), FOCUSED));
        assertEquals(3, reveal.selectedEntryIndex());
        MenuBar.Placement<String> last = placement(menus, reveal, shortBounds);
        for (TuiInputEvent event : List.of(
            key(KeyStroke.Key.ENTER), new TextInput("4"), new TextInput("l"), press(3, 2)
        )) {
            assertCaptured(last.handle(event, FOCUSED));
        }

        MenuBar.Placement<String> taller = placement(menus, reveal, new Rect(0, 0, 8, 7));
        assertTrue(taller.scrollbar().isEmpty());
        assertEquals("last", invokedValue(taller.handle(key(KeyStroke.Key.ENTER), FOCUSED)));
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

    @Test
    void narrowLayoutRevealsTheActiveHeadingAfterKeyboardSwitchAndResize() {
        List<MenuBar.Menu<String>> menus = List.of(
            menu("File", item("file", "Open", 0, true)),
            menu("Settings", item("settings", "Theme", 0, true)),
            menu("Help", item("help", "Keys", 0, true))
        );
        Rect bounds = new Rect(2, 1, 10, 6);
        MenuBar.Placement<String> first = placement(menus, MenuBar.State.open(0), bounds);
        MenuBar.State switched = stateChange(first.handle(key(KeyStroke.Key.ARROW_RIGHT), FOCUSED));
        MenuBar.Placement<String> settings = placement(menus, switched, bounds);
        CellBuffer buffer = filled(8, 14);
        settings.paint(new Canvas(buffer), THEME, WidgetState.FOCUSED);
        assertEquals("Settings", cellText(buffer, 1, 2, 8));
        assertTrue(settings.popupBounds().isPresent());
        assertEquals("settings", invokedValue(settings.handle(key(KeyStroke.Key.ENTER), FOCUSED)));

        MenuBar.Placement<String> resized = placement(menus, switched, new Rect(2, 1, 6, 6));
        CellBuffer narrow = filled(8, 10);
        resized.paint(new Canvas(narrow), THEME, WidgetState.FOCUSED);
        assertEquals("Settin", cellText(narrow, 1, 2, 6));
        assertCaptured(resized.handle(key(KeyStroke.Key.ENTER), FOCUSED));
        assertEquals(MenuBar.State.closed(), stateChange(resized.handle(press(1, 2), UNFOCUSED)));

        MenuBar.State helpState = stateChange(settings.handle(key(KeyStroke.Key.ARROW_RIGHT), FOCUSED));
        MenuBar.Placement<String> help = placement(menus, helpState, bounds);
        assertEquals("help", invokedValue(help.handle(key(KeyStroke.Key.ENTER), FOCUSED)));
        MenuBar.Placement<String> tiny = placement(
            List.of(menu("中", item("item", "Item", 0, true))), MenuBar.State.open(0), new Rect(0, 0, 1, 4)
        );
        CellBuffer single = filled(4, 1);
        tiny.paint(new Canvas(single), THEME, WidgetState.FOCUSED);
        assertEquals("…", single.get(0, 0).grapheme());
    }

    @Test
    void reservedTrailingChromeLimitsHeadingsAndHitsWhilePopupsUseTheFullLayer() {
        List<MenuBar.Menu<String>> menus = List.of(
            menu("File", item("file", "Open", 0, true)),
            menu("Settings", item("settings", "A setting!", 0, true))
        );
        Rect bounds = new Rect(2, 1, 14, 6);
        MenuBar.Placement<String> closed = new MenuBar<>(menus, MenuBar.State.closed()).layout(bounds, 6);
        CellBuffer closedBuffer = filled(8, 18);
        closed.paint(new Canvas(closedBuffer), THEME, WidgetState.NORMAL);
        assertEquals("File..........", cellText(closedBuffer, 1, 2, 14));
        assertTrue(closed.hit(1, 2));
        assertFalse(closed.hit(1, 10));
        assertIgnored(closed.handle(press(1, 10), UNFOCUSED));

        MenuBar.Placement<String> settings = new MenuBar<>(menus, MenuBar.State.open(1)).layout(bounds, 6);
        CellBuffer openBuffer = filled(8, 18);
        settings.paint(new Canvas(openBuffer), THEME, WidgetState.FOCUSED);
        assertEquals("Settings......", cellText(openBuffer, 1, 2, 14));
        assertEquals(new Rect(2, 2, 14, 3), settings.popupBounds().orElseThrow());
        assertEquals("settings", invokedValue(settings.handle(key(KeyStroke.Key.ENTER), FOCUSED)));
    }

    @Test
    void aFullyReservedHeadingRowStillAllowsKeyboardUseOfTheOpenPopup() {
        List<MenuBar.Menu<String>> menus = List.of(menu("Menu", item("item", "Go", 0, true)));
        Rect bounds = new Rect(0, 0, 6, 5);
        for (int reserved : List.of(6, Integer.MAX_VALUE)) {
            MenuBar.Placement<String> placement = new MenuBar<>(menus, MenuBar.State.open(0))
                .layout(bounds, reserved);
            CellBuffer buffer = filled(5, 6);
            placement.paint(new Canvas(buffer), THEME, WidgetState.FOCUSED);

            assertEquals("......", cellText(buffer, 0, 0, 6));
            assertEquals(new Rect(0, 1, 6, 3), placement.popupBounds().orElseThrow());
            assertEquals("item", invokedValue(placement.handle(key(KeyStroke.Key.ENTER), FOCUSED)));
        }
        assertThrows(IllegalArgumentException.class,
            () -> new MenuBar<>(menus, MenuBar.State.closed()).layout(bounds, -1));
    }

    @Test
    void popupWheelPreservesManualViewportAndKeyboardRevealsSelectionBeforeInvoking() {
        List<MenuBar.Menu<String>> menus = longMenu();
        Rect bounds = new Rect(1, 1, 20, 7);
        MenuBar.Placement<String> initial = placement(menus, MenuBar.State.open(0), bounds);
        Rect popup = initial.popupBounds().orElseThrow();
        MenuBar.State scrolled = stateChange(initial.handle(
            pointer(popup.y() + 1, popup.x() + 1, PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE),
            UNFOCUSED
        ));
        assertEquals(3, scrolled.firstVisibleEntryIndex());
        assertEquals(0, scrolled.selectedEntryIndex());
        MenuBar.Placement<String> rebuilt = placement(menus, scrolled, bounds);
        assertEquals(scrolled, rebuilt.state());
        assertTrue(rebuilt.cursorPosition(FOCUSED).isEmpty());
        MenuBar.State revealed = stateChange(rebuilt.handle(key(KeyStroke.Key.ENTER), FOCUSED));
        assertEquals(new MenuBar.State(0, 0, 0), revealed);
        assertEquals("item0", invokedValue(placement(menus, revealed, bounds).handle(
            key(KeyStroke.Key.ENTER), FOCUSED
        )));
        MenuBar.State moved = stateChange(rebuilt.handle(key(KeyStroke.Key.ARROW_DOWN), FOCUSED));
        assertEquals(new MenuBar.State(0, 1, 1), moved);
        assertCaptured(initial.handle(
            pointer(bounds.bottomExclusive() - 1, bounds.rightExclusive() - 1,
                PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE), UNFOCUSED
        ));
    }

    @Test
    void popupScrollbarUsesSharedTrackDraggingAndReleasesOutsideClipOrWhileDisabled() {
        List<MenuBar.Menu<String>> menus = longMenu();
        Rect bounds = new Rect(1, 1, 20, 7);
        MenuBar.Placement<String> initial = placement(menus, MenuBar.State.open(0), bounds);
        Scrollbar bar = initial.scrollbar().orElseThrow();
        MenuBar.State grabbed = stateChange(initial.handle(press(bar.track().y(), bar.track().x()), UNFOCUSED));
        assertTrue(grabbed.drag() != null);
        MenuBar.Placement<String> dragging = placement(menus, grabbed, bounds);
        MenuBar.State stationary = stateChange(dragging.handle(
            pointer(bar.track().y(), bar.track().x(), PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT), UNFOCUSED
        ));
        assertEquals(grabbed, stationary);
        MenuBar.State bottom = stateChange(dragging.handle(
            pointer(100, 100, PointerEvent.Kind.DRAG, PointerEvent.Button.LEFT), UNFOCUSED
        ));
        assertEquals(8, bottom.firstVisibleEntryIndex());
        MenuBar.Placement<String> bottomPlacement = placement(menus, bottom, bounds);
        FocusContext disabled = new FocusContext(false, false, FocusSpace.root(ROOT_CLIP));
        MenuBar.State released = stateChange(bottomPlacement.handle(
            pointer(100, 100, PointerEvent.Kind.RELEASE, PointerEvent.Button.NONE), disabled
        ));
        assertEquals(new MenuBar.State(0, 0, 8), released);
        assertEquals(released, bottom.cancelPointerInteraction());
        assertSame(released, released.cancelPointerInteraction());
        assertEquals(released, stateChange(bottomPlacement.handle(new ResizeEvent(20, 80), disabled)));
        MenuBar.State page = stateChange(initial.handle(
            press(bar.track().bottomExclusive() - 1, bar.track().x()), UNFOCUSED
        ));
        assertEquals(new MenuBar.State(0, 0, 3), page);
        MenuBar.Placement<String> tiny = placement(menus, grabbed, new Rect(1, 1, 20, 4));
        assertTrue(tiny.scrollbar().isEmpty());
        assertTrue(tiny.state().drag() == null);
    }

    @Test
    void scrollbarInheritsPopupBodyBackgroundAndFrameForeground() {
        MenuBar.Placement<String> placement = placement(longMenu(), MenuBar.State.open(0), new Rect(0, 0, 20, 7));
        CellStyle body = new CellStyle(CellColor.CYAN, CellColor.BLUE, false, false);
        CellStyle frame = new CellStyle(CellColor.BRIGHT_WHITE, CellColor.RED, true, false);
        CellStyle expected = new CellStyle(frame.fg(), body.bg(), frame.bold(), frame.underline());
        Theme theme = role -> {
            if (WidgetThemeKeys.MENU_ITEM.equals(role)) return body;
            if (WidgetThemeKeys.MENU_POPUP.equals(role)) return frame;
            return THEME.style(role);
        };
        CellBuffer buffer = filled(8, 22);
        placement.paint(new Canvas(buffer), theme, WidgetState.FOCUSED);
        Rect track = placement.scrollbar().orElseThrow().track();
        assertEquals("█", buffer.get(track.y(), track.x()).grapheme());
        assertEquals(expected, buffer.get(track.y(), track.x()).style());
        assertEquals("│", buffer.get(track.bottomExclusive() - 1, track.x()).grapheme());
        assertEquals(expected, buffer.get(track.bottomExclusive() - 1, track.x()).style());
        assertEquals(frame, buffer.get(track.y(), track.x() + 1).style());
    }

    private static List<MenuBar.Menu<String>> longMenu() {
        List<MenuBar.Entry<String>> entries = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            entries.add(item("item" + index, "Item " + index, 0, true));
        }
        return List.of(new MenuBar.Menu<>("Menu", 0, entries));
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
