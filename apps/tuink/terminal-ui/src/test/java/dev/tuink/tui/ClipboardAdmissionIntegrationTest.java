package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.PasteEvent;
import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.Position;
import dev.tuink.tui.command.TuinkCommand;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClipboardAdmissionIntegrationTest {
    @Test
    void aLargeCopyAndCutReportFailureAndLeaveTheWindowUsable(@TempDir Path directory) throws Exception {
        var locations = new LinkedHashSet<String>();
        for (Class<?> type : List.of(getClass(), EditorWindowFixture.class)) locations.add(location(type));
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
        )) locations.add(location(Class.forName(name, false, getClass().getClassLoader())));
        MarkdownProbeClasspath.addTo(locations);
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-cp", String.join(File.pathSeparator, locations), Probe.class.getName(),
            directory.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Clipboard probe exceeded its deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            try (EditorWindowFixture window = new EditorWindowFixture(Path.of(args[0]), false, List.of(""))) {
                window.ui.wrapLinesSetting = false;
                var tab = window.active().tabSession();
                var engine = tab.engine();
                engine.setGutterDiffMode(GutterDiffMode.DISABLED);
                for (int index = 0; index < 30; index++) {
                    window.event(new PasteEvent("a".repeat(1_048_575) + "\n"));
                }
                require(tab.model().lineCount() >= 5, "The large document was not built");
                window.host.clipboard.setText("previous clipboard");
                window.command(TuinkCommand.EDIT_SELECT_ALL);
                var original = tab.model().snapshotLines();
                var metadata = tab.model().fileMetadata();
                var view = tab.state().viewMemento();
                long revision = tab.model().revision();
                long navigation = engine.navigationRevision();
                for (TuinkCommand command : List.of(TuinkCommand.EDIT_COPY, TuinkCommand.EDIT_CUT)) {
                    window.command(command);
                    require(window.notification.message.equals("Not enough memory to copy this text; clipboard unchanged"),
                        "Missing refusal for " + command + ": " + window.notification.message);
                    require(window.notification.style().equals(window.host.styles.notifyError()), "Wrong error style");
                    require(window.host.clipboard.text().equals("previous clipboard"), "Clipboard changed");
                    require(tab.model().snapshotLines().equals(original), "Document changed");
                    require(tab.model().fileMetadata().equals(metadata), "Metadata changed");
                    require(tab.model().revision() == revision, "Revision changed");
                    require(engine.navigationRevision() == navigation, "Navigation changed");
                    require(tab.state().viewMemento().equals(view), "View changed");
                    require(engine.captureRecoverySnapshot().lines().equals(original), "Recovery lost text");
                }
                engine.restoreSelection(new Position(0, 16), new Position(0, 0));
                window.command(TuinkCommand.EDIT_COPY);
                require(window.host.clipboard.text().equals("a".repeat(16)), "Small copy failed after refusal");
                require(engine.undo(), "Undo history lost");
                require(engine.redo(), "Redo history lost");
                require(tab.model().snapshotLines().equals(original), "Undo/redo changed the text");
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
