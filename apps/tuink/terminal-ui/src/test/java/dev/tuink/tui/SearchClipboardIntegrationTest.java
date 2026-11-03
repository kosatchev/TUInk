package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.TextField;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.TextModel;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;

/** Exercises search and clipboard behavior through the production window event route. */
class SearchClipboardIntegrationTest {
    @TempDir
    Path directory;
    private final List<EditorWindowFixture> windows = new ArrayList<>();

    @AfterEach
    void closeWindows() {
        windows.forEach(EditorWindowFixture::close);
    }

    @ParameterizedTest
    @EnumSource(value = SearchFocus.class, names = {"FIND", "REPLACE"}, mode = EnumSource.Mode.EXCLUDE)
    @NullSource
    void clipboardShortcutsOnButtonsTogglesOrNoFocusAreConsumedWithoutChangingEditorState(
        SearchFocus target
    ) throws Exception {
        for (boolean readingMode : List.of(false, true)) {
            Fixture fixture = new Fixture(target, readingMode, true);
            fixture.clipboard.setText("unchanged clipboard😀");

            for (KeyStroke.Key key : List.of(KeyStroke.Key.C, KeyStroke.Key.X, KeyStroke.Key.V)) {
                fixture.dispatch(key);

                assertEquals("find😀", fixture.query.text());
                assertEquals("replace😀", fixture.replacement.text());
                assertEquals("find😀".length(), fixture.query.cursor());
                assertEquals("replace😀".length(), fixture.replacement.cursor());
                assertEquals("unchanged clipboard😀", fixture.clipboard.text());
                fixture.assertDocumentUnchanged();
            }
            fixture.assertHistoryUnchanged();
            assertEquals("unchanged clipboard😀", fixture.clipboard.text());
        }
    }

    @ParameterizedTest
    @EnumSource(SearchFocus.class)
    @NullSource
    void undoAndRedoCannotReachDocumentHistoryWhileTheSearchPanelOwnsFocus(SearchFocus target) throws Exception {
        for (boolean readingMode : List.of(false, true)) {
            Fixture fixture = new Fixture(target, readingMode, true);
            fixture.clipboard.setText("unchanged clipboard");

            for (KeyStroke.Key key : List.of(KeyStroke.Key.Z, KeyStroke.Key.Y)) {
                fixture.dispatch(key);

                fixture.assertDocumentUnchanged();
                assertEquals("find😀", fixture.query.text());
                assertEquals("replace😀", fixture.replacement.text());
                assertEquals("unchanged clipboard", fixture.clipboard.text());
            }
            fixture.assertHistoryUnchanged();
        }
    }

    @Test
    void clipboardShortcutsCannotEditAnInvisibleFieldOrFallThroughToTheDocument() throws Exception {
        Fixture fixture = new Fixture(SearchFocus.FIND, false);
        fixture.clipboard.setText("clipboard");

        fixture.window.resize(2, 100);
        assertTrue(fixture.focus.focused().isEmpty());
        for (KeyStroke.Key key : List.of(KeyStroke.Key.C, KeyStroke.Key.X, KeyStroke.Key.V)) {
            fixture.window.event(KeyStroke.of(key, KeyStroke.Modifier.CTRL));
            assertEquals("find😀", fixture.query.text());
            assertEquals("replace😀", fixture.replacement.text());
            assertEquals("clipboard", fixture.clipboard.text());
            fixture.assertDocumentUnchanged();
        }
    }

    @ParameterizedTest
    @CsvSource({"FIND,false", "FIND,true", "REPLACE,false", "REPLACE,true"})
    void focusedCopyAndCutUseTheFieldWithoutChangingTheDocument(
        SearchFocus target, boolean readingMode
    ) throws Exception {
        Fixture fixture = new Fixture(target, readingMode);
        fixture.clipboard.setText("previous clipboard");
        String value = fixture.field().text();
        String otherValue = fixture.otherField().text();

        fixture.dispatch(KeyStroke.Key.C);
        fixture.dispatch(KeyStroke.Key.X);
        assertEquals("previous clipboard", fixture.clipboard.text());
        assertEquals(value, fixture.field().text());
        fixture.field().selectAll();

        fixture.dispatch(KeyStroke.Key.C);
        assertEquals(value, fixture.clipboard.text());
        assertEquals(value, fixture.field().text());
        fixture.assertDocumentUnchanged();

        fixture.dispatch(KeyStroke.Key.X);
        assertEquals(value, fixture.clipboard.text());
        assertEquals("", fixture.field().text());
        assertEquals(otherValue, fixture.otherField().text());
        fixture.assertDocumentUnchanged();

        // An empty field must consume both shortcuts, preserving the clipboard.
        fixture.dispatch(KeyStroke.Key.C);
        fixture.dispatch(KeyStroke.Key.X);
        assertEquals(value, fixture.clipboard.text());
        fixture.assertDocumentUnchanged();
    }

