package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.file.FileTreeModel;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedApplicationCommandIntegrationTest {
    @TempDir Path directory;

    @Test void keyboardAndMenuOpenResetPickerForSourceLiveAndFrozenPreview() throws Exception {
        for (var kind : ActiveSurface.Kind.values()) {
            for (boolean menu : new boolean[] {false, true}) {
                try (var window = new EditorWindowFixture(directory.resolve(kind + "-" + menu), false, List.of("# Original"))) {
                    select(window, kind);
                    var owner = window.selected();
                    owner.uiState().lastPickerDirectoryFailure = new FileTreeModel.DirectoryFailure(directory, "old failure");
                    if (menu) {
                        window.event(KeyStroke.of(KeyStroke.Key.F1));
                        window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
                        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                    } else window.event(KeyStroke.of(KeyStroke.Key.O, KeyStroke.Modifier.CTRL));
                    assertSame(owner, window.selected());
                    assertTrue(owner.uiState().openFileMode, kind + " menu=" + menu);
                    assertNull(owner.uiState().lastPickerDirectoryFailure);
                    assertEquals(OpenFileInputRouter.Focus.LIST, owner.uiState().openFileFocusState.focus());
                }
            }
        }
    }

    @Test void previewFindAndSaveAsRemainEditableWhileDocumentEditsAreUnavailable() throws Exception {
        for (var kind : new ActiveSurface.Kind[] {ActiveSurface.Kind.LIVE_PREVIEW, ActiveSurface.Kind.FROZEN_PREVIEW}) {
            try (var window = new EditorWindowFixture(directory.resolve(kind.name()), false, List.of("# Original"))) {
                select(window, kind);
                assertFalse(window.controller.activeSurface().allows(TuinkCommand.EDIT_PASTE));
                window.command(TuinkCommand.SEARCH_OPEN);
                assertTrue(window.controller.activeSurface().allows(TuinkCommand.EDIT_PASTE));
                window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
                window.command(TuinkCommand.DOCUMENT_SAVE_AS);
                assertTrue(window.selected().uiState().modalState.isSaveAs());
                assertTrue(window.controller.activeSurface().allows(TuinkCommand.EDIT_PASTE));
            }
        }
    }

    private static void select(EditorWindowFixture window, ActiveSurface.Kind kind) throws Exception {
        if (kind == ActiveSurface.Kind.SOURCE) return;
        window.event(KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL));
        window.await(() -> window.preview().ready());
        if (kind == ActiveSurface.Kind.FROZEN_PREVIEW) {
            window.event(KeyStroke.of(KeyStroke.Key.P, KeyStroke.Modifier.CTRL));
            window.command(TuinkCommand.TAB_CLOSE);
            window.await(() -> window.selected() instanceof PreviewTab preview && preview.source() instanceof PreviewTab.ClosedSnapshot);
        }
        assertEquals(kind, window.controller.activeSurface().kind());
    }
}
