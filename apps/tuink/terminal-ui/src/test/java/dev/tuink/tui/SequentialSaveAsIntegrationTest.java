package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.ui.SaveAsBarView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SequentialSaveAsIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savingAllDraftsFocusesEachNewFilenameAfterButtonSubmissionAndOverwrite(boolean mouse) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("named "));
            var drafts = new ArrayList<TerminalEditorApp.EditorTab>();
            for (String text : List.of("first", "second", "third")) {
                window.command(TuinkCommand.TAB_NEW);
                window.event(new TextInput(text));
                drafts.add(window.active());
            }
            Path first = directory.resolve("first.txt");
            Path second = Files.writeString(directory.resolve("second.txt"), "existing");
            Path third = directory.resolve("third.txt");

            window.command(TuinkCommand.APP_QUIT);
            window.event(new TextInput("y"));
            assertSame(drafts.get(0), window.active());
            setFilename(window, first);
            submitButton(window, mouse);

            assertEquals("first", Files.readString(first));
            assertSame(drafts.get(1), window.active());
            setFilename(window, second);
            submitButton(window, mouse);
            assertEquals("existing", Files.readString(second));
            window.event(new TextInput("y"));

            assertEquals("second", Files.readString(second));
            assertSame(drafts.get(2), window.active());
            assertTrue(window.controller.running());
            assertEquals("original", Files.readString(window.document));
            setFilename(window, third);
            submitButton(window, mouse);

            assertFalse(window.controller.running());
            assertEquals("third", Files.readString(third));
            assertEquals("named original", Files.readString(window.document));
            for (var draft : drafts) {
                assertFalse(draft.state().isDirty());
            }
        }
    }

    @Test
    void cancellingTheNextFilenameKeepsItsDraftAndCanResumeAfterResize() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("first"));
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("second"));
            var secondDraft = window.active();
            Path first = directory.resolve("first.txt");
            Path second = directory.resolve("second.txt");
            window.command(TuinkCommand.APP_QUIT);
            window.event(new TextInput("y"));
            setFilename(window, first);
            submitButton(window, false);

            setFilename(window, second);
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.controller.running());
            assertEquals("first", Files.readString(first));
            assertFalse(Files.exists(second));
            assertSame(secondDraft, window.active());
            assertTrue(secondDraft.state().isDirty());
            assertEquals(List.of("second"), secondDraft.tabSession().model().snapshotLines());

            window.command(TuinkCommand.APP_QUIT);
            window.event(new TextInput("y"));
            assertEquals(second.toString(), window.filename.text());
            window.resize(24, 10);
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertFalse(Files.exists(second));
            window.resize(24, 80);
            assertEquals(SaveAsBarView.Target.FIELD, window.host.saveAsFocusManager().focused().orElseThrow());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertFalse(window.controller.running());
            assertEquals("second", Files.readString(second));
        }
    }

    private static void setFilename(EditorWindowFixture window, Path target) {
        assertEquals(SaveAsBarView.Target.FIELD, window.host.saveAsFocusManager().focused().orElseThrow());
        window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
        window.event(new TextInput(target.toString()));
        assertEquals(target.toString(), window.filename.text());
    }

    private static void submitButton(EditorWindowFixture window, boolean mouse) {
        if (mouse) {
            int row = window.rowStartingWith("Save As: ");
            int column = window.screenRow(row).indexOf("[Save]") + 1;
            assertTrue(column > 0);
            window.pointer(PointerEvent.Kind.PRESS, row, column);
            window.pointer(PointerEvent.Kind.RELEASE, row, column);
        } else {
            window.event(KeyStroke.of(KeyStroke.Key.TAB));
            assertEquals(SaveAsBarView.Target.SAVE, window.host.saveAsFocusManager().focused().orElseThrow());
            window.frame();
            assertEquals(SaveAsBarView.Target.SAVE, window.host.saveAsFocusManager().focused().orElseThrow());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
        }
    }
}
