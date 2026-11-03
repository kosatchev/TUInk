package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.storage.BomKind;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AmbiguousOpenIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"enter", "escape", "ctrl-q", "n"})
    void ambiguousBytesRequireAnExplicitChoiceAndCancellationReturnsToTheSameSelection(String cancel) throws Exception {
        Path file = ambiguousFile();
        byte[] original = Files.readAllBytes(file);
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var source = window.active();
            var cursor = source.state().getCursor();
            selectAndOpen(window, file);
            assertEquals(1, window.tabs.size());
            assertTrue(window.promptText().contains("Ambiguous:"));
            assertTrue(window.promptText().contains(file.getFileName().toString()));
            assertTrue(window.promptText().contains("UTF-16LE"));

            switch (cancel) {
                case "enter" -> window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                case "escape" -> window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
                case "ctrl-q" -> window.event(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL));
                case "n" -> window.event(new TextInput("n"));
                default -> throw new AssertionError(cancel);
            }
            assertEquals(file, window.tree.state().selectedId().path());
            assertEquals(1, window.tabs.size());
            assertSame(source, window.active());
            assertEquals(cursor, source.state().getCursor());
            assertEquals(List.of("draft original"), source.tabSession().model().snapshotLines());
            assertTrue(source.state().isDirty());
            assertArrayEquals(original, Files.readAllBytes(file));
            assertTrue(java.util.stream.IntStream.range(0, window.height)
                .anyMatch(row -> window.screenRow(row).contains("Open File")));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.command(TuinkCommand.EDIT_UNDO);
            assertEquals(List.of("original"), source.tabSession().model().snapshotLines());
        }
    }

    @Test
    void explicitUtf16ChoiceOpensTheExactBytesWithoutChangingAnotherDraftOrMakingTheChoiceGlobal() throws Exception {
        Path file = ambiguousFile();
        byte[] bytes = Files.readAllBytes(file);
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            var source = window.active();
            selectAndOpen(window, file);
            window.resize(3, 20);
            window.event(new TextInput("y"));
            assertEquals(1, window.tabs.size(), "A hidden question cannot authorize explicit decoding");
            window.resize(24, 80);
            window.event(new TextInput("y"));

            assertEquals(2, window.tabs.size());
            assertEquals(file, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertEquals(List.of("\0A\0"), window.active().tabSession().model().snapshotLines());
            assertEquals(StandardCharsets.UTF_16LE, window.active().state().getFileEncoding());
            assertEquals(BomKind.UTF_16_LE, window.active().tabSession().model().fileMetadata().bomKind());
            assertTrue(source.state().isDirty());
            window.command(TuinkCommand.DOCUMENT_SAVE);
            assertArrayEquals(bytes, Files.readAllBytes(file));
            window.command(TuinkCommand.TAB_CLOSE);
            assertSame(source, window.active());
            selectAndOpen(window, file);
            assertEquals(1, window.tabs.size());
            assertTrue(window.promptText().contains("Ambiguous:"),
                "An explicit decoding choice must be requested again on a later open");
        }
    }

    @Test
    void aFileThatDisappearsBeforeExplicitOpenReturnsToThePickerWithItsLocalError() throws Exception {
        Path file = ambiguousFile();
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            selectAndOpen(window, file);
            Files.delete(file);
            window.event(new TextInput("y"));
            assertEquals(1, window.tabs.size());
            int status = dev.tuink.tui.ui.OpenFileDialogController
                .layoutForTerminal(window.height, window.width).bottom() - 1;
            assertTrue(window.screenRow(status).contains("file no longer exists"));
            assertEquals(List.of("original"), window.active().tabSession().model().snapshotLines());
        }
    }

    private Path ambiguousFile() throws Exception {
        return Files.write(directory.resolve("z-ambiguous.txt"),
            new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 0, 65, 0, 0, 0});
    }

    private static void selectAndOpen(EditorWindowFixture window, Path file) throws Exception {
        window.command(TuinkCommand.DOCUMENT_OPEN);
        window.await(() -> !window.tree.loading());
        window.event(KeyStroke.of(KeyStroke.Key.END));
        assertEquals(file, window.tree.state().selectedId().path());
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }
}
