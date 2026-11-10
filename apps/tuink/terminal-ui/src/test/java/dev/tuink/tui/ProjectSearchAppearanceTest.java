package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.widget.ListView;
import dev.tuiframe.widget.WidgetThemeKeys;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.project.search.ProjectSearch;
import dev.tuink.tui.style.UiStyles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSearchAppearanceTest {
    @TempDir Path root;
    private ProjectSearchPanel results(String text, int... ranges) {
        var panel = new ProjectSearchPanel(); panel.query.set("needle");
        var target = new ProjectSearch.Target("note", "src/Note.md", null, null, 1,
            new ProjectSearch.ReadVersion(null, DocumentOpenMode.UTF_8));
        var matches = new ArrayList<ProjectSearch.Match>();
        for (int i = 0; i < ranges.length; i += 2) matches.add(new ProjectSearch.Match(target, 0, ranges[i], ranges[i + 1], text, 0));
        panel.accept(new ProjectSearch.Matches(matches));
        panel.accept(new ProjectSearch.Finished(ProjectSearch.Outcome.COMPLETE, 1, matches.size(), 0, ""));
        panel.listState = new ListView.State(1, 0);
        return panel;
    }
    private ProjectSearchView.Placement selected(ProjectSearchPanel panel, Rect bounds) {
        var placed = panel.place(bounds, true, true, root);
        panel.focus.focus(placed.scope(), ProjectSearchPanel.Target.RESULTS);
        return panel.place(bounds, true, true, root);
    }
    private String text(CellBuffer cells, int row) {
        StringBuilder text = new StringBuilder();
        for (int col = 0; col < cells.cols(); col++) if (!cells.get(row, col).continuation()) text.append(cells.get(row, col).grapheme());
        return text.toString();
    }
    @Test void allVisibleOccurrencesAreHighlightedAndUnicodeControlsAreSafelyPresented() {
        String source = "é 中\tneedle needle \u001b[31m";
        int first = source.indexOf("needle"), second = source.lastIndexOf("needle");
        var panel = results(source, first, first + 6, second, second + 6);
        var placed = selected(panel, new Rect(0, 0, 70, 18));
        var row = placed.resultRows().stream().filter(line -> line.index() == 1).findFirst().orElseThrow();
        assertEquals("needle", row.parts().stream().filter(part -> part.role() == ProjectSearchResults.Role.CURRENT).map(ProjectSearchResults.Part::text).reduce("", String::concat));
        assertEquals("needle", row.parts().stream().filter(part -> part.role() == ProjectSearchResults.Role.MATCH).map(ProjectSearchResults.Part::text).reduce("", String::concat));
        var app = new TerminalEditorApp();
        var theme = app.explorerWidgetTheme(true); var cells = new CellBuffer(20, 75);
        placed.paint(new Canvas(cells), theme);
        int y = placed.list().contentBounds().y() + 1;
        String rendered = text(cells, y);
        assertTrue(rendered.contains("é 中\\tneedle needle \\u001B[31m"), rendered);
        assertFalse(rendered.contains("\u001b"));
        int current = 0, other = 0;
        for (int col = 0; col < cells.cols(); col++) {
            if (cells.get(y, col).style().equals(theme.resolveStyle(WidgetThemeKeys.SEARCH_CURRENT_MATCH))) current++;
            if (cells.get(y, col).style().equals(theme.resolveStyle(WidgetThemeKeys.SEARCH_MATCH))) other++;
        }
        assertEquals(6, current); assertEquals(6, other);
    }
    @Test void emptyAndIntraGraphemeMatchesHaveVisibleMarkers() {
        for (var panel : List.of(results("", 0, 0), results("é🙂", 1, 1), results("tail", 4, 4))) {
            var placed = selected(panel, new Rect(0, 0, 32, 18));
            var row = placed.resultRows().stream().filter(line -> line.index() == 1).findFirst().orElseThrow();
            assertTrue(row.parts().stream().anyMatch(part -> part.text().equals("▏") && part.role() == ProjectSearchResults.Role.CURRENT));
        }
    }
    @Test void highlightAndSelectionStayDistinguishableInEveryThemeAndWithoutColor() {
        for (var value : UiStyles.Theme.values()) for (boolean color : List.of(true, false)) {
            var app = new TerminalEditorApp(); app.applyTheme(value);
            var theme = app.explorerWidgetTheme(color);
            var match = theme.resolveStyle(WidgetThemeKeys.SEARCH_MATCH);
            var current = theme.resolveStyle(WidgetThemeKeys.SEARCH_CURRENT_MATCH);
            assertNotEquals(match, current);
            assertNotEquals(match, theme.resolveStyle(WidgetThemeKeys.MODAL_BODY));
            if (!color) { assertTrue(match.bold()); assertTrue(current.bold()); assertTrue(current.underline()); }
        }
    }
    @Test void inactiveSelectedMatchKeepsBlackInkAndUnderlinesOccurrencesWithoutBrighteningThem() {
        for (var value : UiStyles.Theme.values()) for (boolean color : List.of(true, false)) {
            var app = new TerminalEditorApp(); app.applyTheme(value);
            var panel = results("before needle after", 7, 13);
            var placed = panel.place(new Rect(0, 0, 70, 18), false, true, root);
            var cells = new CellBuffer(18, 70);
            placed.paint(new Canvas(cells), app.explorerWidgetTheme(color));
            int y = placed.list().contentBounds().y() + 1;
            int match = text(cells, y).indexOf("needle");
            assertTrue(match >= 0);
            for (int column = placed.list().contentBounds().x(); column < placed.list().contentBounds().rightExclusive(); column++) {
                var style = cells.get(y, column).style();
                assertEquals(CellColor.BLACK, style.fg());
                assertEquals(CellColor.GRAY_188, style.bg());
                assertFalse(style.bold(), "Bold ANSI black can appear gray instead of black");
                assertFalse(style.inverse());
                assertEquals(column >= match && column < match + 6, style.underline());
            }
        }
    }
    @Test void statusAndHelpHaveFullWidthColorBandsWhileOrdinaryLabelsKeepThePaletteBody() {
        var panel = results("needle", 0, 6);
        var app = new TerminalEditorApp(); var theme = app.explorerWidgetTheme(true);
        var placed = selected(panel, new Rect(0, 0, 70, 18));
        var cells = new CellBuffer(18, 70); placed.paint(new Canvas(cells), theme);
        for (int column = 1; column < 69; column++) {
            assertEquals(app.styles.windows().notifySuccess(), cells.get(placed.status().getFirst().y(), column).style());
            assertEquals(app.styles.windows().notifyInfo(), cells.get(placed.footer().y(), column).style());
        }
        assertEquals(app.styles.windows().sidebarBody(), cells.get(0, 1).style());
        panel.error = "Invalid regular expression at 1: unclosed class";
        placed = panel.place(new Rect(0, 0, 70, 18), true, true, root);
        placed.paint(new Canvas(cells), theme);
        for (var line : placed.status()) for (int column = 1; column < 69; column++)
            assertEquals(app.styles.windows().notifyError(), cells.get(line.y(), column).style());
    }
    @Test void basenameEmphasisPreservesLiteralBackslashesAndEscapedFileIdentifiers() {
        for (String name : List.of("a\\b.md", "tab\t.md", "file:literal.md")) {
            Path path = root.resolve(name);
            String label = dev.tuiframe.explorer.file.PathLabels.relative(root, path);
            var target = new ProjectSearch.Target("file", label, dev.tuink.storage.jvm.JvmDocumentStorage.resource(path), null, 1,
                new ProjectSearch.ReadVersion(null, DocumentOpenMode.UTF_8));
            var panel = new ProjectSearchPanel(); panel.query.set("x");
            panel.accept(new ProjectSearch.Matches(List.of(new ProjectSearch.Match(target, 0, 0, 1, "x", 0))));
            var placed = panel.place(new Rect(0, 0, 100, 18), true, true, root);
            var row = placed.resultRows().getFirst();
            assertEquals(dev.tuiframe.explorer.file.PathLabels.name(path), row.parts().stream()
                .filter(part -> part.role() == ProjectSearchResults.Role.FILE).map(ProjectSearchResults.Part::text).reduce("", String::concat));
        }
    }
    @Test void layoutAndPaintingStayInsideThePaletteAtSmallSizesWithExpandedFilters() {
        for (int width : List.of(0, 1, 16, 24, 30, 47, 70)) for (int height = 0; height <= 24; height++)
            for (boolean filters : List.of(false, true)) for (boolean hints : List.of(false, true)) {
                var panel = new ProjectSearchPanel(); panel.hintsVisible = hints; panel.filters = filters; panel.query.set("["); panel.mode = SearchOptions.MatchMode.REGEX;
                panel.error = "Invalid regular expression at 1: Unclosed character class";
                Rect bounds = new Rect(2, 3, width, height);
                var placed = panel.place(bounds, true, true, root);
                for (var target : placed.scope().targets()) assertTrue(bounds.contains(target.placement().bounds()), target.id().toString());
                for (var line : placed.status()) for (var target : placed.scope().targets()) if (target.id() != ProjectSearchPanel.Target.RESULTS)
                    assertFalse(target.placement().bounds().contains(target.placement().bounds().x(), line.y()), target.id().toString());
                var cells = new CellBuffer(30, 80); cells.fill(new Cell("·", CellStyle.DEFAULT));
                placed.paint(new Canvas(cells), role -> null);
                for (int row = 0; row < cells.rows(); row++) for (int col = 0; col < cells.cols(); col++) if (!bounds.contains(col, row))
                    assertEquals("·", cells.get(row, col).grapheme(), width + "x" + height);
            }
    }
    @Test void primaryActionKeepsItsHitAreaWhileSwitchingBetweenSearchStopAndRefresh() {
        for (int width : List.of(16, 24, 30, 47)) {
            var panel = new ProjectSearchPanel(); panel.query.set("needle");
            Rect bounds = new Rect(0, 0, width, 24);
            var search = panel.place(bounds, true, true, root).scope().target(ProjectSearchPanel.Target.FIND).orElseThrow().placement().bounds();
            panel.pending = true;
            assertEquals(search, panel.place(bounds, true, true, root).scope().target(ProjectSearchPanel.Target.FIND).orElseThrow().placement().bounds());
            panel.pending = false; panel.stale = true;
            assertEquals(search, panel.place(bounds, true, true, root).scope().target(ProjectSearchPanel.Target.FIND).orElseThrow().placement().bounds());
        }
    }
    @Test void primaryActionUsesColoredTextAndOnlyFocusInvertsItsBackground() {
        for (var selectedTheme : UiStyles.Theme.values()) for (boolean busy : List.of(false, true)) {
            var app = new TerminalEditorApp(); app.applyTheme(selectedTheme);
            var theme = app.explorerWidgetTheme(true);
            var panel = new ProjectSearchPanel(); panel.query.set("needle"); panel.pending = busy;
            var bounds = new Rect(0, 0, 70, 18);
            var placed = panel.place(bounds, true, true, root);
            var button = placed.scope().target(ProjectSearchPanel.Target.FIND).orElseThrow().placement().bounds();
            var cells = new CellBuffer(18, 70); placed.paint(new Canvas(cells), theme);
            var normal = cells.get(button.y(), button.x()).style();
            assertEquals(theme.resolveStyle(WidgetThemeKeys.MODAL_BODY).bg(), normal.bg());
            assertFalse(normal.inverse()); assertNotEquals(normal.fg(), normal.bg());
            panel.focus.focus(placed.scope(), ProjectSearchPanel.Target.FIND);
            panel.place(bounds, true, true, root).paint(new Canvas(cells), theme);
            assertEquals(normal.inverted(), cells.get(button.y(), button.x()).style());
            panel.focus.focus(placed.scope(), ProjectSearchPanel.Target.QUERY);
            panel.place(bounds, true, true, root).paint(new Canvas(cells), theme);
            assertEquals(normal, cells.get(button.y(), button.x()).style());
        }
    }
    @Test void hidingHelpReclaimsAResultRowAndPreservesStatusAndErrorMessages() {
        var panel = results("needle", 0, 6); var bounds = new Rect(0, 0, 70, 18);
        var before = panel.place(bounds, true, true, root);
        panel.hintsVisible = false;
        var hidden = panel.place(bounds, true, true, root);
        assertNull(hidden.footer());
        assertEquals(before.list().bounds().height() + 1, hidden.list().bounds().height());
        assertEquals(before.status(), hidden.status());
        panel.error = "Invalid regular expression";
        assertEquals(WidgetThemeKeys.NOTICE_ERROR, panel.place(bounds, true, true, root).status().getFirst().role());
    }
}