    @ParameterizedTest
    @CsvSource({"FIND,false", "FIND,true", "REPLACE,false", "REPLACE,true"})
    void focusedPasteJoinsLinesAndPreservesTabsAtTheFieldCaret(
        SearchFocus target, boolean readingMode
    ) throws Exception {
        Fixture fixture = new Fixture(target, readingMode);
        fixture.field().set("a😀b");
        fixture.field().moveLeft();
        fixture.clipboard.setText("x\r\n\ty\u0001\nz");
        String otherValue = fixture.otherField().text();

        fixture.dispatch(KeyStroke.Key.V);

        assertEquals("a😀x \ty zb", fixture.field().text());
        assertEquals("a😀x \ty z".length(), fixture.field().cursor());
        assertEquals(otherValue, fixture.otherField().text());
        fixture.assertDocumentUnchanged();
    }

    @ParameterizedTest
    @EnumSource(SearchFocus.class)
    void physicalNavigationAndSelectionNeverLeakIntoTheDocument(SearchFocus target) throws Exception {
        Fixture fixture = new Fixture(target, false);
        for (KeyStroke stroke : List.of(
            KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.CTRL),
            KeyStroke.of(KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.CTRL),
            KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT),
            KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT),
            KeyStroke.of(KeyStroke.Key.HOME, KeyStroke.Modifier.SHIFT),
            KeyStroke.of(KeyStroke.Key.END, KeyStroke.Modifier.CTRL),
            KeyStroke.of(KeyStroke.Key.ARROW_UP, KeyStroke.Modifier.SHIFT),
            KeyStroke.of(KeyStroke.Key.PAGE_DOWN, KeyStroke.Modifier.SHIFT),
            KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL)
        )) {
            fixture.dispatchStroke(stroke);
            fixture.assertDocumentUnchanged();
        }
        if (target == SearchFocus.FIND || target == SearchFocus.REPLACE) {
            assertEquals(fixture.field().text(), fixture.field().selectedText());
        } else {
            assertFalse(fixture.query.hasSelection());
            assertFalse(fixture.replacement.hasSelection());
        }
    }

    @ParameterizedTest
    @EnumSource(SearchFocus.class)
    void semanticNavigationAndSelectAllAlsoRemainInsideTheSearchPanel(SearchFocus target) throws Exception {
        Fixture fixture = new Fixture(target, false);
        for (TuinkCommand command : List.of(
            TuinkCommand.CURSOR_WORD_LEFT, TuinkCommand.CURSOR_WORD_RIGHT,
            TuinkCommand.CURSOR_SELECT_LEFT, TuinkCommand.CURSOR_SELECT_RIGHT,
            TuinkCommand.CURSOR_SELECT_UP, TuinkCommand.CURSOR_SELECT_DOWN,
            TuinkCommand.CURSOR_HOME, TuinkCommand.CURSOR_END, TuinkCommand.EDIT_SELECT_ALL
        )) {
            fixture.dispatchCommand(command);
            fixture.assertDocumentUnchanged();
        }
        if (target == SearchFocus.FIND || target == SearchFocus.REPLACE) {
            assertEquals(fixture.field().text(), fixture.field().selectedText());
        }
    }

    @Test
    void pageNavigationScrollsPlainAndWrappedDocumentsWithoutMovingEitherCaretOrSelection() throws Exception {
        for (boolean wrapped : List.of(false, true)) {
            List<String> document = new ArrayList<>();
            for (int line = 0; line < 80; line++) {
                document.add("document " + line + "x".repeat(390));
            }
            Fixture fixture = new Fixture(SearchFocus.FIND, false, false, document);
            fixture.ui.wrapLinesSetting = wrapped;
            fixture.ui.wrapWordsSetting = false;
            int fieldCaret = fixture.query.cursor();
            fixture.dispatchStroke(KeyStroke.of(KeyStroke.Key.PAGE_DOWN));
            assertTrue(fixture.state.getTopLine() > 0 || fixture.state.getTopWrapSegment() > 0);
            fixture.assertDocumentUnchanged();
            assertEquals(fieldCaret, fixture.query.cursor());
            assertEquals(SearchFocus.FIND, fixture.focus.focused().orElseThrow());
            fixture.dispatchStroke(KeyStroke.of(KeyStroke.Key.PAGE_UP));
            assertEquals(0, fixture.state.getTopLine());
            assertEquals(0, fixture.state.getTopWrapSegment());
            fixture.assertDocumentUnchanged();
            assertEquals(fieldCaret, fixture.query.cursor());
        }
    }

    @ParameterizedTest
    @CsvSource({"FIND,false", "FIND,true", "REPLACE,false", "REPLACE,true"})
    void editMenuUsesTheSameFieldSelectionAndClipboardAsKeyboardShortcuts(
        SearchFocus target, boolean readingMode
    ) throws Exception {
        Fixture fixture = new Fixture(target, readingMode);
        String value = fixture.field().text();
        String otherValue = fixture.otherField().text();
        fixture.clipboard.setText("previous clipboard");

        // The original failure: Ctrl+A in the field, F2, Down, Down, Enter (Cut).
        fixture.dispatchStroke(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
        fixture.dispatchMenu(TuinkCommand.EDIT_CUT);
        assertEquals("", fixture.field().text());
        assertEquals(value, fixture.clipboard.text());
        fixture.assertDocumentUnchanged();

        fixture.dispatchMenu(TuinkCommand.EDIT_PASTE);
        assertEquals(value, fixture.field().text());
        fixture.dispatchMenu(TuinkCommand.EDIT_SELECT_ALL);
        assertEquals(value, fixture.field().selectedText());
        fixture.clipboard.setText("previous clipboard");
        fixture.dispatchMenu(TuinkCommand.EDIT_COPY);
        assertEquals(value, fixture.clipboard.text());

        fixture.clipboard.setText("new\r\nvalue");
        fixture.dispatchMenu(TuinkCommand.EDIT_PASTE);
        assertEquals("new value", fixture.field().text());
        assertEquals(otherValue, fixture.otherField().text());
        fixture.assertDocumentUnchanged();
    }

    @ParameterizedTest
    @EnumSource(value = SearchFocus.class, names = {"FIND", "REPLACE"}, mode = EnumSource.Mode.EXCLUDE)
    @NullSource
    void editMenuCannotChangeFieldsOrDocumentWhenAButtonOrNoControlOwnsFocus(SearchFocus target)
        throws Exception {
        Fixture fixture = new Fixture(target, false, true);
        fixture.clipboard.setText("unchanged clipboard");
        for (TuinkCommand command : List.of(
            TuinkCommand.EDIT_SELECT_ALL, TuinkCommand.EDIT_COPY, TuinkCommand.EDIT_CUT,
            TuinkCommand.EDIT_PASTE, TuinkCommand.EDIT_UNDO, TuinkCommand.EDIT_REDO,
            TuinkCommand.EDIT_TOGGLE_LINE_COMMENT
        )) {
            fixture.dispatchMenu(command);
            assertEquals("find😀", fixture.query.text());
            assertEquals("replace😀", fixture.replacement.text());
            assertFalse(fixture.query.hasSelection());
            assertFalse(fixture.replacement.hasSelection());
            assertEquals("unchanged clipboard", fixture.clipboard.text());
            fixture.assertDocumentUnchanged();
        }
        fixture.assertHistoryUnchanged();
    }

    @ParameterizedTest
    @EnumSource(value = SearchFocus.class, names = {"FIND", "REPLACE"})
    void undoAndRedoFromTheEditMenuCannotReachDocumentHistoryFromAField(SearchFocus target) throws Exception {
        Fixture fixture = new Fixture(target, false, true);
        fixture.dispatchMenu(TuinkCommand.EDIT_UNDO);
        fixture.dispatchMenu(TuinkCommand.EDIT_REDO);
        fixture.assertDocumentUnchanged();
        fixture.assertHistoryUnchanged();
    }

    @Test
    void editMenuUsesDocumentSelectionWhenTheDocumentOwnsSearchFocus() throws Exception {
        Fixture fixture = new Fixture(SearchFocus.FIND, false);
        fixture.focusDocument();
        fixture.dispatchMenu(TuinkCommand.EDIT_SELECT_ALL);
        fixture.dispatchMenu(TuinkCommand.EDIT_CUT);
        assertEquals(List.of(""), fixture.model.snapshotLines());
        assertEquals("document", fixture.clipboard.text());
        assertEquals("find😀", fixture.query.text());
        assertFalse(fixture.query.hasSelection());
        fixture.dispatchMenu(TuinkCommand.EDIT_UNDO);
        assertEquals(List.of("document"), fixture.model.snapshotLines());
    }

    private final class Fixture {
        private final EditorWindowFixture window;
        private final TextField query;
        private final TextField replacement;
        private final UiSessionState ui;
        private final EditorClipboard clipboard;
        private final FocusManager<SearchFocus> focus;
        private final SearchFocus target;
        private final TextModel model;
        private final EditorState state;
        private final EditorEngine engine;
        private final List<String> initialLines;
        private final long initialRevision;
        private final boolean initiallyDirty;
        private boolean documentFocused;

        private Fixture(SearchFocus target, boolean readingMode) throws Exception {
            this(target, readingMode, false);
        }

        private Fixture(SearchFocus target, boolean readingMode, boolean primeHistory) throws Exception {
            this(target, readingMode, primeHistory, List.of("document"));
        }

        private Fixture(SearchFocus target, boolean readingMode, boolean primeHistory, List<String> document)
            throws Exception {
            this.target = target;
            window = new EditorWindowFixture(Files.createTempDirectory(directory, "window-"), readingMode, document);
            windows.add(window);
            query = window.query;
            replacement = window.replacement;
            ui = window.ui;
            clipboard = window.host.clipboard;
            focus = window.host.searchFocusManager();
            model = window.active().tabSession().model();
            state = window.active().state();
            engine = window.active().tabSession().engine();
            if (primeHistory) {
                engine.restoreSelection(new Position(0, "document".length()), null);
                engine.insertInput("1");
                engine.insertNewline();
                assertTrue(engine.undo());
            }
            engine.restoreSelection(new Position(0, 3), new Position(0, 0));
            window.frame();
            window.event(KeyStroke.of(KeyStroke.Key.F, KeyStroke.Modifier.CTRL));
            query.set("find😀");
            replacement.set("replace😀");
            window.frame();
            window.focusSearch(target);
            initialLines = model.snapshotLines();
            initialRevision = model.revision();
            initiallyDirty = state.isDirty();
        }

        private TextField field() {
            return target == SearchFocus.FIND ? query : replacement;
        }

        private TextField otherField() {
            return target == SearchFocus.FIND ? replacement : query;
        }

        private void dispatch(KeyStroke.Key key) {
            dispatchStroke(KeyStroke.of(key, KeyStroke.Modifier.CTRL));
        }

        private void dispatchStroke(KeyStroke stroke) {
            window.focusSearch(target);
            window.frame();
            window.event(stroke);
        }

        private void dispatchCommand(TuinkCommand command) {
            window.focusSearch(target);
            window.frame();
            window.command(command);
        }

        private void dispatchMenu(TuinkCommand command) {
            if (documentFocused) {
                window.focusDocument();
            } else {
                window.focusSearch(target);
            }
            window.frame();
            List<TuinkCommand> editEntries = List.of(
                TuinkCommand.EDIT_UNDO, TuinkCommand.EDIT_REDO, TuinkCommand.EDIT_CUT,
                TuinkCommand.EDIT_COPY, TuinkCommand.EDIT_PASTE, TuinkCommand.EDIT_TOGGLE_LINE_COMMENT,
                TuinkCommand.EDIT_SELECT_ALL, TuinkCommand.SEARCH_OPEN
            );
            int index = editEntries.indexOf(command);
            assertTrue(index >= 0, "command must appear in Edit menu");
            window.event(KeyStroke.of(KeyStroke.Key.F2));
            assertTrue(window.menu.isOpen());
            var descriptors = TopMenuView.build(ui, List.of(), -1, "Cyan", new CommandRouter(),
                window.menu.state(), window.controller.activeSurface());
            var item = (dev.tuiframe.widget.MenuBar.Item<?>) descriptors.menus()
                .get(TopMenuController.EDIT_INDEX).entries().get(index);
            if (!item.enabled()) {
                // An unavailable command has no keyboard activation; close without invoking another row.
                window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            } else {
                for (int step = 0; window.menu.state().selectedEntryIndex() != index && step < editEntries.size(); step++) {
                    window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
                }
                assertEquals(index, window.menu.state().selectedEntryIndex());
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            }
            assertFalse(window.menu.isOpen());
        }

        private void focusDocument() {
            window.focusDocument();
            documentFocused = true;
        }

        private void assertDocumentUnchanged() {
            assertEquals(initialLines, model.snapshotLines());
            assertEquals(initialRevision, model.revision());
            assertEquals(initiallyDirty, state.isDirty());
            assertEquals(new Position(0, 3), state.getCursor());
            assertEquals(new Position(0, 0), state.getSelectionAnchor());
        }

        private void assertHistoryUnchanged() {
            // Leave search through the production route, then inspect history
            // with the same Undo/Redo bindings available to the user.
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            ui.readingModeSetting = false;
            window.frame();
            window.event(KeyStroke.of(KeyStroke.Key.Z, KeyStroke.Modifier.CTRL));
            assertEquals(List.of("document"), model.snapshotLines());
            window.event(KeyStroke.of(KeyStroke.Key.Y, KeyStroke.Modifier.CTRL));
            assertEquals(List.of("document1"), model.snapshotLines());
            window.event(KeyStroke.of(KeyStroke.Key.Y, KeyStroke.Modifier.CTRL));
            assertEquals(List.of("document1", ""), model.snapshotLines());
            long revision = model.revision();
            window.event(KeyStroke.of(KeyStroke.Key.Y, KeyStroke.Modifier.CTRL));
            assertEquals(revision, model.revision(), "the original redo chain must end here");
        }
    }
}
