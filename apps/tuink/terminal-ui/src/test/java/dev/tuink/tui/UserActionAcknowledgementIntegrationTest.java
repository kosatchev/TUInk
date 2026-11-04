package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.AppSettings;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class UserActionAcknowledgementIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(ActiveSurface.Kind.class)
    void keyboardAndPointerNavigationAcknowledgeThePreviousError(ActiveSurface.Kind kind) throws Exception {
        try (var window = window(kind)) {
            window.notification.error("Previous action failed");
            window.frame();
            assertTrue(window.notification.isError());
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            assertNull(window.notification.message, kind + " keyboard navigation must acknowledge the error");

            window.notification.error("Previous action failed again");
            window.frame();
            int row = kind == ActiveSurface.Kind.SOURCE ? 1 : window.preview().contentBounds().y();
            window.pointer(PointerEvent.Kind.PRESS, row, 8);
            window.pointer(PointerEvent.Kind.RELEASE, row, 8);
            assertNull(window.notification.message, kind + " pointer navigation must acknowledge the error");
        }
    }

    @ParameterizedTest
    @EnumSource(ActiveSurface.Kind.class)
    void saveAsKeepsItsFailureUntilTheFilenameActuallyChanges(ActiveSurface.Kind kind) throws Exception {
        try (var window = window(kind)) {
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            assertTrue(window.selected().uiState().modalState.isSaveAs());
            window.command(TuinkCommand.CURSOR_END);
            String filename = window.filename.text();
            window.notification.error("Could not save the selected filename");
            window.frame();

            window.event(KeyStroke.of(KeyStroke.Key.ARROW_LEFT));
            assertTrue(window.notification.isError());
            assertEquals("Could not save the selected filename", window.notification.message);
            assertEquals(filename, window.filename.text());
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            assertTrue(window.notification.isError());
            window.event(new TextInput("renamed.md"));
            assertEquals("renamed.md", window.filename.text());
            assertNull(window.notification.message, "Editing the filename acknowledges the save failure");
            window.command(TuinkCommand.UI_ESCAPE);
        }
    }

    private EditorWindowFixture window(ActiveSurface.Kind kind) throws Exception {
        var window = new EditorWindowFixture(directory.resolve(kind.name()), "document.md",
            List.of("# Acknowledgement", "", "alpha beta gamma"),
            new AppSettingsSession(AppSettings.defaults(), TuiCliOptions.parse(new String[0])));
        if (kind == ActiveSurface.Kind.SOURCE) return window;
        window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
        window.await(() -> window.preview().ready());
        if (kind == ActiveSurface.Kind.FROZEN_PREVIEW) {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.command(TuinkCommand.TAB_CLOSE);
            window.await(() -> window.selected() instanceof PreviewTab preview
                && preview.source() instanceof PreviewTab.ClosedSnapshot && window.preview().ready());
        }
        return window;
    }
}
