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
        for (var kind : List.of(ActiveSurface.Kind.SOURCE)) {
            for (boolean menu : new boolean[] {false, true}) {
                try (var window = new EditorWindowFixture(directory.resolve(kind + "-" + menu), false, List.of("# Original"))) {
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


}
