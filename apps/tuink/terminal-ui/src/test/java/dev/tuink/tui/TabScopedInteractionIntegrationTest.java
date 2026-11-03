package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.ui.HelpPages;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuink.tui.ui.TabBarView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Per-view controls exercised through the same event and paint route as the terminal. */
class TabScopedInteractionIntegrationTest {
    @TempDir Path directory;

    @Test
    void searchesRetainTheirFieldsOptionsSelectionAndFocusAcrossKeyboardTabSwitches() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            var second = window.active();
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("missing-a"));
            window.focusSearch(SearchFocus.CASE);
            window.event(KeyStroke.of(KeyStroke.Key.SPACE));
            window.focusSearch(SearchFocus.REPLACE);
            window.event(new TextInput("replacement-a"));
            window.replacement.selectAll();
            window.frame();
            assertIndicators(window, first, true, false);

            window.command(TuinkCommand.TAB_NEXT);

            assertSame(second.uiState(), window.active().uiState());
            assertFalse(second.uiState().searchMode);
            assertFalse(hasRow(window, "Find:"));
            assertEquals("", window.query.text());
            assertEquals("", window.replacement.text());
            window.event(new TextInput("second document"));
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("missing-b"));
            assertFalse(second.uiState().searchUi.caseSensitive());
            assertEquals("missing-a", first.uiState().searchQueryInput.text());
            assertIndicators(window, first, true, false);
            assertIndicators(window, second, true, false);

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(second.uiState().searchMode);
            assertIndicators(window, second, false, false);
            window.command(TuinkCommand.TAB_PREVIOUS);

            assertSame(first.uiState(), window.active().uiState());
            assertTrue(first.uiState().searchMode);
            assertEquals("missing-a", window.query.text());
            assertEquals("replacement-a", window.replacement.text());
            assertEquals("replacement-a", window.replacement.selectedText());
            assertTrue(first.uiState().searchUi.caseSensitive());
            assertEquals(SearchFocus.REPLACE, window.host.searchFocusManager().focused().orElseThrow());
            assertEquals(List.of("original"), first.tabSession().model().snapshotLines());
            assertEquals(List.of("second document"), second.tabSession().model().snapshotLines());
        }
    }

    @Test
    void clickingAnotherTabSuspendsSearchAndReturningRestoresIt() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.showTabBarSetting = true;
            window.ui.mouseModeSetting = true;
            window.mouseAvailable = true;
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            var second = window.active();
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("missing-a"));
            int secondColumn = window.screenRow(1).indexOf(TabBarView.NO_NAME_LABEL) + 2;
            assertTrue(secondColumn > 1, window.screenRow(1));

            clickTab(window, secondColumn);

            assertSame(second.uiState(), window.active().uiState());
            assertFalse(hasRow(window, "Find:"));
            assertIndicators(window, first, true, false);
            int firstColumn = window.screenRow(1).indexOf("document.txt") + 2;
            assertTrue(firstColumn > 1, window.screenRow(1));
            clickTab(window, firstColumn);

            assertSame(first.uiState(), window.active().uiState());
            assertTrue(hasRow(window, "Find:"));
            assertEquals("missing-a", window.query.text());
            assertEquals(SearchFocus.FIND, window.host.searchFocusManager().focused().orElseThrow());
        }
    }

    @Test
    void anInactiveCloseQuestionKeepsItsBadgeAndAnswerFocusWhileAnotherTabIsEdited() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.confirmOnCloseSetting = true;
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            var second = window.active();
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.TAB_CLOSE);
            var question = first.uiState().modalState;
            var focus = window.host.confirmFocusManager().focused();
            assertTrue(question.isCloseTabConfirm());
            assertIndicators(window, first, false, true);

            window.command(TuinkCommand.TAB_NEXT);
            window.event(new TextInput("second draft"));

            assertSame(second.uiState(), window.active().uiState());
            assertTrue(second.uiState().modalState.isNone());
            assertSame(question, first.uiState().modalState);
            assertEquals(List.of("second draft"), second.tabSession().model().snapshotLines());
            assertEquals(List.of("draft original"), first.tabSession().model().snapshotLines());
            assertIndicators(window, first, false, true);
            assertIndicators(window, second, false, false);
            window.command(TuinkCommand.TAB_PREVIOUS);
            assertSame(question, first.uiState().modalState);
            assertEquals(focus, window.host.confirmFocusManager().focused());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));

            assertTrue(first.uiState().modalState.isNone());
            assertIndicators(window, first, false, false);
            assertEquals(2, window.tabs.size());
            assertTrue(first.state().isDirty());
            assertEquals("original", Files.readString(window.document));
        }
    }

    @Test
    void anOverwriteQuestionAndItsFilenameRemainOnTheirTabWhileAnotherSearchIsUsed() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            var second = window.active();
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.event(new TextInput("draft "));
            Path target = Files.writeString(directory.resolve("existing.txt"), "existing");
            SaveWorkflowIntegrationTest.submitFilename(window, target);
            assertTrue(first.uiState().modalState.isSaveOverwriteConfirm());
            assertIndicators(window, first, false, true);

            window.command(TuinkCommand.TAB_NEXT);
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("missing-b"));

            assertSame(second.uiState(), window.active().uiState());
            assertEquals("missing-b", window.query.text());
            assertEquals("", window.filename.text());
            assertEquals(target.toString(), first.uiState().saveAsInput.text());
            assertIndicators(window, first, false, true);
            assertIndicators(window, second, true, false);
            assertEquals("existing", Files.readString(target));
            window.command(TuinkCommand.TAB_PREVIOUS);
            assertEquals(target.toString(), window.filename.text());
            assertTrue(first.uiState().modalState.isSaveOverwriteConfirm());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertEquals("existing", Files.readString(target));
            assertTrue(first.state().isDirty());
            assertTrue(second.uiState().searchMode);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellingALocalPromptResumesTheSearchItCovered(boolean saveAs) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.confirmOnCloseSetting = true;
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            var second = window.active();
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("missing-a"));
            window.focusSearch(SearchFocus.REPLACE);
            window.event(new TextInput("replacement-a"));
            window.replacement.selectAll();
            window.frame();

            window.command(saveAs ? TuinkCommand.DOCUMENT_SAVE_AS : TuinkCommand.TAB_CLOSE);

            assertTrue(saveAs ? first.uiState().modalState.isSaveAs()
                : first.uiState().modalState.isCloseTabConfirm());
            assertTrue(first.uiState().searchMode);
            assertFalse(hasRow(window, "Find:"));
            assertIndicators(window, first, true, true);
            window.command(TuinkCommand.TAB_NEXT);
            assertSame(second.uiState(), window.active().uiState());
            assertIndicators(window, first, true, true);
            assertIndicators(window, second, false, false);
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));

            assertTrue(first.uiState().modalState.isNone());
            assertTrue(first.uiState().searchMode);
            assertTrue(hasRow(window, "Find:"));
            assertEquals("missing-a", window.query.text());
            assertEquals("replacement-a", window.replacement.text());
            assertEquals("replacement-a", window.replacement.selectedText());
            assertEquals(SearchFocus.REPLACE, window.host.searchFocusManager().focused().orElseThrow());
            assertIndicators(window, first, true, false);
            assertEquals(List.of("draft original"), first.tabSession().model().snapshotLines());
            assertEquals("original", Files.readString(window.document));
        }
    }

    @Test
    void anInactiveSaveFailureRestoresOnlyItsOwnPromptAndFilename() throws Exception {
        ControlledExecutor worker = new ControlledExecutor();
        EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"), worker);
        try {
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            var second = window.active();
            window.event(new TextInput("second draft"));
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.event(new TextInput("draft "));
            Path target = directory.resolve("target.txt");
            SaveWorkflowIntegrationTest.submitFilename(window, target);
            assertTrue(window.documentIo.busy());
            window.command(TuinkCommand.TAB_NEXT);
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("missing-b"));
            Files.createDirectory(target);

            worker.runNextOnWorker();
            window.frame();

            assertFalse(window.documentIo.busy());
            assertSame(second.uiState(), window.active().uiState());
            assertTrue(second.uiState().modalState.isNone());
            assertTrue(second.uiState().searchMode);
            assertEquals("missing-b", window.query.text());
            assertEquals("", window.filename.text());
            assertTrue(first.uiState().modalState.isSaveAs());
            assertEquals(target.toString(), first.uiState().saveAsInput.text());
            assertIndicators(window, first, false, true);
            assertIndicators(window, second, true, false);
            window.command(TuinkCommand.TAB_PREVIOUS);
            assertEquals(target.toString(), window.filename.text());
            assertTrue(first.uiState().modalState.isSaveAs());
            assertEquals("original", Files.readString(window.document));
            assertEquals(List.of("second draft"), second.tabSession().model().snapshotLines());
        } finally {
            worker.finishQueued();
            window.close();
        }
    }

    @Test
    void anotherTabsSaveAsDialogRemainsEditableWhileAQueuedWriteCompletes() throws Exception {
        ControlledExecutor worker = new ControlledExecutor();
        EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"), worker);
        try {
            var first = window.active();
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.DOCUMENT_SAVE);
            assertTrue(window.documentIo.busy());
            window.command(TuinkCommand.TAB_NEW);
            var second = window.active();
            window.event(new TextInput("second draft"));
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            Path target = directory.resolve("second.txt");
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(target.toString()));
            var prompt = second.uiState().modalState;
            String notice = window.notification.message;
            assertTrue(prompt.isSaveAs());
            assertEquals(target.toString(), window.filename.text());
            assertEquals(SaveAsBarView.Target.FIELD, window.host.saveAsFocusManager().focused().orElseThrow());
            assertIndicators(window, second, false, true);

            worker.runNextOnWorker();
            window.frame();

            assertFalse(window.documentIo.busy());
            assertSame(second.uiState(), window.active().uiState());
            assertSame(prompt, second.uiState().modalState);
            assertEquals(target.toString(), window.filename.text());
            assertEquals(SaveAsBarView.Target.FIELD, window.host.saveAsFocusManager().focused().orElseThrow());
            assertEquals(notice, window.notification.message);
            assertIndicators(window, second, false, true);
            assertFalse(first.state().isDirty());
            assertTrue(second.state().isDirty());
            assertEquals("draft original", Files.readString(window.document));
            assertEquals(List.of("second draft"), second.tabSession().model().snapshotLines());
            assertFalse(Files.exists(target));
        } finally {
            worker.finishQueued();
            window.close();
        }
    }

    @Test
    void aNewTabCannotInheritTheClosedViewsSearchOrIndicator() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("missing-a"));
            assertIndicators(window, first, true, false);

            window.command(TuinkCommand.TAB_CLOSE);

            assertEquals(-1, window.context.indexOf(first.documentSession().viewId()));
            assertEquals(1, window.tabs.size());
            window.command(TuinkCommand.TAB_NEW);
            assertFalse(window.active().uiState().searchMode);
            assertTrue(window.active().uiState().modalState.isNone());
            assertEquals("", window.query.text());
            assertEquals("", window.replacement.text());
            for (var tab : window.tabs) assertIndicators(window, tab, false, false);
        }
    }

    @Test
    void filePickersKeepIndependentSelectionAndCloseOnlyOnTheirOwnTab() throws Exception {
        Path selected = Files.writeString(directory.resolve("z-selected.txt"), "selected");
        Path otherFile = Files.writeString(directory.resolve("a-other.txt"), "other");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            var second = window.active();
            second.tabSession().engine().open(JvmDocumentStorage.resource(otherFile));
            window.frame();
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.command(TuinkCommand.DOCUMENT_OPEN);
            window.await(() -> !window.tree.loading());
            window.event(KeyStroke.of(KeyStroke.Key.END));
            var firstTree = window.tree;
            var firstSelection = firstTree.state().selectedId();
            assertEquals(selected, firstSelection.path());
            assertIndicators(window, first, false, true);

            window.command(TuinkCommand.TAB_NEXT);
            window.event(new TextInput("second "));
            assertFalse(second.uiState().openFileMode);
            assertTrue(first.uiState().openFileMode);
            assertEquals(List.of("second other"), second.tabSession().model().snapshotLines());
            window.command(TuinkCommand.DOCUMENT_OPEN);
            window.await(() -> !window.tree.loading());
            var secondTree = window.tree;
            assertNotSame(firstTree, secondTree);
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            var secondSelection = secondTree.state().selectedId();
            assertFalse(firstSelection.equals(secondSelection));
            assertIndicators(window, first, false, true);
            assertIndicators(window, second, false, true);
            window.command(TuinkCommand.TAB_PREVIOUS);

            assertSame(firstTree, window.tree);
            assertEquals(firstSelection, window.tree.state().selectedId());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertFalse(first.uiState().openFileMode);
            assertTrue(second.uiState().openFileMode);
            assertIndicators(window, first, false, false);
            assertIndicators(window, second, false, true);
            window.command(TuinkCommand.TAB_NEXT);
            assertSame(secondTree, window.tree);
            assertEquals(secondSelection, window.tree.state().selectedId());
            assertEquals("original", Files.readString(window.document));
            assertEquals("other", Files.readString(otherFile));
        }
    }

    @Test
    void aHelpPageKeepsItsReadingPositionWhileAnotherTabsHelpIsOpenedAndClosed() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            var second = window.active();
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.command(TuinkCommand.HELP_KEY_HINTS);
            window.event(KeyStroke.of(KeyStroke.Key.PAGE_DOWN));
            int firstRow = window.host.helpScrollState().firstVisibleIndex();
            assertTrue(firstRow > 0);
            assertIndicators(window, first, false, true);

            window.command(TuinkCommand.TAB_NEXT);
            assertTrue(second.uiState().modalState.isNone());
            window.command(TuinkCommand.HELP_AUTHOR);
            assertTrue(second.uiState().modalState.isHelpAuthor());
            assertEquals(0, window.host.helpScrollState().firstVisibleIndex());
            assertIndicators(window, first, false, true);
            assertIndicators(window, second, false, true);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(second.uiState().modalState.isNone());
            assertIndicators(window, first, false, true);
            assertIndicators(window, second, false, false);
            window.command(TuinkCommand.TAB_PREVIOUS);

            assertTrue(first.uiState().modalState.isHelpHints());
            assertEquals(firstRow, window.host.helpScrollState().firstVisibleIndex());
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            assertEquals(0, window.host.helpScrollState().firstVisibleIndex());
            assertEquals(List.of("original"), first.tabSession().model().snapshotLines());
            assertFalse(first.state().isDirty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inactiveHelpKeepsItsSourceAnchorWhenAnotherTabResizesTheTerminal(boolean otherHelp) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.showTabBarSetting = true;
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.command(TuinkCommand.HELP_KEY_HINTS);
            for (int row = 0; row < 12; row++) {
                window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
            }
            var before = window.host.helpOverlayPlacement(window.height, window.width, HelpPages.KEYMAP, 2);
            assertTrue(window.screenRow(before.content().contentBounds().y()).contains("Ctrl+X / Ctrl+C"));

            window.command(TuinkCommand.TAB_NEXT);
            if (otherHelp) window.command(TuinkCommand.HELP_AUTHOR);
            window.resize(24, 40);
            window.command(TuinkCommand.TAB_PREVIOUS);

            assertSame(first.uiState(), window.active().uiState());
            assertTrue(first.uiState().modalState.isHelpHints());
            var after = window.host.helpOverlayPlacement(window.height, window.width, HelpPages.KEYMAP, 2);
            assertTrue(window.screenRow(after.content().contentBounds().y()).contains("Ctrl+X / Ctrl+C"));
            assertTrue(after.content().state().firstVisibleIndex() > before.content().state().firstVisibleIndex());
        }
    }

    @Test
    void reopeningGlobalQuitResetsItsSafeChoiceAndPreservesTabControls() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.confirmOnCloseSetting = true;
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            var second = window.active();
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.event(new TextInput("draft "));
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("missing-a"));
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            Path target = directory.resolve("pending-name.txt");
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(target.toString()));
            var localPrompt = first.uiState().modalState;
            window.command(TuinkCommand.TAB_NEXT);
            window.command(TuinkCommand.SEARCH_OPEN);
            window.event(new TextInput("missing-b"));

            window.command(TuinkCommand.APP_QUIT);

            assertTrue(window.controller.running());
            assertFalse(hasRow(window, "Find:"));
            assertTrue(window.host.confirmFocusManager().activeScopeId().isPresent());
            assertEquals(ConfirmChoice.CANCEL, window.host.confirmFocusManager().focused().orElseThrow());
            window.event(KeyStroke.of(KeyStroke.Key.HOME));
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            assertEquals(ConfirmChoice.DISCARD, window.host.confirmFocusManager().focused().orElseThrow());
            assertSame(localPrompt, first.uiState().modalState);
            assertEquals(target.toString(), first.uiState().saveAsInput.text());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));

            assertSame(second.uiState(), window.active().uiState());
            assertTrue(window.controller.running());
            assertTrue(hasRow(window, "Find:"));
            assertEquals("missing-b", window.query.text());
            assertEquals(SearchFocus.FIND, window.host.searchFocusManager().focused().orElseThrow());
            assertSame(localPrompt, first.uiState().modalState);
            assertIndicators(window, first, true, true);
            assertIndicators(window, second, true, false);
            window.command(TuinkCommand.APP_QUIT);
            assertEquals(ConfirmChoice.CANCEL, window.host.confirmFocusManager().focused().orElseThrow());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertTrue(window.controller.running(), "a fresh Quit question must not inherit Discard focus");
            assertSame(second.uiState(), window.active().uiState());
            assertEquals("missing-b", window.query.text());
            assertTrue(hasRow(window, "Find:"));
            window.command(TuinkCommand.TAB_PREVIOUS);
            assertSame(localPrompt, first.uiState().modalState);
            assertEquals(target.toString(), window.filename.text());
            assertEquals("missing-a", window.query.text());
            assertFalse(Files.exists(target));
            assertEquals("original", Files.readString(window.document));
        }
    }

    @Test
    void globalSaveAndQuitReturnsToAnInactiveOverwriteQuestionBeforeWritingAnyDocument() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.confirmOnCloseSetting = true;
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            var second = window.active();
            window.event(new TextInput("second draft"));
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.event(new TextInput("draft "));
            Path target = Files.writeString(directory.resolve("existing.txt"), "existing");
            SaveWorkflowIntegrationTest.submitFilename(window, target);
            var question = first.uiState().modalState;
            var approval = first.uiState().overwriteApproval;
            assertTrue(question.isSaveOverwriteConfirm());
            window.command(TuinkCommand.TAB_NEXT);

            window.command(TuinkCommand.APP_QUIT);
            window.event(new TextInput("y"));

            assertTrue(window.controller.running());
            assertFalse(window.documentIo.busy());
            assertEquals(2, window.tabs.size());
            assertSame(first.uiState(), window.active().uiState());
            assertSame(question, first.uiState().modalState);
            assertSame(approval, first.uiState().overwriteApproval);
            assertEquals(target.toString(), window.filename.text());
            assertIndicators(window, first, false, true);
            assertTrue(first.state().isDirty());
            assertTrue(second.state().isDirty());
            assertEquals(List.of("draft original"), first.tabSession().model().snapshotLines());
            assertEquals(List.of("second draft"), second.tabSession().model().snapshotLines());
            assertEquals("existing", Files.readString(target));
            assertEquals("original", Files.readString(window.document));
        }
    }

    private static void assertIndicators(EditorWindowFixture window, TerminalEditorApp.EditorTab tab,
                                         boolean searching, boolean question) {
        int index = window.context.indexOf(tab.documentSession().viewId());
        var item = window.host.toTabItems(window.tabs, null).get(index);
        assertEquals(searching, item.searching());
        assertEquals(question, item.awaitingAnswer());
        String title = TabBarView.formatTabTitle(item.file(), item.dirty(), item.hasMatch(),
            item.searching(), item.awaitingAnswer());
        assertTrue(window.screenRow(1).contains(title), window.screenRow(1));
    }

    private static boolean hasRow(EditorWindowFixture window, String prefix) {
        for (int row = 0; row < window.height; row++) {
            if (window.screenRow(row).startsWith(prefix)) return true;
        }
        return false;
    }

    private static void clickTab(EditorWindowFixture window, int column) {
        window.pointer(PointerEvent.Kind.PRESS, 1, column);
        window.pointer(PointerEvent.Kind.RELEASE, 1, column);
    }

    private static final class ControlledExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }

        void runNextOnWorker() throws InterruptedException {
            Thread thread = new Thread(tasks.removeFirst(), "tab-ui-save-worker");
            thread.start();
            thread.join(5_000L);
            assertFalse(thread.isAlive(), "detached save inspection did not finish");
        }

        void finishQueued() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }
    }
}
