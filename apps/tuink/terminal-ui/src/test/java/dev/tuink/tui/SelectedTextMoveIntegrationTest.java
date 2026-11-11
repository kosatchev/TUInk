package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuink.core.Position;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelectedTextMoveIntegrationTest {
    @TempDir Path directory;

    @Test void settingsEnableCapturedMultilineMoveThroughTheRealWindowAndUndoRestoresIt() throws Exception {
        var original = List.of("before ONE", "TWO after", "tail");
        try (var window = new EditorWindowFixture(directory, false, original)) {
            assertFalse(window.ui.dragSelectedTextSetting); window.command(TuinkCommand.SETTINGS_TOGGLE_DRAG_SELECTED_TEXT);
            var source = window.active(); var engine = source.tabSession().engine();
            engine.restoreSelection(new Position(1, 3), new Position(0, 7)); window.frame();
            int first = row(window, "before ONE"), last = row(window, "tail");
            int press = window.screenRow(first).indexOf("before ONE") + 8;
            int destination = window.screenRow(last).indexOf("tail") + 4;
            window.pointer(PointerEvent.Kind.PRESS, first, press); Thread.sleep(520); window.frame();
            window.pointer(PointerEvent.Kind.DRAG, last, destination);
            assertEquals(original, source.tabSession().model().snapshotLines());
            assertTrue(window.editorView.movingSelectedText());
            window.pointer(PointerEvent.Kind.RELEASE, last, destination);
            assertEquals(List.of("before  after", "tailONE", "TWO"), source.tabSession().model().snapshotLines());
            assertTrue(source.state().isDirty()); assertFalse(window.editorView.movingSelectedText());
            window.event(KeyStroke.of(KeyStroke.Key.Z, KeyStroke.Modifier.CTRL));
            assertEquals(original, source.tabSession().model().snapshotLines()); assertFalse(source.state().isDirty());
        }
    }
    private static int row(EditorWindowFixture window, String needle) {
        return java.util.stream.IntStream.range(0, window.height).filter(row -> window.screenRow(row).contains(needle)).findFirst().orElseThrow();
    }
}
