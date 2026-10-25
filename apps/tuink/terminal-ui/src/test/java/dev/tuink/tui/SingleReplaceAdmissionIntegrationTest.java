package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.Position;
import dev.tuink.core.ViewportState;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SearchFocus;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleReplaceAdmissionIntegrationTest {
    @Test
    void rejectedSingleReplaceKeepsTheWindowDocumentAndHistoryOn64MiB(@TempDir Path directory) throws Exception {
        var locations = new LinkedHashSet<String>();
        locations.add(location(getClass()));
        // The child uses the real window with in-memory terminal/recovery ports.
        // Include each runtime module and the assertions used by its fixture.
        for (String name : List.of(
            "dev.tuink.tui.TerminalEditorApp", "dev.tuink.storage.jvm.JvmDocumentStorage", "dev.tuink.core.EditorEngine",
            "dev.tuink.editor.language.LanguageRegistry", "dev.tuink.unicode.GraphemeText",
            "dev.tuink.editor.session.document.EditorDocumentSession", "dev.tuink.render.RenderPlanBuilder",
            "dev.tuink.highlight.regex.RegexHighlighter", "dev.tuiframe.cell.CellBuffer",
            "dev.tuiframe.widget.TextField", "dev.tuiframe.jline.presentation.ScreenPresenter",
            "dev.tuink.diff.jgit.JgitLineDiff", "org.eclipse.jgit.diff.DiffAlgorithm", "org.slf4j.LoggerFactory",
            "com.googlecode.javaewah.EWAHCompressedBitmap", "org.apache.commons.codec.binary.Hex",
            "org.jline.terminal.Terminal", "org.junit.jupiter.api.Assertions",
            "org.junit.platform.commons.util.StringUtils", "org.opentest4j.AssertionFailedError"
        )) {
            locations.add(location(Class.forName(name, false, getClass().getClassLoader())));
        }
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-cp", String.join(File.pathSeparator, locations), WindowProbe.class.getName(),
            directory.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Single Replace window probe exceeded its deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class WindowProbe {
        public static void main(String[] args) throws Exception {
            try (EditorWindowFixture window = new EditorWindowFixture(Path.of(args[0]), false,
                List.of("a".repeat(1024 * 1024) + "needle" + "a".repeat(1024 * 1024)))) {
                window.ui.wrapLinesSetting = false;
                var tab = window.active().tabSession();
                var engine = tab.engine();
                window.command(TuinkCommand.SEARCH_OPEN);
                window.event(new TextInput("needle"));
                window.focusSearch(SearchFocus.REPLACE);
                // Set up the replacement without transient append copies competing
                // with the document under test for the child's small heap.
                window.event(new PasteEvent("b".repeat(16 * 1_048_576)));
                require(window.replacement.text().length() == 16 * 1_048_576, "Replacement field was truncated");
                engine.restoreSelection(new Position(0, 120), new Position(0, 100));
                tab.state().setViewport(new ViewportState(0, 0, 64, 64));
                window.frame();
                var before = tab.state().viewMemento();
                var metadata = tab.model().fileMetadata();
                var original = tab.model().snapshotLines();
                long revision = tab.model().revision();

                window.command(TuinkCommand.SEARCH_REPLACE);

                require(window.notification.message.startsWith("Replacement result is too large"),
                    "Missing single-replacement admission error");
                require(window.notification.style().equals(window.host.styles.notifyError()), "Wrong error style");
                require(window.notification.nanosUntilExpire(System.nanoTime()) == -1, "Error must persist");
                require(tab.model().snapshotLines().equals(original), "Rejected Replace changed the document");
                require(tab.model().fileMetadata().equals(metadata), "Rejected Replace changed file metadata");
                require(tab.model().revision() == revision, "Rejected Replace changed the revision");
                require(tab.state().viewMemento().equals(before), "Rejected Replace changed the view");
                require(!tab.state().isDirty(), "Rejected Replace changed the dirty flag");
                require(engine.captureRecoverySnapshot().lines().equals(original), "Recovery lost the original text");
                require(!engine.undo(), "Rejected Replace created an undo entry");
                require(!engine.redo(), "Rejected Replace created a redo entry");
                require(Files.readString(window.document).equals(original.getFirst()), "The disk file changed");
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
