package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.MenuBar;
import dev.tuiframe.widget.TextField;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.Position;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.input.EditorInput;
import dev.tuink.tui.ui.SearchBarView;
import dev.tuink.editor.command.CommandInvocation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;

/** Exercises physical routing and the real search-mode dispatcher together. */
class SearchClipboardIntegrationTest {
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

        for (KeyStroke.Key key : List.of(KeyStroke.Key.C, KeyStroke.Key.X, KeyStroke.Key.V)) {
            fixture.dispatch(key, new Rect(0, 0, 100, 2));
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
    void focusedPasteNormalizesMultilineClipboardAtTheFieldCaret(
        SearchFocus target, boolean readingMode
    ) throws Exception {
        Fixture fixture = new Fixture(target, readingMode);
        fixture.field().set("a😀b");
        fixture.field().moveLeft();
        fixture.clipboard.setText("x\r\n\ty\u0001\nz");
        String otherValue = fixture.otherField().text();

        fixture.dispatch(KeyStroke.Key.V);

        assertEquals("a😀x y zb", fixture.field().text());
        assertEquals("a😀x y z".length(), fixture.field().cursor());
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
            fixture.preparePlacement(new Rect(0, 0, 100, 24));
            fixture.invokeSearch(EditorInput.command(command));
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
        fixture.ui.searchEditorFocused = true;
        fixture.dispatchMenu(TuinkCommand.EDIT_SELECT_ALL);
        fixture.dispatchMenu(TuinkCommand.EDIT_CUT);
        assertEquals(List.of(""), fixture.model.snapshotLines());
        assertEquals("document", fixture.clipboard.text());
        assertEquals("find😀", fixture.query.text());
        assertFalse(fixture.query.hasSelection());
        fixture.dispatchMenu(TuinkCommand.EDIT_UNDO);
        assertEquals(List.of("document"), fixture.model.snapshotLines());
    }

    private static final class Fixture {
        private final TerminalEditorApp app = new TerminalEditorApp();
        private final TextField query = new TextField();
        private final TextField replacement = new TextField();
        private final UiSessionState ui = new UiSessionState();
        private final CommandRouter commandRouter = new CommandRouter();
        private final EditorClipboard clipboard;
        private final FocusManager<SearchFocus> focus;
        private final SearchFocus target;
        private final TextModel model;
        private final EditorState state = new EditorState();
        private final EditorEngine engine;
        private final Object notification;
        private final Method searchHandler;
        private final Method consumedAccessor;
        private final List<String> initialLines;
        private final long initialRevision;
        private final boolean initiallyDirty;
        private SearchBarView.Placement currentPlacement;

        private Fixture(SearchFocus target, boolean readingMode) throws Exception {
            this(target, readingMode, false);
        }

        @SuppressWarnings("unchecked")
        private Fixture(SearchFocus target, boolean readingMode, boolean primeHistory) throws Exception {
            this(target, readingMode, primeHistory, List.of("document"));
        }

        @SuppressWarnings("unchecked")
        private Fixture(
            SearchFocus target, boolean readingMode, boolean primeHistory, List<String> document
        ) throws Exception {
            this.target = target;
            clipboard = (EditorClipboard) appField("clipboard");
            focus = (FocusManager<SearchFocus>) appField("searchFocusManager");
            query.set("find😀");
            replacement.set("replace😀");
            ui.searchMode = true;
            ui.readingModeSetting = readingMode;
            SimpleLineBuffer buffer = new SimpleLineBuffer();
            buffer.setLine(0, document.getFirst());
            for (int line = 1; line < document.size(); line++) {
                buffer.insertLine(line, document.get(line));
            }
            model = new TextModel(buffer);
            engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model), clipboard);
            if (primeHistory) {
                engine.restoreSelection(new Position(0, "document".length()), null);
                engine.insertInput("1");
                engine.insertNewline();
                assertTrue(engine.undo());
            }
            engine.restoreSelection(new Position(0, 3), new Position(0, 0));
            initialLines = model.snapshotLines();
            initialRevision = model.revision();
            initiallyDirty = state.isDirty();
            Class<?> notificationType = Class.forName(
                "dev.tuink.tui.TerminalEditorApp$NotificationState"
            );
            Constructor<?> constructor = notificationType.getDeclaredConstructor(TerminalEditorApp.class);
            constructor.setAccessible(true);
            notification = constructor.newInstance(app);
            searchHandler = Arrays.stream(TerminalEditorApp.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("handleSearchModeInput"))
                .findFirst().orElseThrow();
            searchHandler.setAccessible(true);
            consumedAccessor = searchHandler.getReturnType().getDeclaredMethod("consumed");
            consumedAccessor.setAccessible(true);
        }

        private Object appField(String name) throws Exception {
            Field field = TerminalEditorApp.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(app);
        }

        private TextField field() {
            return target == SearchFocus.FIND ? query : replacement;
        }

        private TextField otherField() {
            return target == SearchFocus.FIND ? replacement : query;
        }

        private void dispatch(KeyStroke.Key key) throws Exception {
            dispatch(key, new Rect(0, 0, 100, 24));
        }

