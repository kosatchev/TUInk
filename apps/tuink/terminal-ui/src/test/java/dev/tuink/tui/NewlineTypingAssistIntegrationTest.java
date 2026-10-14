package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.Position;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewlineTypingAssistIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void enterAfterAnAlignedPythonCloserKeepsTheNextStatementInsideTheFunction() throws Exception {
        List<String> original = List.of("def work():", "    result = call(", "        value,", "    )");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, original)) {
            var engine = window.active().tabSession().engine();
            engine.saveAs(directory.resolve("main.py"));
            engine.moveCursorTo(new Position(3, 5));
            window.frame();

            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(new Position(4, 4), window.active().state().getCursor());
            window.event(new TextInput("next()"));
            assertEquals("    next()", window.active().tabSession().model().getLine(4));

            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals("    ", window.active().tabSession().model().getLine(4));
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(original, window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test
    void successiveEntersContinueFinishAndLeaveAMarkdownListWithIndependentUndo() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("- item"))) {
            window.active().tabSession().engine().saveAs(directory.resolve("notes.md"));
            window.event(KeyStroke.of(KeyStroke.Key.END));

            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(List.of("- item", "- "), window.active().tabSession().model().snapshotLines());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(List.of("- item", ""), window.active().tabSession().model().snapshotLines());
            assertEquals(new Position(1, 0), window.active().state().getCursor());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals(List.of("- item", "", ""), window.active().tabSession().model().snapshotLines());

            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("- item", ""), window.active().tabSession().model().snapshotLines());
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("- item", "- "), window.active().tabSession().model().snapshotLines());
            assertEquals(new Position(1, 2), window.active().state().getCursor());
            window.event(ctrl(KeyStroke.Key.Z));
            assertEquals(List.of("- item"), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }

    private static KeyStroke ctrl(KeyStroke.Key key) {
        return KeyStroke.of(key, KeyStroke.Modifier.CTRL);
    }
}
