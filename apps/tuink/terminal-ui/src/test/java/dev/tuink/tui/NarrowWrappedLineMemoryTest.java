package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.jline.presentation.ScreenPresenter;
import dev.tuiframe.text.TerminalText;
import dev.tuiframe.widget.TextField;
import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.Position;
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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NarrowWrappedLineMemoryTest {
    @Test
    void resizingADirtyLongLineKeepsNavigationHistoryAndRecoveryInASmallHeap(@TempDir Path directory)
        throws Exception {
        Path file = directory.resolve("long-line.txt");
        byte[] chunk = new byte[8192];
        Arrays.fill(chunk, (byte) 'a');
        try (var output = Files.newOutputStream(file)) {
            for (int index = 0; index < 256; index++) output.write(chunk);
        }
        Set<String> locations = new LinkedHashSet<>();
        for (Class<?> type : List.of(getClass(), EditorWindowFixture.class, dev.tuink.storage.jvm.JvmDocumentStorage.class, TerminalEditorApp.class,
            TerminalText.class, TextField.class,
            ScreenPresenter.class, TextModel.class, DocumentView.class, EditorDocumentSession.class,
            RegexHighlighter.class, TerminalEditorTextLayout.class,
            org.junit.jupiter.api.Assertions.class)) {
            locations.add(location(type));
        }
        for (String type : List.of("dev.tuink.unicode.GraphemeText", "dev.tuink.diff.jgit.JgitLineDiff",
            "org.eclipse.jgit.diff.DiffAlgorithm", "org.slf4j.LoggerFactory",
            "org.opentest4j.AssertionFailedError", "org.junit.platform.commons.util.Preconditions")) {
            locations.add(location(Class.forName(type)));
        }
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-cp", String.join(File.pathSeparator, locations), NarrowProbe.class.getName(),
            file.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Narrow wrapped editor exceeded its deadline");
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
        } finally {
            child.destroyForcibly();
        }
        assertEquals(2 * 1024 * 1024, Files.size(file));
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class NarrowProbe {
        public static void main(String[] args) throws Exception {
            Path file = Path.of(args[0]);
            try (EditorWindowFixture window = new EditorWindowFixture(file.getParent(), false, List.of(""))) {
                var session = window.active().tabSession();
                session.engine().open(JvmDocumentStorage.resource(file));
                int originalLength = session.model().getLine(0).length();
                window.ui.wrapLinesSetting = true;
                window.ui.syntaxHighlightingSetting = false;
                window.ui.rainbowBracketsSetting = false;
                window.ui.gutterDiffModeSetting = GutterDiffMode.DISABLED;
                session.engine().insertInput("x");
                window.frame();
                long revision = session.model().revision();

                for (boolean words : new boolean[] {false, true}) {
                    window.ui.wrapWordsSetting = words;
                    window.resize(24, 5);
                    window.command(TuinkCommand.CURSOR_DOCUMENT_END);
                    require(session.state().getCursor().equals(new Position(0, originalLength + 1)),
                        "End navigation lost its document position");
                    require(session.state().getTopWrapSegment() > 0, "End navigation did not reveal the caret");
                    window.command(TuinkCommand.CURSOR_PAGE_UP);
                    require(session.state().getCursor().col() < originalLength + 1, "Page Up did not navigate");
                    window.command(TuinkCommand.CURSOR_DOCUMENT_START);
                    require(session.state().getCursor().equals(new Position(0, 0)), "Start navigation changed");
                    window.command(TuinkCommand.CURSOR_PAGE_DOWN);
                    require(session.state().getCursor().col() > 0, "Page Down did not navigate");
                    window.resize(24, 80);
                    require(session.model().revision() == revision, "Resize or navigation edited the document");
                }

                require(session.state().isDirty(), "Resizing cleared the unsaved edit");
                require(session.model().getLine(0).length() == originalLength + 1
                    && session.model().getLine(0).startsWith("x"), "Resizing changed the edited text");
                window.command(TuinkCommand.EDIT_UNDO);
                require(!session.state().isDirty() && session.model().getLine(0).length() == originalLength,
                    "Resizing damaged the edit's undo history");
                window.command(TuinkCommand.EDIT_REDO);
                require(session.state().isDirty(), "Redo did not restore the unsaved edit");
                String warning = window.recoveryManager.finishSession(window.host.toTabSessions(window.tabs), false);
                require(warning == null, "Final recovery failed: " + warning);
                require(window.recoverySnapshots.size() == 1, "The dirty document lost its recovery snapshot");
                var snapshot = window.recoverySnapshots.values().iterator().next();
                require(snapshot.lines().getFirst().length() == originalLength + 1
                    && snapshot.lines().getFirst().startsWith("x"), "Recovery lost the latest unsaved text");
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
