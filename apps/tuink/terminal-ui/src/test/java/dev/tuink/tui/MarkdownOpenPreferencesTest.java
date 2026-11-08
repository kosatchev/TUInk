package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MarkdownOpenPreferencesTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void startupUsesBothPreferencesIndependently(boolean both, boolean preview) throws Exception {
        try (var window = new EditorWindowFixture(directory, "initial.MD", List.of("# Initial", "body"), settings(both, preview))) {
            assertPresentation(window, both, preview, window.document);
            if (preview && !both) {
                var snapshot = assertInstanceOf(PreviewTab.ClosedSnapshot.class,
                    ((PreviewTab) window.selected()).source());
                assertEquals(List.of("# Initial", "body"), snapshot.source().text().lines());
                window.event(KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL));
                window.await(() -> window.selected() instanceof TerminalEditorApp.EditorTab);
                assertEquals(2, window.context.presentations().size());
                assertInstanceOf(TerminalEditorApp.EditorTab.class, window.context.presentations().get(0));
                assertInstanceOf(PreviewTab.class, window.context.presentations().get(1));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void pickerUsesTheSamePolicyAndPreservesTheExistingDirtyView(boolean both, boolean preview) throws Exception {
        Path markdown = Files.writeString(directory.resolve("z-new.markdown"), "# Opened\n");
        try (var window = new EditorWindowFixture(directory, List.of("keep"), settings(both, preview))) {
            var original = window.active();
            window.event(new TextInput("dirty "));
            window.command(TuinkCommand.DOCUMENT_OPEN);
            window.await(() -> !window.tree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.END));
            assertEquals(markdown, window.tree.state().selectedId().path());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> window.context.presentations().size() > 1 && !window.documentIo.busy());
            assertEquals(List.of("dirty keep"), original.tabSession().model().snapshotLines());
            assertTrue(original.state().isDirty());
            assertSame(original, window.context.source(original.documentSession().viewId()));
            var opened = window.context.presentations().subList(1, window.context.presentations().size());
            assertEquals(both ? 2 : 1, opened.size());
            assertEquals(preview, window.selected() instanceof PreviewTab);
            if (both) {
                var source = assertInstanceOf(TerminalEditorApp.EditorTab.class, opened.get(0));
                var projection = assertInstanceOf(PreviewTab.class, opened.get(1));
                assertEquals(source.groupId(), projection.groupId());
                assertInstanceOf(PreviewTab.LiveSource.class, projection.source());
            } else if (preview) {
                assertInstanceOf(PreviewTab.ClosedSnapshot.class, ((PreviewTab) opened.get(0)).source());
                assertEquals(1, window.tabs.size(), "Only the dirty original retains an editing engine");
            }
        }
    }

    @Test void togglesDoNotRetireAnExistingDirtySourceOrChangeManualCtrlP() throws Exception {
        try (var window = new EditorWindowFixture(directory, "initial.md", List.of("keep"), settings(false, false))) {
            var source = window.active();
            window.event(new TextInput("dirty "));
            window.command(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_OPEN_IN_PREVIEW);
            assertSame(source, window.selected());
            assertEquals(1, window.context.presentations().size());
            assertTrue(source.state().isDirty());
            window.event(KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL));
            assertInstanceOf(PreviewTab.LiveSource.class, ((PreviewTab) window.selected()).source());
            assertEquals(2, window.context.presentations().size());
            assertTrue(source.state().isDirty());
            window.event(KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL));
            assertSame(source, window.selected());
        }
    }


    @Test void settingsMenuCheckboxesAreIndependentAndPersistFromKeyboard() throws Exception {
        try (var window = new EditorWindowFixture(directory, List.of("keep"), settings(false, false))) {
            window.command(TuinkCommand.MENU_SETTINGS);
            window.selectMenuCommand(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_OPEN_BOTH_TABS);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(window.ui.markdownOpenBothTabsSetting);
            assertFalse(window.ui.markdownOpenInPreviewSetting);
            window.command(TuinkCommand.MENU_SETTINGS);
            window.selectMenuCommand(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_OPEN_IN_PREVIEW);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(window.ui.markdownOpenBothTabsSetting);
            assertTrue(window.ui.markdownOpenInPreviewSetting);
            var saved = window.settingsSession.preferences(window.ui.settingsSnapshot(window.host.theme));
            assertTrue(saved.markdownOpenBothTabs());
            assertTrue(saved.markdownOpenInPreview());
            assertEquals(1, window.context.presentations().size(), "Changing future-open preferences does not change current tabs");
        }
    }

    @Test void openingAnExistingSourceIgnoresFutureOpenPreferences() throws Exception {
        try (var window = new EditorWindowFixture(directory, "initial.md", List.of("keep"), settings(false, false))) {
            var source = window.active();
            window.event(new TextInput("dirty "));
            window.command(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_OPEN_IN_PREVIEW);
            window.command(TuinkCommand.DOCUMENT_OPEN);
            window.await(() -> !window.tree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.END));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            window.await(() -> !window.documentIo.busy() && !window.selected().uiState().openFileMode);
            assertSame(source, window.selected());
            assertEquals(1, window.context.presentations().size());
            assertSame(source, window.context.source(source.documentSession().viewId()));
            assertEquals(List.of("dirty keep"), source.tabSession().model().snapshotLines());
            assertTrue(source.state().isDirty());
        }
    }

    @Test void unsupportedExtensionsAndDirtyAdmissionsRemainSource() {
        for (boolean both : List.of(false, true)) for (boolean preview : List.of(false, true)) {
            assertEquals(MarkdownOpenPolicy.SOURCE, MarkdownOpenPolicy.forNewFile(Path.of("notes.txt"), false, both, preview));
            assertEquals(MarkdownOpenPolicy.SOURCE, MarkdownOpenPolicy.forNewFile(Path.of("draft.md"), true, both, preview));
        }
    }

    private static void assertPresentation(EditorWindowFixture window, boolean both, boolean preview, Path file) {
        assertEquals(both ? 2 : 1, window.context.presentations().size());
        assertEquals(preview, window.selected() instanceof PreviewTab);
        assertEquals(preview && !both ? 0 : 1, window.tabs.size());
        if (both) {
            var source = assertInstanceOf(TerminalEditorApp.EditorTab.class, window.context.presentations().get(0));
            var projection = assertInstanceOf(PreviewTab.class, window.context.presentations().get(1));
            assertEquals(source.groupId(), projection.groupId());
            assertEquals(file, JvmDocumentStorage.pathOf(source.state().getCurrentResource()));
        }
    }

    private static AppSettingsSession settings(boolean both, boolean preview) {
        var d = AppSettings.defaults();
        var s = new AppSettings(d.showTabBar(), d.wrapLines(), d.wrapWords(), d.readingMode(), d.confirmOnClose(),
            d.mouseMode(), d.highlightActiveGutter(), d.smartHomeEnd(), d.autoIndent(), d.autoPairs(), d.autoList(),
            d.visibleWhitespace(), d.syntaxHighlighting(), d.rainbowBrackets(), d.autoSelectionHighlight(),
            both, preview, d.markdownCodeHighlighting(), d.gutterDiffMode(), d.tabWidth(), d.tabInsertMode(), d.theme(),
            d.rightClickClipboard(), d.markdownVisualCopy(), true);
        return new AppSettingsSession(s, TuiCliOptions.parse(new String[0]));
    }
}
