package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.TextField;
import dev.tuink.core.EditorClipboard;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuink.tui.ui.SearchBarView;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class FieldEditAdmissionIntegrationTest {
    private static final Rect CLIP = new Rect(0, 0, 80, 24);

    @Test
    void findAndSaveAsKeepTypingAfterTheGraphemeJoinedByDeletion() {
        for (boolean saveAs : new boolean[] {false, true}) {
            TextField field = new TextField();
            field.set("👩‍x💻TAIL");
            var searchFocus = new FocusManager<SearchFocus>();
            var search = SearchBarView.build(field, new TextField(), 80, false,
                SearchOptions.MatchMode.SUBSTRING, false).place("search", 20, CLIP);
            var saveFocus = new FocusManager<SaveAsBarView.Target>();
            var save = SaveAsBarView.build(field, 80).place("save", 22, CLIP);
            for (var key : new KeyStroke.Key[] {KeyStroke.Key.HOME, KeyStroke.Key.ARROW_RIGHT, KeyStroke.Key.DELETE}) {
                if (saveAs) SaveAsInputRouter.route(saveFocus, save, KeyStroke.of(key));
                else SearchInputRouter.route(searchFocus, search, KeyStroke.of(key));
            }
            if (saveAs) SaveAsInputRouter.route(saveFocus, save, new TextInput("B"));
            else SearchInputRouter.route(searchFocus, search, new TextInput("B"));
            assertEquals("👩‍💻BTAIL", field.text());
            assertEquals("👩‍💻B".length(), field.cursor());
        }
    }

    @Test
    void searchAndSaveAsExposeMemoryRejectionFromRawInputAndTheInternalClipboard() throws Exception {
        AtomicLong budget = new AtomicLong(Long.MAX_VALUE);
        TextField field = boundedField(budget::get);
        assertTrue(field.set("keep 👩‍💻"));
        field.selectAll();
        var searchFocus = new FocusManager<SearchFocus>();
        var search = SearchBarView.build(field, new TextField(), 80, false,
            SearchOptions.MatchMode.SUBSTRING, false).place("search", 20, CLIP);
        var saveFocus = new FocusManager<SaveAsBarView.Target>();
        var save = SaveAsBarView.build(field, 80).place("save", 22, CLIP);
        EditorClipboard clipboard = new EditorClipboard();
        clipboard.setText("replacement");
        budget.set(0);

        assertEquals(SearchBarView.Action.INPUT_REJECTED,
            SearchInputRouter.route(searchFocus, search, new TextInput("界")).action());
        assertEquals(SearchBarView.Action.INPUT_REJECTED,
            SearchInputRouter.route(searchFocus, search, new PasteEvent("new\nvalue")).action());
        assertEquals(SearchBarView.Action.INPUT_REJECTED,
            SearchInputRouter.routeCommand(searchFocus, search, TuinkCommand.EDIT_PASTE, clipboard).action());
        assertEquals(SaveAsBarView.Action.INPUT_REJECTED,
            SaveAsInputRouter.route(saveFocus, save, new TextInput("界")).action());
        assertEquals(SaveAsBarView.Action.INPUT_REJECTED,
            SaveAsInputRouter.route(saveFocus, save, new PasteEvent("new\nvalue")).action());
        assertEquals(SaveAsBarView.Action.INPUT_REJECTED,
            SaveAsInputRouter.routeCommand(saveFocus, save, TuinkCommand.EDIT_PASTE, clipboard).action());
        assertEquals("keep 👩‍💻", field.text());
        assertEquals("keep 👩‍💻", field.selectedText());
        assertEquals("replacement", clipboard.text());

        budget.set(Long.MAX_VALUE);
        assertEquals(SaveAsBarView.Action.VALUE_CHANGED,
            SaveAsInputRouter.routeCommand(saveFocus, save, TuinkCommand.EDIT_PASTE, clipboard).action());
        assertEquals("replacement", field.text());
        assertFalse(field.hasSelection());
    }

    @Test
    void aRefusedPartialCutKeepsBothTheFieldAndClipboardWhileWholeFieldCutCanReleaseMemory() throws Exception {
        AtomicLong budget = new AtomicLong(Long.MAX_VALUE);
        TextField field = boundedField(budget::get);
        field.set("cut 界界界");
        field.moveHome();
        field.navigate(KeyStroke.Key.ARROW_RIGHT, true, true);
        int cursor = field.cursor();
        String selected = field.selectedText();
        var searchFocus = new FocusManager<SearchFocus>();
        var search = SearchBarView.build(field, new TextField(), 80, false,
            SearchOptions.MatchMode.SUBSTRING, false).place("search", 20, CLIP);
        var saveFocus = new FocusManager<SaveAsBarView.Target>();
        var save = SaveAsBarView.build(field, 80).place("save", 22, CLIP);
        EditorClipboard clipboard = new EditorClipboard();
        clipboard.setText("old clipboard");
        budget.set(0);

        assertEquals(FieldClipboard.Result.INPUT_REJECTED,
            FieldClipboard.handle(TuinkCommand.EDIT_CUT, field, clipboard));
        assertEquals(SearchBarView.Action.INPUT_REJECTED,
            SearchInputRouter.routeCommand(searchFocus, search, TuinkCommand.EDIT_CUT, clipboard).action());
        assertEquals(SaveAsBarView.Action.INPUT_REJECTED,
            SaveAsInputRouter.routeCommand(saveFocus, save, TuinkCommand.EDIT_CUT, clipboard).action());
        assertEquals("old clipboard", clipboard.text());
        assertEquals("cut 界界界", field.text());
        assertEquals(selected, field.selectedText());
        assertEquals(cursor, field.cursor());

        field.selectAll();
        assertEquals(SaveAsBarView.Action.VALUE_CHANGED,
            SaveAsInputRouter.routeCommand(saveFocus, save, TuinkCommand.EDIT_CUT, clipboard).action());
        assertEquals("cut 界界界", clipboard.text());
        assertTrue(field.isEmpty());
        assertFalse(field.hasSelection());
    }

    private static TextField boundedField(LongSupplier budget) throws Exception {
        // Keep the allocator injection internal to the widget; test the real application
        // adapters without depending on machine-wide free heap or allocating huge input.
        var constructor = TextField.class.getDeclaredConstructor(TextField.PastePolicy.class, LongSupplier.class);
        constructor.setAccessible(true);
        return constructor.newInstance(TextField.PastePolicy.NORMALIZE_TABS, budget);
    }
}
