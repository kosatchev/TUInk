package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The same control can be embedded in different surfaces without its own palette. */
class SurfaceInheritanceTest {
    private static final CellStyle LIGHT = new CellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, false, false);
    private static final CellStyle DARK = new CellStyle(CellColor.BRIGHT_WHITE, CellColor.BLACK, false, false);
    private static final CellStyle BORDER = new CellStyle(CellColor.CYAN, CellColor.RED, true, false);
    private static final CellStyle SELECTED = new CellStyle(CellColor.BLACK, CellColor.CYAN, false, false);
    private static final CellStyle FOCUSED_SELECTED = new CellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE, false, false);
    private static final CellStyle ROW_OVERRIDE = new CellStyle(CellColor.YELLOW, CellColor.BLUE, false, false);
    private static final CellStyle DISABLED_OVERRIDE = new CellStyle(CellColor.BRIGHT_BLACK, CellColor.BLUE, false, false);
    private static final Rect BOUNDS = new Rect(0, 0, 10, 3);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryAndDisabledRowsInheritTheSurfaceWhileSelectionUsesInverseOrAccent(boolean tree) {
        var placement = placement(tree);
        Theme empty = role -> null;
        for (CellStyle body : List.of(LIGHT, DARK)) {
            Theme theme = empty.withSurface(body, BORDER);
            for (WidgetState state : List.of(WidgetState.NORMAL, WidgetState.FOCUSED, WidgetState.DISABLED)) {
                var buffer = paint(placement, theme, state);
                var selected = !state.enabled() ? body : state.focused()
                    ? new CellStyle(CellColor.BLACK, CellColor.CYAN, true, false) : body.inverted();
                assertEquals(selected, buffer.get(0, 0).style(), "Only focused selection uses the accent");
                assertEquals(body, buffer.get(1, 0).style(), "Ordinary row text inherits the same surface");
                assertEquals(body, buffer.get(1, 8).style(), "Unused row cells retain the parent background");
                assertEquals(new CellStyle(BORDER.fg(), body.bg(), BORDER.bold(), BORDER.underline()),
                    buffer.get(1, 9).style(), "Scrollbar inherits border ink and body background");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectionRolesOverrideOnlyTheirRowsAndKeepTheOrdinarySurface(boolean tree) {
        Theme selection = role -> {
            if (WidgetThemeKeys.LIST_ITEM_SELECTED.equals(role)) return SELECTED;
            if (WidgetThemeKeys.LIST_ITEM_SELECTED_FOCUSED.equals(role)) return FOCUSED_SELECTED;
            return null;
        };
        var placement = placement(tree);
        for (CellStyle body : List.of(LIGHT, DARK)) {
            Theme theme = selection.withSurface(body, BORDER);
            var unfocused = paint(placement, theme, WidgetState.NORMAL);
            var focused = paint(placement, theme, WidgetState.FOCUSED);
            var disabled = paint(placement, theme, WidgetState.DISABLED);
            assertEquals(SELECTED, unfocused.get(0, 0).style());
            assertEquals(FOCUSED_SELECTED, focused.get(0, 0).style());
            assertEquals(body, focused.get(1, 0).style());
            assertEquals(body, disabled.get(0, 0).style(), "Disabled controls do not apply selection styles");
            assertEquals(body, disabled.get(1, 0).style());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitOrdinaryAndDisabledRolesKeepPrecedenceOverTheContainingSurface(boolean tree) {
        var placement = placement(tree);
        Theme ordinary = role -> WidgetThemeKeys.LIST_ITEM.equals(role) ? ROW_OVERRIDE : null;
        Theme disabled = role -> WidgetThemeKeys.LIST_ITEM_DISABLED.equals(role)
            ? DISABLED_OVERRIDE : ordinary.style(role);
        for (CellStyle body : List.of(LIGHT, DARK)) {
            assertEquals(ROW_OVERRIDE, paint(placement, ordinary.withSurface(body, BORDER), WidgetState.NORMAL)
                .get(1, 0).style());
            assertEquals(ROW_OVERRIDE, paint(placement, ordinary.withSurface(body, BORDER), WidgetState.DISABLED)
                .get(1, 0).style(), "Missing disabled role falls back to the explicit ordinary role");
            assertEquals(DISABLED_OVERRIDE, paint(placement, disabled.withSurface(body, BORDER), WidgetState.DISABLED)
                .get(1, 0).style());
        }
    }

    private static PlacedWidget placement(boolean tree) {
        if (tree) {
            return new TreeView<>(IntStream.range(0, 8)
                .mapToObj(index -> new TreeView.Item<>(index, "row-" + index, 0, TreeView.Kind.LEAF, ""))
                .toList(), new TreeView.State(0, 0, 0)).layout(BOUNDS);
        }
        return new ListView(8, index -> "row-" + index, new ListView.State(0, 0)).layout(BOUNDS);
    }

    private static CellBuffer paint(PlacedWidget placement, Theme theme, WidgetState state) {
        var buffer = new CellBuffer(BOUNDS.height(), BOUNDS.width());
        placement.paint(new Canvas(buffer), theme, state);
        return buffer;
    }
}
