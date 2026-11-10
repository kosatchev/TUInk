package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.capability.ColorDepth;
import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.jline.presentation.ScreenPresenter;
import dev.tuiframe.screen.Frame;
import dev.tuiframe.screen.ScreenCursor;
import java.io.PrintWriter;
import java.io.StringWriter;
import dev.tuiframe.screen.ScrollHint;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlColorIntegrationTest {
    @TempDir Path directory;
    @Test void colorlessPaletteSelectionShowsFocusInTheActualTerminalOutput() {
        for (var value : dev.tuink.tui.style.UiStyles.Theme.values()) {
            var app = new TerminalEditorApp(); app.applyTheme(value);
            var theme = app.explorerWidgetTheme(false);
            var inactive = new CellBuffer(1, 1);
            inactive.set(0, 0, "▶", theme.resolveStyle(dev.tuiframe.widget.WidgetThemeKeys.LIST_ITEM_SELECTED));
            var active = new CellBuffer(1, 1);
            active.set(0, 0, "▶", theme.resolveStyle(dev.tuiframe.widget.WidgetThemeKeys.LIST_ITEM_SELECTED_FOCUSED));
            var output = new StringWriter();
            var presenter = new ScreenPresenter(new PrintWriter(output), TerminalCapabilities.MINIMAL);
            presenter.present(new Frame(inactive, ScreenCursor.hidden(), ScrollHint.none()));
            output.getBuffer().setLength(0);
            presenter.present(new Frame(active, ScreenCursor.hidden(), ScrollHint.none()));
            assertTrue(output.toString().contains("\u001b[39;49;22;4;7m"),
                "Focus must remain underlined when white and gray collapse to the same monochrome surface");
        }
    }
    private static void grayField(CellStyle style) {
        assertEquals(CellColor.GRAY_028, style.fg());
        assertEquals(CellColor.GRAY_188, style.bg());
        assertFalse(style.inverse(), "The gray background must be an explicit color");
        var cell = new CellBuffer(1, 1); cell.set(0, 0, "x", style);
        for (var depth : List.of(ColorDepth.ANSI_16, ColorDepth.ANSI_256, ColorDepth.TRUECOLOR)) {
            var output = new StringWriter();
            new ScreenPresenter(new PrintWriter(output), new TerminalCapabilities(depth, false, false, false))
                .present(new Frame(cell, ScreenCursor.hidden(), ScrollHint.none()));
            String ansi = output.toString();
            assertTrue(ansi.contains(switch (depth) {
                case ANSI_16 -> "30;47;";
                case ANSI_256 -> "38;5;234;48;5;250;";
                case TRUECOLOR -> "38;2;28;28;28;48;2;188;188;188;";
                default -> throw new AssertionError();
            }), ansi);
        }
    }
    @Test void notificationInputInvertsOnFocusAndKeepsTheMessageBackgroundAfterFocusLeaves() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput("new.txt"));
            int row = window.rowStartingWith("Save As: ");
            for (int column : new int[] {"Save As: ".length(), "Save As: ".length() + 12}) {
                var style = window.screenStyle(row, column);
                assertEquals(window.screenStyle(row, 0).bg(), style.bg());
                assertEquals(window.screenStyle(row, 0).fg(), style.fg()); assertTrue(style.inverse());
            }
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertFalse(window.screenStyle(row, "Save As: ".length() + 12).inverse());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }
    @Test void inlineFindAndReplaceInvertOnlyTheFocusedNotificationField() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("query"));
            for (String label : List.of("Find: ", "Replace: ")) {
                int row = window.rowStartingWith(label);
                for (int col : new int[] {label.length(), label.length() + 12}) {
                    var field = window.screenStyle(row, col);
                    assertEquals(window.screenStyle(row, 0).bg(), field.bg());
                    assertEquals(label.equals("Find: "), field.inverse());
                }
            }
        }
    }
    @Test void inlineDropdownPopupInvertsTheSearchBarInsteadOfInheritingTheSidebarMenuColors() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.command(TuinkCommand.SEARCH_OPEN);
            int row = window.rowStartingWith("Find: ");
            int column = window.screenRow(row).indexOf("[Strict ▾]");
            assertTrue(column >= 0);
            var body = window.host.styles.windows().searchBar();
            assertEquals(body, window.screenStyle(row, column));
            window.focusSearch(SearchFocus.MODE); window.event(KeyStroke.of(KeyStroke.Key.SPACE));
            int popupRow = java.util.stream.IntStream.range(0, window.height)
                .filter(line -> window.screenRow(line).contains("* and ?")).findFirst().orElseThrow();
            int popupColumn = window.screenRow(popupRow).indexOf("Wildcard");
            assertEquals(body.inverted(), window.screenStyle(popupRow, popupColumn));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }
    @Test void focusedSearchFieldUsesTheAccentWhileInactiveFieldsStayInverseAndChecksOnlyShowTheirValue() throws Exception {
        try (var window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.controller.openFolder(directory); window.await(() -> !window.ui.sidebar.tree.loading());
            window.command(TuinkCommand.PROJECT_SEARCH_OPEN);
            fieldIsAccent(window);
            window.event(new TextInput("needle")); fieldIsAccent(window);
            click(window, ProjectSearchPanel.Target.CASE);
            assertTrue(window.ui.sidebar.search.caseSensitive);
            fieldIsGray(window);
            click(window, ProjectSearchPanel.Target.WORD);
            assertEquals(window.host.styles.windows().sidebarBody(), style(window, ProjectSearchPanel.Target.CASE));
            click(window, ProjectSearchPanel.Target.MODE); window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertEquals(window.host.styles.windows().sidebarBody(), style(window, ProjectSearchPanel.Target.WORD));
            click(window, ProjectSearchPanel.Target.QUERY);
            assertEquals(window.host.styles.windows().sidebarBody(), style(window, ProjectSearchPanel.Target.MODE));
            fieldIsAccent(window);
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }
    private void fieldIsAccent(EditorWindowFixture window) {
        Rect bounds = bounds(window, ProjectSearchPanel.Target.QUERY);
        var expected = window.host.styles.windows().sidebarSelectedFocused().emphasis(true, true);
        var text = window.screenStyle(bounds.y(), bounds.x());
        var content = window.ui.sidebar.search.query.isEmpty()
            ? dev.tuiframe.widget.ControlStyles.placeholder(window.host.explorerWidgetTheme(true), dev.tuiframe.widget.WidgetState.FOCUSED) : expected;
        assertEquals(content, text); assertEquals(expected.bg(), text.bg()); assertFalse(text.inverse());
        assertEquals(expected, window.screenStyle(bounds.y(), bounds.rightExclusive() - 1));
    }
    private void fieldIsGray(EditorWindowFixture window) {
        Rect bounds = bounds(window, ProjectSearchPanel.Target.QUERY);
        if (window.ui.sidebar.search.query.isEmpty())
            assertEquals(dev.tuiframe.widget.ControlStyles.placeholder(window.host.explorerWidgetTheme(true), dev.tuiframe.widget.WidgetState.NORMAL),
                window.screenStyle(bounds.y(), bounds.x()));
        else grayField(window.screenStyle(bounds.y(), bounds.x()));
        grayField(window.screenStyle(bounds.y(), bounds.rightExclusive() - 1));
    }
    private Rect bounds(EditorWindowFixture window, ProjectSearchPanel.Target id) {
        return window.ui.sidebar.placement.search().scope().target(id).orElseThrow().placement().bounds();
    }
    private CellStyle style(EditorWindowFixture window, ProjectSearchPanel.Target id) {
        Rect bounds = bounds(window, id); return window.screenStyle(bounds.y(), bounds.x());
    }
    private void click(EditorWindowFixture window, ProjectSearchPanel.Target id) {
        Rect bounds = bounds(window, id);
        window.pointer(PointerEvent.Kind.PRESS, bounds.y(), bounds.x()); window.pointer(PointerEvent.Kind.RELEASE, bounds.y(), bounds.x());
    }
}
