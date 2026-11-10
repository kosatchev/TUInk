package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.widget.ListView;
import dev.tuiframe.widget.TreeView;
import dev.tuiframe.widget.WidgetState;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.project.search.ProjectSearch;
import dev.tuink.tui.style.UiStyles;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PaletteSelectionAppearanceTest {
    static Stream<Arguments> themesAndColorModes() {
        return Arrays.stream(UiStyles.Theme.values()).flatMap(theme ->
            Stream.of(Arguments.of(theme, true), Arguments.of(theme, false)));
    }

    @ParameterizedTest
    @MethodSource("themesAndColorModes")
    void folderAndSearchGroupShareColorsThroughExpansionFocusAndDisabledStates(UiStyles.Theme selectedTheme,
        boolean colorEnabled) {
        var app = new TerminalEditorApp();
        app.applyTheme(selectedTheme);
        var theme = app.explorerWidgetTheme(colorEnabled);
        var bounds = new Rect(0, 0, 40, 5);
        for (boolean expanded : List.of(false, true)) {
            var panel = new ProjectSearchPanel();
            for (String name : List.of("selected", "ordinary")) {
                var target = new ProjectSearch.Target(name, "src/" + name, null, null, 1,
                    new ProjectSearch.ReadVersion(null, DocumentOpenMode.UTF_8));
                panel.accept(new ProjectSearch.Matches(List.of(new ProjectSearch.Match(target, 0, 0, 6, "needle", 0))));
                if (!expanded) panel.collapsed.add(name);
            }
            panel.rebuild();
            var kind = expanded ? TreeView.Kind.EXPANDED : TreeView.Kind.COLLAPSED;
            var items = panel.rows.stream().map(row -> new TreeView.Item<>(row.group(),
                row.match() == null ? row.group() : "needle", row.match() == null ? 1 : 2,
                row.match() == null ? kind : TreeView.Kind.LEAF)).toList();
            var tree = new TreeView<>(items, TreeView.State.initial()).layout(bounds);
            var list = new ListView(panel.rows.size(), ignored -> "", ListView.State.initial()).layout(bounds);
            var lines = ProjectSearchResults.snapshot(panel, list);
            int ordinaryRow = expanded ? 2 : 1;
            for (var state : List.of(WidgetState.NORMAL, WidgetState.FOCUSED, WidgetState.DISABLED)) {
                boolean enabled = state.enabled(), focused = state.focused();
                var folders = new CellBuffer(5, 40);
                var results = new CellBuffer(5, 40);
                tree.paint(new Canvas(folders), theme, state);
                ProjectSearchResults.paint(new Canvas(results), theme, list, lines, focused, enabled);
                var disabled = new CellStyle(CellColor.GRAY_128, CellColor.GRAY_188, false, false);
                var selected = !enabled ? disabled : !focused
                    ? new CellStyle(CellColor.BLACK, CellColor.GRAY_188, false, false)
                    : colorEnabled && selectedTheme != UiStyles.Theme.MONOCHROME
                        ? new CellStyle(CellColor.BRIGHT_WHITE, selectedTheme == UiStyles.Theme.CYAN ? CellColor.CYAN : CellColor.BLUE, true, false)
                        : new CellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, false, !colorEnabled);
                var ordinary = enabled ? new CellStyle(CellColor.GRAY_188, CellColor.BLACK, false, false) : disabled;
                assertEquals(expanded ? "▼" : "▶", folders.get(0, 2).grapheme());
                assertEquals(expanded ? "▾" : "▸", results.get(0, 0).grapheme());
                // Bold black can become gray in terminals that brighten ANSI colors.
                for (int column : new int[] {2, 4}) assertEquals(selected, folders.get(0, column).style());
                for (int column : new int[] {0, 2}) assertEquals(selected, results.get(0, column).style());
                // Include disclosure marks, labels, counts, parent paths and row padding.
                for (int column = 0; column < bounds.width(); column++) {
                    assertColors(selected, folders.get(0, column).style());
                    assertColors(selected, results.get(0, column).style());
                    assertColors(ordinary, folders.get(ordinaryRow, column).style());
                    assertColors(ordinary, results.get(ordinaryRow, column).style());
                }
            }
        }
    }

    private static void assertColors(CellStyle expected, CellStyle actual) {
        assertEquals(expected.fg(), actual.fg());
        assertEquals(expected.bg(), actual.bg());
        assertFalse(actual.inverse(), "Selection colors must not depend on terminal inversion");
    }
}