        private void dispatch(KeyStroke.Key key, Rect clip) throws Exception {
            SearchBarView.Placement placement = preparePlacement(clip);
            KeyStroke stroke = KeyStroke.of(key, KeyStroke.Modifier.CTRL);
            assertFalse(SearchInputRouter.route(focus, placement, stroke).consumed());
            EditorInput input = TerminalEditorApp.resolveEditorInput(
                commandRouter, stroke, commandRouter.invocationFor(stroke).orElseThrow(),
                null, true, target
            );
            assertNotNull(input);
            invokeSearch(input);
        }

        private SearchBarView.Placement preparePlacement(Rect clip) {
            SearchBarView.Placement placement = SearchBarView.build(
                query, replacement, 100, false, SearchOptions.MatchMode.SUBSTRING, false
            ).place("search", 20, clip);
            placement.activate(focus);
            if (target == null) {
                focus.clear(placement.focusScope());
            } else {
                focus.focus(placement.focusScope(), target);
            }
            currentPlacement = placement;
            return placement;
        }

        private void dispatchStroke(KeyStroke stroke) throws Exception {
            SearchBarView.Placement placement = preparePlacement(new Rect(0, 0, 100, 24));
            SearchInputRouter.Result raw = SearchInputRouter.route(focus, placement, stroke);
            if (raw.consumed()) {
                assertEquals(null, raw.action(), "navigation must not accidentally activate a search action");
                return;
            }
            EditorInput input = TerminalEditorApp.resolveEditorInput(
                commandRouter, stroke, commandRouter.invocationFor(stroke).orElseThrow(),
                null, true, target
            );
            assertNotNull(input);
            invokeSearch(input);
        }

        private void dispatchMenu(TuinkCommand command) throws Exception {
            preparePlacement(new Rect(0, 0, 100, 24));
            List<TuinkCommand> editEntries = List.of(
                TuinkCommand.EDIT_UNDO, TuinkCommand.EDIT_REDO, TuinkCommand.EDIT_CUT,
                TuinkCommand.EDIT_COPY, TuinkCommand.EDIT_PASTE, TuinkCommand.EDIT_TOGGLE_LINE_COMMENT,
                TuinkCommand.EDIT_SELECT_ALL, TuinkCommand.SEARCH_OPEN
            );
            TopMenuController menu = new TopMenuController();
            TopMenuInputRouter.Result opened = routeMenu(menu, KeyStroke.of(KeyStroke.Key.F2));
            assertTrue(opened.consumed());
            assertEquals(null, opened.command());
            for (int i = 0; i < editEntries.indexOf(command); i++) {
                TopMenuInputRouter.Result moved = routeMenu(menu, KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
                assertTrue(moved.consumed());
                assertEquals(null, moved.command());
            }
            KeyStroke enter = KeyStroke.of(KeyStroke.Key.ENTER);
            CommandInvocation selected = routeMenu(menu, enter).command();
            assertNotNull(selected);
            assertEquals(command.id(), selected.id());
            assertEquals(MenuBar.State.closed(), menu.state());
            EditorInput input = TerminalEditorApp.resolveEditorInput(
                commandRouter, enter, selected, commandRouter.dispatch(selected).orElseThrow(), false, target
            );
            assertEquals(command, input.command(), "the menu's Enter must retain its semantic command");
            if (TerminalEditorApp.searchOwnsCommand(
                ui.searchMode, ui.searchEditorFocused, true, input.command()
            )) {
                invokeSearch(input);
            } else {
                assertTrue(InputActionDispatcher.dispatch(input.command(), ui,
                    new InputActionDispatcher.DispatchContext(engine, state, menu, enabled -> { }, 20, 100)
                ).handled());
            }
        }

        private TopMenuInputRouter.Result routeMenu(TopMenuController menu, TuiInputEvent event) {
            TopMenuView.Placement placement = TopMenuView.build(
                ui, List.of(), -1, "Cyan", commandRouter, menu.state()
            ).place(new Rect(0, 0, 100, 24));
            return TopMenuInputRouter.route(menu, placement, commandRouter, event);
        }

        private void invokeSearch(EditorInput input) throws Exception {
            Object result = searchHandler.invoke(
                app, input, ui, SearchController.SearchUiOptions.DEFAULT,
                query, replacement, currentPlacement, List.of(), 0, model, state, engine,
                null, 20, 100, true, UiModalState.none(), new TextField(), null,
                notification, false, null, OpenFileInputRouter.Focus.LIST, null,
                (Consumer<Boolean>) enabled -> { }, null
            );
            assertEquals(Boolean.TRUE, consumedAccessor.invoke(result), "the search panel must consume the input");
        }

        private void assertDocumentUnchanged() {
            assertEquals(initialLines, model.snapshotLines());
            assertEquals(initialRevision, model.revision());
            assertEquals(initiallyDirty, state.isDirty());
            assertEquals(new Position(0, 3), state.getCursor());
            assertEquals(new Position(0, 0), state.getSelectionAnchor());
        }

        private void assertHistoryUnchanged() {
            assertTrue(engine.undo(), "the existing undo entry must survive clipboard input");
            assertEquals(List.of("document"), model.snapshotLines());
            assertTrue(engine.redo());
            assertEquals(List.of("document1"), model.snapshotLines());
            assertTrue(engine.redo(), "the existing redo entry must survive clipboard input");
            assertEquals(List.of("document1", ""), model.snapshotLines());
            assertFalse(engine.redo());
        }
    }
}
