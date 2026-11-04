package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.widget.TextField;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MalformedBindingIntegrationTest {
    @TempDir Path directory;

    @Test void malformedBindingsCannotEditFindSaveAsOrOpenLocationForAnyPresentation() throws Exception {
        for (var kind : ActiveSurface.Kind.values()) {
            try (var window = window("fields-" + kind, kind)) {
                window.command(TuinkCommand.SEARCH_OPEN);
                window.event(new TextInput("needle"));
                TextField query = kind == ActiveSurface.Kind.SOURCE ? window.query : window.preview().searchField;
                window.command(TuinkCommand.EDIT_SELECT_ALL);
                assertRejectedEdit(window, query, TuinkCommand.EDIT_PASTE);
                var left = KeyStroke.of(KeyStroke.Key.ARROW_LEFT);
                int cursor = query.cursor();
                window.keymap.rebind(left, malformed(TuinkCommand.CURSOR_LEFT));
                window.event(left);
                assertEquals(cursor, query.cursor());
                assertEquals("needle", query.selectedText());
                window.command(TuinkCommand.UI_ESCAPE);

                window.command(TuinkCommand.DOCUMENT_SAVE_AS);
                window.command(TuinkCommand.EDIT_SELECT_ALL);
                assertRejectedEdit(window, window.filename, TuinkCommand.EDIT_CUT);
                window.command(TuinkCommand.UI_ESCAPE);

                window.command(TuinkCommand.DOCUMENT_OPEN);
                window.selected().uiState().openFileFocusState.focus(OpenFileInputRouter.Focus.LOCATION);
                window.frame();
                var location = window.selected().uiState().openFileFocusState.location();
                location.set("unchanged"); location.selectAll(); window.frame();
                assertRejectedEdit(window, location, TuinkCommand.EDIT_PASTE);
                assertEquals(List.of("# Header", "needle content"), java.nio.file.Files.readAllLines(window.document));
            }
        }
    }

    @Test void malformedMenuAndFormatCommandsCannotPerformTheirRawOwnerEffects() throws Exception {
        try (var window = window("format", ActiveSurface.Kind.SOURCE)) {
            var menu = KeyStroke.of(KeyStroke.Key.F1);
            window.keymap.rebind(menu, malformed(TuinkCommand.MENU_FILE));
            window.event(menu);
            assertFalse(window.menu.isOpen());
            window.command(TuinkCommand.DOCUMENT_FORMAT);
            var format = window.selected().uiState().formatDialog;
            long revision = window.active().tabSession().model().revision();
            for (var command : List.of(TuinkCommand.DOCUMENT_FORMAT_APPLY, TuinkCommand.APP_QUIT)) {
                bindAndSend(window, malformed(command));
                assertSame(format, window.selected().uiState().formatDialog);
                assertTrue(window.selected().uiState().modalState.isDocumentFormat());
                assertEquals(revision, window.active().tabSession().model().revision());
                assertTrue(window.controller.running());
            }
        }
    }

    @Test void programmaticTabActivationAndCloseUseTheSameWindowModalCapabilityGate() throws Exception {
        try (var window = window("window-modal", ActiveSurface.Kind.LIVE_PREVIEW)) {
            window.command(TuinkCommand.TAB_PREVIOUS);
            window.event(new TextInput("modified "));
            window.command(TuinkCommand.APP_QUIT);
            var selected = window.selected().presentationId();
            int count = window.context.presentations().size();
            var recipient = UiRecipient.presentation(window.selected(), UiRecipient.Flow.LOCAL);
            window.controller.invokeCommand(new CommandInvocation(TuinkCommand.TAB_ACTIVATE.id(), Map.of("index", "1")), recipient);
            window.controller.invokeCommand(new CommandInvocation(TuinkCommand.TAB_CLOSE.id(), Map.of("index", "1")), recipient);
            window.frame();
            assertEquals(selected, window.selected().presentationId());
            assertEquals(count, window.context.presentations().size());
            assertEquals(ActiveSurface.ModalScope.WINDOW, window.controller.activeSurface().modalScope());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.controller.running());
        }
    }

    private static void assertRejectedEdit(EditorWindowFixture window, TextField field, TuinkCommand command) {
        String before = field.text();
        String selected = field.selectedText();
        window.host.clipboard.setText("must not be pasted or replaced");
        bindAndSend(window, malformed(command));
        assertEquals(before, field.text());
        assertEquals(selected, field.selectedText());
        assertEquals("must not be pasted or replaced", window.host.clipboard.text());
    }

    private static CommandInvocation malformed(TuinkCommand command) {
        return new CommandInvocation(command.id(), Map.of("unexpected", "value"));
    }
    private static void bindAndSend(EditorWindowFixture window, CommandInvocation invocation) {
        var alias = KeyStroke.of(KeyStroke.Key.F12);
        window.keymap.rebind(alias, invocation); window.event(alias);
    }
    private EditorWindowFixture window(String name, ActiveSurface.Kind kind) throws Exception {
        var window = new EditorWindowFixture(directory.resolve(name), "document.md", List.of("# Header", "needle content"),
            new AppSettingsSession(dev.tuink.tui.flow.AppSettings.defaults(), TuiCliOptions.parse(new String[0])));
        if (kind == ActiveSurface.Kind.SOURCE) return window;
        window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW); window.await(() -> window.preview().ready());
        if (kind == ActiveSurface.Kind.FROZEN_PREVIEW) {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW); window.command(TuinkCommand.TAB_CLOSE);
            window.await(() -> window.selected() instanceof PreviewTab preview && preview.source() instanceof PreviewTab.ClosedSnapshot);
        }
        return window;
    }
}
