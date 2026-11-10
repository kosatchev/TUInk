package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSearchControlsIntegrationTest {
    @TempDir Path root;
    private void open(EditorWindowFixture window) throws Exception {
        window.controller.openFolder(root); window.await(() -> !window.ui.sidebar.tree.loading());
        window.command(TuinkCommand.PROJECT_SEARCH_OPEN);
    }
    private void click(EditorWindowFixture window, ProjectSearchPanel.Target id) {
        var bounds = window.ui.sidebar.placement.search().scope().target(id).orElseThrow().placement().bounds();
        window.pointer(PointerEvent.Kind.PRESS, bounds.y(), bounds.x());
        window.pointer(PointerEvent.Kind.RELEASE, bounds.y(), bounds.x());
    }
    private void query(EditorWindowFixture window, String text) throws Exception {
        var previous = window.ui.sidebar.search.finished;
        window.command(TuinkCommand.PROJECT_SEARCH_OPEN);
        window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL)); window.event(new TextInput(text));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
        window.await(() -> window.ui.sidebar.search.finished != null && window.ui.sidebar.search.finished != previous);
    }
    @Test void checkedAddFlagsIncludeIgnoredAndHiddenFiles() throws Exception {
        Files.createDirectory(root.resolve(".git")); Files.writeString(root.resolve(".gitignore"), "ignored.txt\n");
        for (String file : List.of("plain.txt", "ignored.txt", ".hidden")) Files.writeString(root.resolve(file), "probe");
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            window.resize(36, 160); open(window); window.ui.sidebar.preferredWidth = 70; window.frame();
            click(window, ProjectSearchPanel.Target.FILTERS); var p = window.ui.sidebar.search;
            assertTrue(p.respectIgnore); assertTrue(p.hidden);
            assertTrue(java.util.stream.IntStream.range(0, window.height).mapToObj(window::screenRow).anyMatch(line -> line.contains("[  Add gitignore]")));
            assertTrue(java.util.stream.IntStream.range(0, window.height).mapToObj(window::screenRow).anyMatch(line -> line.contains("[✓ Add hidden]")));
            query(window, "probe"); assertEquals(2, p.matches());
            click(window, ProjectSearchPanel.Target.HIDDEN); query(window, "probe"); assertEquals(1, p.matches());
            click(window, ProjectSearchPanel.Target.IGNORE); assertFalse(p.respectIgnore);
            query(window, "probe"); assertEquals(2, p.matches());
            click(window, ProjectSearchPanel.Target.HIDDEN); query(window, "probe"); assertEquals(3, p.matches());
        }
    }
    @Test void dropdownShowsChoicesAndCancelPreservesQueryModeAndResults() throws Exception {
        Files.writeString(root.resolve("closed.txt"), "a.b axb");
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            open(window); query(window, "a.b"); var p = window.ui.sidebar.search;
            var rows = p.rows; long generation = p.resultGeneration;
            click(window, ProjectSearchPanel.Target.MODE);
            assertTrue(p.modes.popupOpen());
            for (var option : p.modes.options()) assertTrue(java.util.stream.IntStream.range(0, window.height).anyMatch(row -> window.screenRow(row).contains(option.label())));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            assertEquals(SearchOptions.MatchMode.SUBSTRING, p.mode);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(p.modes.popupOpen()); assertSame(rows, p.rows); assertEquals(generation, p.resultGeneration);
            assertEquals("a.b", p.query.text());
            click(window, ProjectSearchPanel.Target.MODE);
            window.event(KeyStroke.of(KeyStroke.Key.END)); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(SearchOptions.MatchMode.REGEX, p.mode); assertFalse(p.modes.popupOpen());
            query(window, "a.b"); assertEquals(2, p.matches());
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }
    @Test void widePaletteOffersThreeDirectModeButtonsAndResizingClosesAnOpenPicker() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            window.resize(32, 140); open(window);
            click(window, ProjectSearchPanel.Target.MODE); assertTrue(window.ui.sidebar.search.modes.popupOpen());
            window.ui.sidebar.preferredWidth = 70; window.frame();
            var view = window.ui.sidebar.placement.search();
            assertFalse(window.ui.sidebar.search.modes.popupOpen());
            assertTrue(view.scope().target(ProjectSearchPanel.Target.MODE).isEmpty());
            for (var mode : SearchOptions.MatchMode.values()) assertTrue(view.scope().target(ProjectSearchPanel.modeTarget(mode)).isPresent());
            click(window, ProjectSearchPanel.Target.MODE_WILDCARD);
            assertEquals(SearchOptions.MatchMode.WILDCARD, window.ui.sidebar.search.mode);
            window.ui.sidebar.preferredWidth = 30; window.frame();
            assertTrue(window.ui.sidebar.placement.search().scope().target(ProjectSearchPanel.Target.MODE).isPresent());
            assertFalse(window.active().state().isDirty());
        }
    }
    @Test void filterLabelsExamplesAndCountsAreClearAndFileMasksRemainIndependentOfRegex() throws Exception {
        Files.writeString(root.resolve("one.java"), "id=42"); Files.writeString(root.resolve("two.md"), "id=42");
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            window.resize(36, 100); open(window);
            click(window, ProjectSearchPanel.Target.MODE); window.event(KeyStroke.of(KeyStroke.Key.END)); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            click(window, ProjectSearchPanel.Target.FILTERS);
            var p = window.ui.sidebar.search;
            assertEquals("", p.includes.text()); assertEquals("", p.excludes.text());
            assertTrue(window.screenRow(window.ui.sidebar.placement.search().scope().target(ProjectSearchPanel.Target.INCLUDES).orElseThrow().placement().bounds().y()).contains("Include:"));
            assertTrue(window.screenRow(window.ui.sidebar.placement.search().scope().target(ProjectSearchPanel.Target.EXCLUDES).orElseThrow().placement().bounds().y()).contains("Exclude:"));
            int initial = ProjectSearchPresentation.activeFilters(p, root);
            click(window, ProjectSearchPanel.Target.INCLUDES); window.event(new TextInput("*.java"));
            assertEquals(initial + 1, ProjectSearchPresentation.activeFilters(p, root));
            query(window, "\\d+"); assertEquals(1, p.matches());
            assertEquals("one.java", p.groups.values().iterator().next().target.label());
            click(window, ProjectSearchPanel.Target.EXCLUDES); window.event(new TextInput("*.java"));
            query(window, "\\d+"); assertEquals(0, p.matches());
            assertEquals(ProjectSearchPresentation.State.NO_MATCHES, ProjectSearchPresentation.status(p).state());
            assertEquals("*.java", p.includes.text()); assertEquals("*.java", p.excludes.text());
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }
    @Test void selectionUsesFullPathAndColumnAndErrorsAndStalenessHaveSeparateStates() throws Exception {
        Path target = Files.writeString(root.resolve("closed.txt"), "before needle after");
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            window.resize(36, 180); open(window); query(window, "needle");
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN)); window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            assertTrue(window.screenRow(window.height - 1).contains(target.toString()), window.screenRow(window.height - 1));
            assertTrue(window.screenRow(window.height - 1).contains("Col 8"));
            click(window, ProjectSearchPanel.Target.MODE); window.event(KeyStroke.of(KeyStroke.Key.END)); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            query(window, "[");
            assertEquals(ProjectSearchPresentation.State.ERROR, ProjectSearchPresentation.status(window.ui.sidebar.search).state());
            assertTrue(java.util.stream.IntStream.range(0, window.height).mapToObj(window::screenRow).anyMatch(line -> line.contains("Invalid regular expression")));
            query(window, "before");
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE)); window.event(new TextInput("before "));
            window.await(() -> window.ui.sidebar.search.stale);
            assertEquals(ProjectSearchPresentation.State.STALE, ProjectSearchPresentation.status(window.ui.sidebar.search).state());
            assertEquals("original", Files.readString(window.document));
            assertEquals("before needle after", Files.readString(target));
        }
    }
}
