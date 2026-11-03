package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.TextInput;
import dev.tuiframe.jline.presentation.ScreenPresenter;
import dev.tuiframe.text.TerminalText;
import dev.tuiframe.widget.TextField;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.DocumentView;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.highlight.regex.RegexHighlighter;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.command.TuinkCommand;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LexicalStateMemoryIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"js", "ts", "md"})
    void multipleAcceptedDocumentsRenderWithoutLosingAnotherDraft(String extension, @TempDir Path directory)
        throws Exception {
        boolean markdown = extension.equals("md");
        Path first = Files.writeString(directory.resolve("first." + extension),
            markdown ? "- ".repeat(450_000) + "x" : "(".repeat(900_000));
        Path second = Files.writeString(directory.resolve("second." + extension),
            markdown ? "- ".repeat(400_000) + "x" : "(".repeat(800_000));
        Path balanced = Files.writeString(directory.resolve("balanced." + extension),
            markdown ? "- ".repeat(300_000) + "x\n" + "  ".repeat(300_000) + "continuation"
                : "(".repeat(300_000) + "\n0\n" + ")".repeat(300_000) + ";");
        Set<String> locations = new LinkedHashSet<>();
        for (Class<?> type : List.of(getClass(), EditorWindowFixture.class, dev.tuink.storage.jvm.JvmDocumentStorage.class, TerminalEditorApp.class,
            TerminalText.class, TextField.class, ScreenPresenter.class, TextModel.class,
            DocumentView.class, EditorDocumentSession.class, RegexHighlighter.class,
            TerminalEditorTextLayout.class, org.junit.jupiter.api.Assertions.class)) {
            locations.add(location(type));
        }
        for (String type : List.of("dev.tuink.unicode.GraphemeText", "dev.tuink.diff.jgit.JgitLineDiff",
            "org.eclipse.jgit.diff.DiffAlgorithm",
            "org.slf4j.LoggerFactory", "org.opentest4j.AssertionFailedError",
            "org.junit.platform.commons.util.Preconditions")) {
            locations.add(location(Class.forName(type)));
        }
        MarkdownProbeClasspath.addTo(locations);
        for (boolean syntax : new boolean[] {true, false}) {
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-cp", String.join(File.pathSeparator, locations), Probe.class.getName(),
                directory.toString(), Boolean.toString(syntax), first.toString(), second.toString(), balanced.toString())
                .directory(directory.toFile()).redirectErrorStream(true).start();
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Lexical state rendering exceeded its deadline");
                String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, child.exitValue(), extension + ", syntax=" + syntax + ": " + output);
            } finally {
                child.destroyForcibly();
            }
        }
        assertEquals(markdown ? 900_001 : 900_000, Files.size(first));
        assertEquals(markdown ? 800_001 : 800_000, Files.size(second));
        assertEquals(markdown ? 1_200_014 : 600_004, Files.size(balanced));
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
                window.ui.syntaxHighlightingSetting = Boolean.parseBoolean(args[1]);
                window.event(new TextInput("unsaved "));
                var source = window.active();
                for (int index = 2; index < args.length; index++) {
                    Path file = Path.of(args[index]);
                    TuiDocumentSession document = TuiDocumentSession.create(window.host.clipboard);
                    document.view().engine().openExisting(JvmDocumentStorage.resource(file));
                    int firstLineLength = document.view().model().getLine(0).length();
                    var builder = document.newRenderPlanBuilder(window.host.resolveHighlighter(file));
                    window.context.addNewTabAndActivate(new TerminalEditorApp.EditorTab(document, builder));
                    window.frame();
                    window.resize(24, 20);
                    window.command(TuinkCommand.CURSOR_DOCUMENT_END);
                    window.resize(24, 80);
                    for (int frame = 0; frame < 3; frame++) window.frame();
                    require(!builder.hasPendingSyntaxWork(), "A permanent lexical limit must not schedule endless retries");
                    require(!document.view().state().isDirty(), "Rendering changed the document savepoint");
                    require(document.view().model().getLine(0).length() == firstLineLength,
                        "Rendering changed the accepted text");
                }
                window.context.activate(0);
                window.frame();
                require(source.state().isDirty(), "Rendering other tabs lost the original draft");
                require(source.tabSession().model().snapshotLines().equals(List.of("unsaved original")),
                    "Rendering changed another document");
                window.command(TuinkCommand.EDIT_UNDO);
                require(!source.state().isDirty(), "Rendering damaged the original undo savepoint");
                window.command(TuinkCommand.EDIT_REDO);
                require(source.state().isDirty(), "Rendering damaged the original redo history");
                String warning = window.recoveryManager.finishSession(window.host.toDocumentSessions(window.tabs), false);
                require(warning == null, "Recovery failed: " + warning);
                require(window.recoverySnapshots.values().stream()
                    .anyMatch(snapshot -> snapshot.lines().equals(List.of("unsaved original"))),
                    "The original draft lost its recovery snapshot");
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
