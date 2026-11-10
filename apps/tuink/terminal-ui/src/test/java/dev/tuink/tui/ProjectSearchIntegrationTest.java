package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.project.search.ProjectSearch;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSearchIntegrationTest {
    @TempDir Path root;
    private void cycleMode(EditorWindowFixture window) {
        var button = window.ui.sidebar.placement.search().scope().target(ProjectSearchPanel.Target.MODE).orElseThrow().placement().bounds();
        window.pointer(PointerEvent.Kind.PRESS, button.y(), button.x());
        window.pointer(PointerEvent.Kind.RELEASE, button.y(), button.x());
        window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }

    @Test void switchesBetweenLiteralWildcardAndRegexWithOneSharedMatcher() throws Exception {
        Files.writeString(root.resolve("closed.txt"), "axb a.b");
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            window.controller.openFolder(root); window.frame();
            search(window, "a.b"); assertEquals(1, window.ui.sidebar.search.matches());
            cycleMode(window);
            search(window, "a?b"); assertEquals(2, window.ui.sidebar.search.matches());
            cycleMode(window);
            search(window, "a.b"); assertEquals(2, window.ui.sidebar.search.matches());
            assertEquals(dev.tuink.editor.search.SearchOptions.MatchMode.REGEX, window.ui.sidebar.search.mode);
        }
    }

    @Test void invalidRegexReportsAnErrorAndAnEmptyLineResultCanBeOpened() throws Exception {
        Path target = Files.writeString(root.resolve("closed.txt"), "first\n\nlast");
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            window.controller.openFolder(root); window.frame();
            window.command(TuinkCommand.PROJECT_SEARCH_OPEN); cycleMode(window); cycleMode(window);
            window.command(TuinkCommand.PROJECT_SEARCH_OPEN);
            window.event(new TextInput("[")); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> window.ui.sidebar.search.error != null);
            assertTrue(window.ui.sidebar.search.error.contains("Invalid regular expression"));
            assertFalse(window.active().state().isDirty());
            search(window, "^$"); assertEquals(1, window.ui.sidebar.search.matches());
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN)); window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.documentIo.busy());
            assertEquals(target, dev.tuink.storage.jvm.JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertEquals(1, window.active().state().getCursor().line());
            assertEquals(0, window.active().state().getCursor().col());
        }
    }
    private void search(EditorWindowFixture window, String query) throws Exception {
        var previous = window.ui.sidebar.search.finished;
        window.event(KeyStroke.of(KeyStroke.Key.F, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT));
        window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
        window.event(new TextInput(query));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
        window.await(() -> window.ui.sidebar.search.finished != null && window.ui.sidebar.search.finished != previous);
        assertNull(window.ui.sidebar.search.error);
    }
    @Test void findsClosedFileAndNavigatesToExactUnicodeRange() throws Exception {
        Path target = Files.writeString(root.resolve("closed.txt"), "before\n🙂 needle after\n");
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            window.controller.openFolder(root); window.frame();
            search(window, "needle");
            var panel = window.ui.sidebar.search;
            assertEquals(1, panel.matches());
            assertEquals("closed.txt", panel.groups.values().iterator().next().target.label());
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.documentIo.busy());
            assertEquals(target, dev.tuink.storage.jvm.JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertEquals(1, window.active().state().getCursor().line());
            assertEquals(9, window.active().state().getCursor().col());
            assertEquals(3, window.active().state().getSelectionAnchor().col());
            assertFalse(window.ui.sidebar.focused);
        }
    }
    @Test void searchesUnsavedBufferAndKeepsIndependentFolderAndSearchState() throws Exception {
        Files.writeString(root.resolve("closed.txt"), "disk needle");
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            window.controller.openFolder(root); window.frame();
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE)); window.event(new TextInput("draft needle "));
            String text = window.active().tabSession().model().getLine(0);
            var tree = window.ui.sidebar.tree;
            search(window, "needle");
            assertEquals(2, window.ui.sidebar.search.matches());
            assertEquals(1, window.ui.sidebar.search.groups.values().stream().filter(group -> group.target.documentId() != null).count());
            var results = window.ui.sidebar.search.rows;
            window.event(KeyStroke.of(KeyStroke.Key.E, KeyStroke.Modifier.CTRL));
            assertEquals(SidebarState.Content.FOLDERS, window.ui.sidebar.content);
            assertSame(tree, window.ui.sidebar.tree);
            window.event(KeyStroke.of(KeyStroke.Key.B, KeyStroke.Modifier.CTRL)); assertFalse(window.ui.sidebar.visible);
            window.event(KeyStroke.of(KeyStroke.Key.B, KeyStroke.Modifier.CTRL)); assertTrue(window.ui.sidebar.visible);
            window.command(TuinkCommand.PROJECT_SEARCH_OPEN);
            assertEquals("needle", window.ui.sidebar.search.query.text());
            assertSame(results, window.ui.sidebar.search.rows);
            assertEquals(text, window.active().tabSession().model().getLine(0));
            assertEquals("original", Files.readString(window.document));
        }
    }
    @Test void fieldsAndResultControlsNeverEditTheDocumentOrRunFileUndo() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            window.controller.openFolder(root); window.frame(); search(window, "original");
            window.event(KeyStroke.of(KeyStroke.Key.Z, KeyStroke.Modifier.CTRL));
            window.command(TuinkCommand.SEARCH_REPLACE_ALL);
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            window.event(KeyStroke.of(KeyStroke.Key.SPACE));
            assertTrue(window.ui.sidebar.search.modes.popupOpen());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(window.ui.sidebar.search.modes.popupOpen());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
            assertFalse(window.ui.sidebar.focused);
        }
    }
    @Test void aSymlinkWorkingRootStillUsesABufferOpenedThroughItsPhysicalName() throws Exception {
        Path physical = Files.createDirectory(root.resolve("physical"));
        Path alias = Files.createSymbolicLink(root.resolve("alias"), physical);
        try (var window = new EditorWindowFixture(physical, false, List.of("original"))) {
            window.controller.openFolder(alias); window.frame();
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE)); window.event(new TextInput("draft needle "));
            search(window, "needle");
            assertEquals(1, window.ui.sidebar.search.matches());
            assertNotNull(window.ui.sidebar.search.groups.values().iterator().next().target.documentId());
            assertEquals("original", Files.readString(window.document));
        }
    }
    @Test void editingAnOutsideBufferDoesNotInvalidateFolderSearchCoverage() throws Exception {
        Path folder = Files.createDirectory(root.resolve("project"));
        Files.writeString(folder.resolve("note.txt"), "needle");
        try (var window = new EditorWindowFixture(root, false, List.of("outside"))) {
            window.controller.openFolder(folder); window.frame(); search(window, "needle");
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE)); window.event(new TextInput("changed "));
            assertFalse(window.ui.sidebar.search.stale);
        }
    }
    @Test void changingSourceAfterSearchMarksItsResultsStale() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("needle"))) {
            window.controller.openFolder(root); window.frame(); search(window, "needle");
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.event(new TextInput("changed "));
            assertTrue(window.ui.sidebar.search.stale);
            assertTrue(window.ui.sidebar.search.groups.values().iterator().next().stale);
        }
    }
    @Test void replacedDiskFileIsRefreshedInsteadOfUsingAnOldPosition() throws Exception {
        Path file = Files.writeString(root.resolve("closed.txt"), "needle");
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            window.controller.openFolder(root); window.frame(); search(window, "needle");
            Files.writeString(file, "new first line\nneedle");
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN)); window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.documentIo.busy() && window.ui.sidebar.search.finished != null);
            var group = window.ui.sidebar.search.groups.values().iterator().next();
            assertEquals(1, group.matches.getFirst().line());
        }
    }
    @Test void rootChangeClearsOldResultsWithoutClosingDocuments() throws Exception {
        Path other = Files.createDirectory(root.resolve("other"));
        Files.writeString(root.resolve("old.txt"), "needle"); Files.writeString(other.resolve("new.txt"), "current");
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            window.controller.openFolder(root); window.frame(); search(window, "needle");
            var document = window.active();
            window.controller.openFolder(other); window.frame();
            assertEquals(0, window.ui.sidebar.search.matches()); assertSame(document, window.active());
            search(window, "current");
            assertEquals(List.of("new.txt"), window.ui.sidebar.search.groups.values().stream().map(group -> group.target.label()).toList());
        }
    }
    @Test void aNewQueryReplacesAStreamingRunWithoutLateOldMatches() throws Exception {
        Files.writeString(root.resolve("many.txt"), "old needle ".repeat(25000));
        Files.writeString(root.resolve("current.txt"), "fresh");
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            window.controller.openFolder(root); window.frame();
            window.command(TuinkCommand.PROJECT_SEARCH_OPEN);
            window.event(new TextInput("needle")); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> window.ui.sidebar.search.matches() > 0);
            search(window, "fresh");
            assertEquals(1, window.ui.sidebar.search.matches());
            assertEquals("current.txt", window.ui.sidebar.search.groups.values().iterator().next().target.label());
            for (int i = 0; i < 10; i++) window.frame();
            assertEquals(1, window.ui.sidebar.search.matches());
        }
    }
    @Test void stopKeepsAcceptedResultsAndAllowsTheNextQuery() throws Exception {
        Files.writeString(root.resolve("many.txt"), "needle ".repeat(25000));
        Files.writeString(root.resolve("current.txt"), "fresh");
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            window.controller.openFolder(root); window.frame();
            window.command(TuinkCommand.PROJECT_SEARCH_OPEN);
            window.event(new TextInput("needle")); window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> window.ui.sidebar.search.matches() > 0);
            var stop = window.ui.sidebar.placement.search().scope().target(ProjectSearchPanel.Target.FIND).orElseThrow();
            window.pointer(PointerEvent.Kind.PRESS, stop.placement().bounds().y(), stop.placement().bounds().x());
            assertFalse(window.ui.sidebar.search.running);
            assertEquals(ProjectSearch.Outcome.CANCELLED, window.ui.sidebar.search.finished.outcome());
            assertTrue(window.ui.sidebar.search.matches() > 0);
            search(window, "fresh"); assertEquals(1, window.ui.sidebar.search.matches());
        }
    }
    @Test void searchSettingsAreModalValidatedAndDoNotReplaceCurrentFilters() throws Exception {
        try (var window = new EditorWindowFixture(root, false, List.of("original"))) {
            window.controller.openFolder(root); window.frame();
            window.ui.sidebar.search.includes.set("*.md");
            window.command(TuinkCommand.PROJECT_SEARCH_SETTINGS);
            var draft = window.ui.searchSettingsDialog;
            assertNotNull(draft);
            draft.size.set("0");
            for (int i = 0; i < 7; i++) window.event(KeyStroke.of(KeyStroke.Key.TAB));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertSame(draft, window.ui.searchSettingsDialog); assertNotNull(draft.error);
            draft.size.set("4"); draft.matches.set("250"); draft.encoding = DocumentOpenMode.CP866;
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertNull(window.ui.searchSettingsDialog);
            assertEquals(4, window.ui.searchPreferences.maximumFileMiB());
            assertEquals(250, window.settingsSession.preferences(window.ui.settingsSnapshot(window.host.theme)).projectSearch().maximumMatches());
            assertEquals(DocumentOpenMode.CP866, window.ui.settingsSnapshot(window.host.theme).projectSearch().encoding());
            assertEquals("*.md", window.ui.sidebar.search.includes.text());
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }
    @Test void narrowResizeAndPreviewRetainTheSearchPanel() throws Exception {
        try (var window = new EditorWindowFixture(root, "notes.md", false, List.of("# needle"))) {
            window.controller.openFolder(root); window.frame(); search(window, "needle");
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.controller.previewFrame() != null);
            assertEquals(SidebarState.Content.SEARCH, window.ui.sidebar.content);
            assertEquals(1, window.ui.sidebar.search.matches());
            window.resize(12, 32); window.resize(24, 80);
            assertEquals(SidebarState.Content.SEARCH, window.ui.sidebar.content);
            assertEquals("needle", window.ui.sidebar.search.query.text());
        }
    }
}
