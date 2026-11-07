package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SavePathInput;
import dev.tuiframe.explorer.ui.PathPresentation;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PathIdentityIntegrationTest {
    @TempDir Path directory;

    @Test
    void pickerTabsHeaderAndTabMenuDistinguishNativeBytesFromLiteralText() throws Exception {
        assumeTrue(File.separatorChar == '/');
        List<Path> paths = distinctPaths();
        for (int index = 0; index < paths.size(); index++) Files.writeString(paths.get(index), "content " + index);
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("initial"))) {
            window.command(TuinkCommand.DOCUMENT_OPEN);
            window.await(() -> !window.tree.loading());
            for (Path path : paths) assertTrue(screen(window).contains(PathPresentation.name(path)), screen(window));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            HashSet<String> headers = new HashSet<>();
            for (int index = 0; index < paths.size(); index++) {
                open(window, paths.get(index));
                assertEquals(paths.get(index), JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
                assertEquals("content " + index, window.active().tabSession().model().getLine(0));
                assertTrue(headers.add(window.screenRow(0)), window.screenRow(0));
            }
            window.resize(24, 220);
            for (Path path : paths) assertTrue(window.screenRow(1).contains(PathPresentation.name(path)), window.screenRow(1));
            window.command(TuinkCommand.MENU_TABS);
            for (Path path : paths) assertTrue(screen(window).contains(PathPresentation.name(path)), screen(window));
        }
    }

    @Test
    void overwriteShowsTheExactEncodedNameRejectsANarrowDecisionAndWritesOnlyThatPath() throws Exception {
        assumeTrue(File.separatorChar == '/');
        List<Path> paths = distinctPaths();
        for (Path path : paths) Files.writeString(path, "untouched");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("initial"))) {
            window.event(new TextInput("edited "));
            for (int index = 0; index < paths.size(); index++) {
                Path target = paths.get(index);
                window.command(TuinkCommand.DOCUMENT_SAVE_AS);
                window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
                window.event(new TextInput(PathPresentation.full(target)));
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                String prompt = window.promptText().lines().map(String::stripTrailing).collect(Collectors.joining());
                assertTrue(prompt.contains(PathPresentation.full(target)) || prompt.contains(PathPresentation.name(target)),
                    window.promptText());
                window.resize(4, 30);
                window.event(new TextInput("y"));
                assertEquals("untouched", Files.readString(target));
                assertTrue(window.controller.running());
                window.resize(24, 80);
                window.event(new TextInput("y"));
                assertEquals("edited initial", Files.readString(target));
                assertEquals(target, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
                assertFalse(window.active().state().isDirty());
                for (int neighbor = index + 1; neighbor < paths.size(); neighbor++) {
                    assertEquals("untouched", Files.readString(paths.get(neighbor)));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u202Eb.txt", "a\u200Bb.txt", "a\n.txt"})
    void generatedAndRememberedSaveAsValuesUseTheReversibleVisibleUri(String name) throws Exception {
        assumeTrue(File.separatorChar == '/');
        Path original = Files.writeString(directory.resolve(name), "original");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("initial"))) {
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(original));
            window.frame();
            window.event(new TextInput("edited "));
            for (int opening = 0; opening < 2; opening++) {
                window.command(TuinkCommand.DOCUMENT_SAVE_AS);
                assertTrue(window.filename.text().startsWith("file:/"));
                assertEquals(original, SavePathInput.parse(window.filename.text()));
                window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
                window.command(TuinkCommand.EDIT_COPY);
                assertEquals(window.filename.text(), window.host.clipboard.text());
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                assertEquals(original, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            }
            assertEquals("edited original", Files.readString(original));
        }
    }

    private List<Path> distinctPaths() {
        return List.of(raw("a%FF.txt"), raw("a%FE.txt"), directory.resolve("a�.txt"),
            directory.resolve("a%FF.txt"), directory.resolve("file:a%FF.txt"));
    }

    private Path raw(String name) {
        return Path.of(URI.create(directory.toUri().toASCIIString() + name));
    }

    private static String screen(EditorWindowFixture window) {
        return IntStream.range(0, window.height).mapToObj(window::screenRow).collect(Collectors.joining("\n"));
    }

    private static void open(EditorWindowFixture window, Path path) throws Exception {
        window.command(TuinkCommand.DOCUMENT_OPEN);
        window.await(() -> !window.tree.loading());
        window.event(KeyStroke.of(KeyStroke.Key.HOME));
        for (int index = 0; index <= 20; index++) {
            if (window.tree.state().selectedId() != null && window.tree.state().selectedId().path().equals(path)) {
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));
                return;
            }
            window.event(KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
        }
        throw new AssertionError("Picker did not expose target " + PathPresentation.full(path));
    }
}
