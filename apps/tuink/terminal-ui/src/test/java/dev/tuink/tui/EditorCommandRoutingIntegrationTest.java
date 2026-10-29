package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuink.core.Position;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.ui.SaveAsBarView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Semantic aliases travel through the same controller entry point as ordinary keys. */
class EditorCommandRoutingIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void saveAsAliasesEditTheFieldWithUnicodeSelectionAndClipboardWithoutChangingTheDocument() throws Exception {
        try (EditorWindowFixture fixture = new EditorWindowFixture(directory, false, List.of("document"))) {
            long revision = fixture.active().tabSession().model().revision();
            fixture.command(TuinkCommand.DOCUMENT_SAVE_AS);
            fixture.command(TuinkCommand.EDIT_SELECT_ALL);
            fixture.event(new TextInput("a😀bc"));
            fixture.command(TuinkCommand.CURSOR_HOME);
            fixture.command(TuinkCommand.CURSOR_RIGHT);
            fixture.command(TuinkCommand.CURSOR_RIGHT);
            assertEquals(3, fixture.filename.cursor());
            fixture.command(TuinkCommand.EDIT_BACKSPACE);
            assertEquals("abc", fixture.filename.text());
            fixture.command(TuinkCommand.CURSOR_SELECT_RIGHT);
            assertEquals("b", fixture.filename.selectedText());
            fixture.command(TuinkCommand.EDIT_COPY);
            assertEquals("b", fixture.host.clipboard.text());
            fixture.command(TuinkCommand.EDIT_CUT);
            assertEquals("ac", fixture.filename.text());
            fixture.host.clipboard.setText("x\r\n\ty\u0001\nz");
            fixture.command(TuinkCommand.EDIT_PASTE);
            assertEquals("ax \ty zc", fixture.filename.text());
            fixture.command(TuinkCommand.EDIT_SELECT_ALL);
            fixture.command(TuinkCommand.EDIT_COPY);
            assertEquals("ax \ty zc", fixture.host.clipboard.text());
            fixture.command(TuinkCommand.CURSOR_END);
            fixture.command(TuinkCommand.EDIT_DELETE_FORWARD);
            fixture.command(TuinkCommand.EDIT_UNDO);
            fixture.command(TuinkCommand.EDIT_REDO);
            assertEquals("ax \ty zc", fixture.filename.text());
            assertEquals(SaveAsBarView.Target.FIELD, fixture.host.saveAsFocusManager().focused().orElseThrow());

            fixture.command(TuinkCommand.UI_ESCAPE);
            assertTrue(fixture.host.saveAsFocusManager().activeScopeId().isEmpty());
            assertEquals(List.of("document"), fixture.active().tabSession().model().snapshotLines());
            assertEquals(revision, fixture.active().tabSession().model().revision());
            assertEquals(new Position(0, 0), fixture.active().state().getCursor());
            assertFalse(fixture.active().state().isDirty());
        }
    }

    @Test
    void saveAsAliasesOnAButtonOrExplicitlyClearedFocusCannotReachEitherTextModel() throws Exception {
        Path target = directory.resolve("must-not-be-created.txt");
        try (EditorWindowFixture fixture = new EditorWindowFixture(directory.resolve("window"), false, List.of("document"))) {
            fixture.command(TuinkCommand.DOCUMENT_SAVE_AS);
            fixture.command(TuinkCommand.EDIT_SELECT_ALL);
            fixture.event(new TextInput(target.toString()));
            fixture.command(TuinkCommand.EDIT_INSERT_TAB);
            assertEquals(SaveAsBarView.Target.SAVE, fixture.host.saveAsFocusManager().focused().orElseThrow());
            fixture.host.clipboard.setText("unchanged clipboard");
            for (boolean noFocus : List.of(false, true)) {
                if (noFocus) {
                    fixture.host.saveAsFocusManager().clear(new FocusScope<SaveAsBarView.Target, Object>(
                        TerminalEditorApp.SAVE_AS_SCOPE_ID, FocusResetPolicy.CLEARED, List.of(), List.of(), List.of()));
                    fixture.frame();
                    assertTrue(fixture.host.saveAsFocusManager().focused().isEmpty());
                }
                for (TuinkCommand command : List.of(TuinkCommand.EDIT_SELECT_ALL, TuinkCommand.EDIT_COPY,
                    TuinkCommand.EDIT_CUT, TuinkCommand.EDIT_PASTE, TuinkCommand.EDIT_BACKSPACE,
                    TuinkCommand.EDIT_DELETE_FORWARD, TuinkCommand.EDIT_UNDO, TuinkCommand.EDIT_REDO)) {
                    fixture.command(command);
                    assertEquals(target.toString(), fixture.filename.text());
                    assertEquals("unchanged clipboard", fixture.host.clipboard.text());
                    assertEquals(List.of("document"), fixture.active().tabSession().model().snapshotLines());
                    assertFalse(fixture.active().state().isDirty());
                }
            }
            fixture.command(TuinkCommand.EDIT_INSERT_NEWLINE);
            assertFalse(Files.exists(target));
            assertTrue(fixture.host.saveAsFocusManager().focused().isEmpty());
            fixture.command(TuinkCommand.UI_ESCAPE);
            assertTrue(fixture.host.saveAsFocusManager().activeScopeId().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void saveAsEnterAliasSubmitsTheFieldOrFocusedSaveButton(boolean buttonFocused) throws Exception {
        Path target = directory.resolve("saved.txt");
        try (EditorWindowFixture fixture = new EditorWindowFixture(directory.resolve("window"), false, List.of("document"))) {
            fixture.event(new TextInput("modified "));
            fixture.command(TuinkCommand.DOCUMENT_SAVE_AS);
            fixture.command(TuinkCommand.EDIT_SELECT_ALL);
            fixture.event(new TextInput(target.toString()));
            if (buttonFocused) {
                fixture.command(TuinkCommand.EDIT_INSERT_TAB);
                assertEquals(SaveAsBarView.Target.SAVE, fixture.host.saveAsFocusManager().focused().orElseThrow());
            }

            fixture.command(TuinkCommand.EDIT_INSERT_NEWLINE);

            assertEquals("modified document", Files.readString(target));
            assertEquals("document", Files.readString(fixture.document));
            assertEquals(target.toAbsolutePath().normalize(), JvmDocumentStorage.pathOf(fixture.active().state().getCurrentResource()));
            assertFalse(fixture.active().state().isDirty());
            assertTrue(fixture.host.saveAsFocusManager().activeScopeId().isEmpty());
        }
    }

    @Test
    void globalSaveAndTabAliasesRemainWindowCommandsWhileFindOwnsFocus() throws Exception {
        try (EditorWindowFixture fixture = new EditorWindowFixture(directory, false, List.of("needle document"))) {
            fixture.event(new TextInput("modified "));
            fixture.event(KeyStroke.of(KeyStroke.Key.F, KeyStroke.Modifier.CTRL));
            fixture.command(TuinkCommand.EDIT_SELECT_ALL);
            fixture.event(new TextInput("needle"));
            assertEquals(SearchFocus.FIND, fixture.host.searchFocusManager().focused().orElseThrow());

            fixture.command(TuinkCommand.DOCUMENT_SAVE);
            assertEquals("modified needle document", Files.readString(fixture.document));
            assertEquals("needle", fixture.query.text());
            assertEquals(SearchFocus.FIND, fixture.host.searchFocusManager().focused().orElseThrow());
            assertFalse(fixture.active().state().isDirty());

            fixture.command(TuinkCommand.TAB_NEW);
            assertEquals(2, fixture.tabs.size());
            assertEquals(List.of(""), fixture.active().tabSession().model().snapshotLines());
            fixture.command(TuinkCommand.TAB_PREVIOUS);
            assertEquals(List.of("modified needle document"), fixture.active().tabSession().model().snapshotLines());
            assertEquals("needle", fixture.query.text());
            assertFalse(fixture.active().state().isDirty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tabAliasesFollowTheSameDialogAndDocumentStopsAsPhysicalTab(boolean search) throws Exception {
        try (EditorWindowFixture physical = new EditorWindowFixture(directory.resolve("physical"), false, List.of("document"));
             EditorWindowFixture aliased = new EditorWindowFixture(directory.resolve("aliased"), false, List.of("document"))) {
            KeyStroke open = KeyStroke.of(search ? KeyStroke.Key.F : KeyStroke.Key.K, KeyStroke.Modifier.CTRL);
            physical.event(open);
            aliased.event(open);
            boolean visitedDocument = false;
            int stops = search ? SearchFocus.values().length + 1 : SaveAsBarView.Target.values().length + 1;
            for (int step = 0; step < stops; step++) {
                physical.event(KeyStroke.of(KeyStroke.Key.TAB));
                aliased.command(step % 2 == 0 ? TuinkCommand.EDIT_INSERT_TAB : TuinkCommand.SEARCH_FOCUS_NEXT);
                if (search) {
                    assertEquals(physical.ui.searchEditorFocused, aliased.ui.searchEditorFocused);
                    assertEquals(physical.host.searchFocusManager().focused(), aliased.host.searchFocusManager().focused());
                    visitedDocument |= physical.ui.searchEditorFocused;
                } else {
                    assertEquals(physical.host.saveAsFocusManager().focused(), aliased.host.saveAsFocusManager().focused());
                    visitedDocument |= physical.host.saveAsFocusManager().focused().isEmpty();
                }
            }
            assertTrue(visitedDocument, "the dialog mode includes the document in its focus cycle");
            if (search) {
                assertEquals(SearchFocus.FIND, aliased.host.searchFocusManager().focused().orElseThrow());
            } else {
                assertEquals(SaveAsBarView.Target.FIELD, aliased.host.saveAsFocusManager().focused().orElseThrow());
            }
            assertEquals(List.of("document"), aliased.active().tabSession().model().snapshotLines());
            assertFalse(aliased.active().state().isDirty());
        }
    }
}
