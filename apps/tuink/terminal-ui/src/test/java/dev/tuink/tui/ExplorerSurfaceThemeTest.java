package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.TreeView;
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.widget.WidgetThemeKeys;
import dev.tuink.tui.style.UiStyles;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ExplorerSurfaceThemeTest {
    private static final Set<CellColor> NEUTRAL = Set.of(CellColor.BLACK, CellColor.WHITE,
        CellColor.BRIGHT_BLACK, CellColor.BRIGHT_WHITE, CellColor.DARK_GRAY,
        CellColor.GRAY_028, CellColor.GRAY_128, CellColor.GRAY_158, CellColor.GRAY_188,
        CellColor.GRAY_218, CellColor.GRAY_238);
    private static final Rect TREE_BOUNDS = new Rect(1, 1, 18, 6);

    static Stream<Arguments> themesAndColorModes() {
        return Arrays.stream(UiStyles.Theme.values()).flatMap(theme ->
            Stream.of(Arguments.of(theme, true), Arguments.of(theme, false)));
    }

    @ParameterizedTest
    @MethodSource("themesAndColorModes")
    void sameTreeInheritsPickerOrEditorSurface(UiStyles.Theme selectedTheme, boolean colorEnabled) {
        var app = app(selectedTheme);
        var styles = app.styles.windows();
        var sidebar = app.explorerWidgetTheme(colorEnabled);
        Theme pickerRoles = key -> app.resolveFileTreeWidgetStyle(key, colorEnabled);
        var picker = pickerRoles.withSurface(styles.dialog(), styles.dialogBorder());
        var tree = tree();

        var dark = paint(tree, sidebar, false);
        var light = paint(tree, picker, false);

        assertEquals(styles.renderBody(), styles.sidebarBody(),
            "Sidebar follows the editor's resolved foreground and background");
        assertEquals(CellColor.BLACK, styles.dialog().fg());
        assertEquals(CellColor.BRIGHT_WHITE, styles.dialog().bg());
        assertNotEquals(styles.dialog(), styles.sidebarBody());
        // The second row is not selected. Its disclosure mark, label and padding
        // must use the same inherited foreground/background in either context.
        for (int column : new int[] {1, 3, 10}) {
            assertEquals(styles.sidebarBody(), dark.get(2, column).style());
            assertEquals(styles.dialog(), light.get(2, column).style());
        }
        assertScrollbarsInherit(tree, dark, styles.sidebarBody(), styles.sidebarBorder());
        assertScrollbarsInherit(tree, light, styles.dialog(), styles.dialogBorder());
    }

    @ParameterizedTest
    @MethodSource("themesAndColorModes")
    void sidebarSelectionRetainsItsContextAndClearlyShowsKeyboardFocus(UiStyles.Theme selectedTheme,
        boolean colorEnabled) {
        var app = app(selectedTheme);
        var theme = app.explorerWidgetTheme(colorEnabled);
        var tree = tree();
        var inactive = paint(tree, theme, false);
        var active = paint(tree, theme, true);
        var inactiveStyle = inactive.get(1, 3).style();
        var activeStyle = active.get(1, 3).style();

        assertEquals(theme.resolveStyle(WidgetThemeKeys.LIST_ITEM_SELECTED), inactiveStyle);
        assertEquals(theme.resolveStyle(WidgetThemeKeys.LIST_ITEM_SELECTED_FOCUSED), activeStyle);
        assertNotEquals(app.styles.windows().sidebarBody(), inactiveStyle);
        assertNotEquals(inactiveStyle, activeStyle, "Keyboard focus must be visible independently of selection");
        assertNotEquals(inactiveStyle.fg(), inactiveStyle.bg());
        assertNotEquals(activeStyle.fg(), activeStyle.bg());
        if (!colorEnabled || selectedTheme == UiStyles.Theme.MONOCHROME) {
            assertTrue(NEUTRAL.contains(activeStyle.fg()));
            assertTrue(NEUTRAL.contains(activeStyle.bg()));
        }
        assertEquals(app.styles.windows().sidebarBody(), active.get(2, 3).style(),
            "Focusing a selected row must not replace the rest of the surface");
    }

    @ParameterizedTest
    @MethodSource("themesAndColorModes")
    void sidebarControlsKeepTheEditorSurfaceAndVisibleFocus(UiStyles.Theme selectedTheme,
        boolean colorEnabled) {
        var app = app(selectedTheme);
        var theme = app.explorerWidgetTheme(colorEnabled);
        var body = app.styles.windows().renderBody();
        for (var role : List.of(WidgetThemeKeys.BUTTON, WidgetThemeKeys.BUTTON_DISABLED,
            WidgetThemeKeys.TEXT_FIELD, WidgetThemeKeys.TEXT_FIELD_FOCUSED, WidgetThemeKeys.TEXT_FIELD_DISABLED)) {
            assertEquals(body, theme.resolveStyle(role));
        }
        assertNotEquals(body, theme.resolveStyle(WidgetThemeKeys.BUTTON_FOCUSED));
        assertNotEquals(body, theme.resolveStyle(WidgetThemeKeys.TEXT_FIELD_SELECTION));
        assertEquals(body.bg(), theme.resolveStyle(WidgetThemeKeys.MODAL_TITLE).bg());
    }

    @ParameterizedTest
    @MethodSource("themesAndColorModes")
    void sidebarHeaderDividerAndEmptyAreaUseTheEditorSurface(UiStyles.Theme selectedTheme,
        boolean colorEnabled) {
        var app = app(selectedTheme);
        try (var explorer = new ExplorerState()) {
            explorer.visible = true;
            var bounds = new Rect(0, 1, 80, 8);
            var placement = explorer.place(explorer.prepareLayout(bounds), new Rect(0, 0, 80, 10), true);
            var cells = new CellBuffer(10, 80);
            placement.paint(new Canvas(cells), app.explorerWidgetTheme(colorEnabled));
            var body = app.styles.windows().sidebarBody();

            assertEquals(body, cells.get(4, 2).style(), "Empty sidebar padding inherits the body");
            assertEquals(body, cells.get(2, 2).style(), "The Open folder action shares its containing surface");
            assertEquals(body.bg(), cells.get(1, 2).style().bg(), "Header background belongs to the sidebar");
            assertEquals(body.bg(), cells.get(4, placement.divider().bounds().x()).style().bg(),
                "The divider must not leak the light dialog background");
        }
    }

    private static TerminalEditorApp app(UiStyles.Theme theme) {
        var app = new TerminalEditorApp();
        app.theme = theme;
        app.styles = UiStyles.forTheme(theme);
        return app;
    }

    @ParameterizedTest
    @MethodSource("themesAndColorModes")
    void dividerUsesTheThemeAccentWithoutBoldForEitherFocusState(UiStyles.Theme selectedTheme,
        boolean colorEnabled) {
        var app = app(selectedTheme);
        var expected = app.styles.windows().sidebarBorder();
        assertEquals(switch (selectedTheme) {
            case CYAN -> CellColor.CYAN;
            case BLUE -> CellColor.BLUE;
            case MONOCHROME -> CellColor.GRAY_188;
        }, expected.fg());
        assertEquals(app.styles.windows().renderBody().bg(), expected.bg());
        assertFalse(expected.bold(), "The border color is independent of terminal bold colors");
        try (var explorer = new ExplorerState()) {
            explorer.visible = true;
            for (boolean focused : new boolean[] {false, true}) {
                explorer.focused = focused;
                var placement = explorer.place(explorer.prepareLayout(new Rect(0, 1, 80, 8)),
                    new Rect(0, 0, 80, 10), true);
                var cells = new CellBuffer(10, 80);
                placement.paint(new Canvas(cells), app.explorerWidgetTheme(colorEnabled));
                assertEquals(expected, cells.get(4, placement.divider().bounds().x()).style(),
                    "Focusing the explorer must retain the theme border without adding bold");
            }
        }
    }

    private static TreeView.Placement<Integer> tree() {
        List<TreeView.Item<Integer>> rows = IntStream.range(0, 14)
            .mapToObj(index -> new TreeView.Item<>(index, "folder-%02d-with-a-long-name".formatted(index),
                0, TreeView.Kind.LEAF)).toList();
        return new TreeView<>(rows, TreeView.State.initial()).layout(TREE_BOUNDS);
    }

    private static CellBuffer paint(TreeView.Placement<Integer> tree, Theme theme, boolean focused) {
        var cells = new CellBuffer(9, 22);
        tree.paint(new Canvas(cells), theme, new WidgetState(focused, true));
        return cells;
    }

    private static void assertScrollbarsInherit(TreeView.Placement<Integer> tree, CellBuffer cells,
        CellStyle body, CellStyle border) {
        var vertical = tree.verticalScrollbarBounds().orElseThrow();
        var horizontal = tree.horizontalScrollbarBounds().orElseThrow();
        for (var point : List.of(new int[] {vertical.y(), vertical.x()},
                new int[] {vertical.bottomExclusive() - 1, vertical.x()},
                new int[] {horizontal.y(), horizontal.x()})) {
            var style = cells.get(point[0], point[1]).style();
            assertEquals(body.bg(), style.bg());
            assertEquals(border.fg(), style.fg());
        }
    }
}
