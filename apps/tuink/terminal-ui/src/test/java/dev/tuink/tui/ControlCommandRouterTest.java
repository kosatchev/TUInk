package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.TextField;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.SearchOptions;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuink.tui.ui.SearchBarView;
import java.util.List;
import org.junit.jupiter.api.Test;

class ControlCommandRouterTest {
    private static final Rect CLIP = new Rect(0, 0, 80, 24);

    @Test
    void semanticFieldNavigationAndEditingHaveTheSameResultAsPhysicalInputForBothSearchFields() {
        List<Binding> bindings = List.of(
            new Binding(TuinkCommand.CURSOR_LEFT, KeyStroke.of(KeyStroke.Key.ARROW_LEFT)),
            new Binding(TuinkCommand.SEARCH_NAVIGATE_LEFT, KeyStroke.of(KeyStroke.Key.ARROW_LEFT)),
            new Binding(TuinkCommand.CURSOR_WORD_LEFT, KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.CTRL)),
            new Binding(TuinkCommand.CURSOR_SELECT_LEFT,
                KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT)),
            new Binding(TuinkCommand.CURSOR_HOME, KeyStroke.of(KeyStroke.Key.HOME)),
            new Binding(TuinkCommand.SEARCH_FIELD_HOME, KeyStroke.of(KeyStroke.Key.HOME)),
            new Binding(TuinkCommand.EDIT_BACKSPACE, KeyStroke.of(KeyStroke.Key.BACKSPACE)),
            new Binding(TuinkCommand.SEARCH_FIELD_BACKSPACE, KeyStroke.of(KeyStroke.Key.BACKSPACE)),
            new Binding(TuinkCommand.EDIT_SELECT_ALL, KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL))
        );
        for (SearchFocus recipient : List.of(SearchFocus.FIND, SearchFocus.REPLACE)) {
            for (Binding binding : bindings) {
                SearchControls semantic = search("alpha 👩‍💻 beta", "replacement");
                SearchControls physical = search("alpha 👩‍💻 beta", "replacement");
                semantic.focus.focus(semantic.placement.focusScope(), recipient);
                physical.focus.focus(physical.placement.focusScope(), recipient);
                SearchInputRouter.Result translated = SearchInputRouter.routeCommand(semantic.focus,
                    semantic.placement, binding.command, new EditorClipboard());
                SearchInputRouter.Result raw = SearchInputRouter.route(physical.focus, physical.placement, binding.key);
                assertEquals(raw, translated, binding.command.name());
                assertFieldEquals(physical.query, semantic.query);
                assertFieldEquals(physical.replacement, semantic.replacement);
                assertEquals(physical.focus.focused(), semantic.focus.focused());
            }
        }
    }

    @Test
    void searchClipboardUsesTheFocusedEnabledFieldAndReportsOnlyActualValueChanges() {
        SearchControls controls = search("find", "replace 👩‍💻");
        controls.focus.focus(controls.placement.focusScope(), SearchFocus.REPLACE);
        EditorClipboard clipboard = new EditorClipboard();
        clipboard.setText("old clipboard");
        controls.replacement.selectAll();
        SearchInputRouter.Result copied = SearchInputRouter.routeCommand(controls.focus, controls.placement,
            TuinkCommand.EDIT_COPY, clipboard);
        assertTrue(copied.consumed());
        assertNull(copied.action());
        assertEquals("replace 👩‍💻", clipboard.text());
        SearchInputRouter.Result cut = SearchInputRouter.routeCommand(controls.focus, controls.placement,
            TuinkCommand.EDIT_CUT, clipboard);
        assertEquals(SearchBarView.Action.REPLACEMENT_CHANGED, cut.action());
        assertEquals("", controls.replacement.text());
        assertEquals("find", controls.query.text());
        SearchInputRouter.Result pasted = SearchInputRouter.routeCommand(controls.focus, controls.placement,
            TuinkCommand.EDIT_PASTE, clipboard);
        assertEquals(SearchBarView.Action.REPLACEMENT_CHANGED, pasted.action());
        assertEquals("replace 👩‍💻", controls.replacement.text());

        controls.focus.clear(controls.placement.focusScope());
        clipboard.setText("must not enter Find");
        assertTrue(SearchInputRouter.routeCommand(controls.focus, controls.placement,
            TuinkCommand.EDIT_PASTE, clipboard).consumed());
        assertEquals("find", controls.query.text());
        assertEquals("replace 👩‍💻", controls.replacement.text());
        assertTrue(controls.placement.field(null).isEmpty());
        assertTrue(controls.placement.field(SearchFocus.CASE).isEmpty());
    }

    @Test
    void saveAsSemanticEditingUsesTheSameFieldAndItsButtonsRemainLocalRecipients() {
        SaveControls controls = save("old.txt");
        EditorClipboard clipboard = new EditorClipboard();
        assertTrue(SaveAsInputRouter.routeCommand(controls.focus, controls.placement,
            TuinkCommand.EDIT_SELECT_ALL, clipboard).consumed());
        clipboard.setText("new.md");
        assertEquals(SaveAsBarView.Action.VALUE_CHANGED, SaveAsInputRouter.routeCommand(controls.focus,
            controls.placement, TuinkCommand.EDIT_PASTE, clipboard).action());
        assertEquals("new.md", controls.field.text());
        assertEquals(SaveAsBarView.Action.SUBMITTED, SaveAsInputRouter.routeCommand(controls.focus,
            controls.placement, TuinkCommand.EDIT_INSERT_NEWLINE, clipboard).action());
        controls.focus.focus(controls.placement.focusScope(), SaveAsBarView.Target.CANCEL);
        assertEquals(SaveAsBarView.Action.CANCELLED, SaveAsInputRouter.routeCommand(controls.focus,
            controls.placement, TuinkCommand.EDIT_INSERT_NEWLINE, clipboard).action());
        clipboard.setText("button paste must stay local");
        SaveAsInputRouter.Result ignoredPaste = SaveAsInputRouter.routeCommand(controls.focus,
            controls.placement, TuinkCommand.EDIT_PASTE, clipboard);
        assertTrue(ignoredPaste.consumed());
        assertNull(ignoredPaste.action());
        assertEquals("new.md", controls.field.text());
        assertEquals(SaveAsBarView.Action.CANCELLED, SaveAsInputRouter.routeCommand(controls.focus,
            controls.placement, TuinkCommand.UI_ESCAPE, clipboard).action());
    }

    @Test
    void unsupportedLocalCommandsAreConsumedForFieldsAndButtonsWithoutTouchingTextOrClipboard() {
        List<TuinkCommand> commands = List.of(TuinkCommand.EDIT_UNDO, TuinkCommand.EDIT_REDO,
            TuinkCommand.EDIT_TOGGLE_LINE_COMMENT, TuinkCommand.CURSOR_SELECT_UP, TuinkCommand.CURSOR_SELECT_DOWN);
        SearchControls search = search("find", "replace");
        SaveControls save = save("name.txt");
        EditorClipboard clipboard = new EditorClipboard();
        clipboard.setText("clipboard");
        for (TuinkCommand command : commands) {
            for (SearchFocus target : List.of(SearchFocus.FIND, SearchFocus.REPLACE, SearchFocus.CASE)) {
                search.focus.focus(search.placement.focusScope(), target);
                SearchInputRouter.Result result = SearchInputRouter.routeCommand(search.focus, search.placement,
                    command, clipboard);
                assertTrue(result.consumed(), command.name());
                assertNull(result.action());
            }
            for (SaveAsBarView.Target target : SaveAsBarView.Target.values()) {
                save.focus.focus(save.placement.focusScope(), target);
                SaveAsInputRouter.Result result = SaveAsInputRouter.routeCommand(save.focus, save.placement,
                    command, clipboard);
                assertTrue(result.consumed(), command.name());
                assertNull(result.action());
            }
        }
        assertEquals("find", search.query.text());
        assertEquals("replace", search.replacement.text());
        assertEquals("name.txt", save.field.text());
        assertEquals("clipboard", clipboard.text());
    }

    @Test
    void semanticSearchNavigationAndActionsUseTheSameButtonRoute() {
        SearchControls controls = search("find", "replace");
        EditorClipboard clipboard = new EditorClipboard();
        controls.focus.focus(controls.placement.focusScope(), SearchFocus.CASE);
        SearchInputRouter.routeCommand(controls.focus, controls.placement, TuinkCommand.SEARCH_NAVIGATE_DOWN, clipboard);
        assertEquals(SearchFocus.NEXT_BUTTON, controls.focus.focused().orElseThrow());
        assertEquals(SearchBarView.Action.FIND_NEXT, SearchInputRouter.routeCommand(controls.focus,
            controls.placement, TuinkCommand.EDIT_INSERT_NEWLINE, clipboard).action());
        controls.focus.focus(controls.placement.focusScope(), SearchFocus.REPLACE_BUTTON);
        assertEquals(SearchBarView.Action.REPLACE, SearchInputRouter.routeCommand(controls.focus,
            controls.placement, TuinkCommand.EDIT_INSERT_NEWLINE, clipboard).action());
        assertEquals(SearchBarView.Action.FIND_PREVIOUS, SearchInputRouter.routeCommand(controls.focus,
            controls.placement, TuinkCommand.SEARCH_PREVIOUS, clipboard).action());
        assertEquals(SearchBarView.Action.CLOSE_REQUESTED, SearchInputRouter.routeCommand(controls.focus,
            controls.placement, TuinkCommand.SEARCH_CLOSE, clipboard).action());
    }

    @Test
    void pageKeysRemainDocumentViewportActionsInSearchAndCannotEscapeSaveAs() {
        SearchControls search = search("find", "replace");
        SaveControls save = save("file.txt");
        EditorClipboard clipboard = new EditorClipboard();
        for (TuinkCommand command : List.of(TuinkCommand.CURSOR_PAGE_UP, TuinkCommand.CURSOR_PAGE_DOWN)) {
            assertFalse(SearchInputRouter.routeCommand(search.focus, search.placement, command, clipboard).consumed());
            assertTrue(SaveAsInputRouter.routeCommand(save.focus, save.placement, command, clipboard).consumed());
        }
        assertEquals("find", search.query.text());
        assertEquals("file.txt", save.field.text());
        assertFalse(SearchInputRouter.routeCommand(search.focus, search.placement,
            TuinkCommand.DOCUMENT_SAVE, clipboard).consumed());
        assertFalse(SaveAsInputRouter.routeCommand(save.focus, save.placement,
            TuinkCommand.TAB_NEXT, clipboard).consumed());
    }

    @Test
    void clippedFieldsNeverReceiveSemanticClipboardCommands() {
        TextField query = field("query");
        TextField replacement = field("replacement");
        SearchBarView.Placement placement = SearchBarView.build(query, replacement, 80, false,
            SearchOptions.MatchMode.SUBSTRING, false).place("search", 20, new Rect(0, 0, 80, 20));
        FocusManager<SearchFocus> focus = new FocusManager<>();
        EditorClipboard clipboard = new EditorClipboard();
        clipboard.setText("must not enter clipped controls");
        assertTrue(placement.field(SearchFocus.FIND).isEmpty());
        assertTrue(SearchInputRouter.routeCommand(focus, placement, TuinkCommand.EDIT_PASTE, clipboard).consumed());
        assertEquals("query", query.text());
        assertEquals("replacement", replacement.text());
    }

    private static void assertFieldEquals(TextField expected, TextField actual) {
        assertEquals(expected.text(), actual.text());
        assertEquals(expected.cursor(), actual.cursor());
        assertEquals(expected.selectionStart(), actual.selectionStart());
        assertEquals(expected.selectionEnd(), actual.selectionEnd());
    }

    private static SearchControls search(String queryText, String replacementText) {
        TextField query = field(queryText);
        TextField replacement = field(replacementText);
        FocusManager<SearchFocus> focus = new FocusManager<>();
        SearchBarView.Placement placement = SearchBarView.build(query, replacement, 80, false,
            SearchOptions.MatchMode.SUBSTRING, false).place("search", 20, CLIP);
        placement.activate(focus);
        return new SearchControls(query, replacement, focus, placement);
    }

    private static SaveControls save(String text) {
        TextField field = field(text);
        FocusManager<SaveAsBarView.Target> focus = new FocusManager<>();
        SaveAsBarView.Placement placement = SaveAsBarView.build(field, 80).place("save", 22, CLIP);
        placement.activate(focus);
        return new SaveControls(field, focus, placement);
    }

    private static TextField field(String text) {
        TextField field = new TextField();
        field.set(text);
        return field;
    }

    private record Binding(TuinkCommand command, KeyStroke key) {
    }

    private record SearchControls(TextField query, TextField replacement, FocusManager<SearchFocus> focus,
        SearchBarView.Placement placement) {
    }

    private record SaveControls(TextField field, FocusManager<SaveAsBarView.Target> focus,
        SaveAsBarView.Placement placement) {
    }
}
