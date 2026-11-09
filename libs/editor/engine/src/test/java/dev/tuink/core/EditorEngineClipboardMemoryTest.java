package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.language.LanguageRegistry;
import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.unicode.GraphemeText;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditorEngineClipboardMemoryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copyingAnAdmittedMultilineDocumentCannotExhaustTheHeap(boolean disableCompactStrings) throws Exception {
        probe("selection", disableCompactStrings);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aLargeCurrentLineWithItsNewlineIsRefusedBeforeChangingTheDocument(boolean disableCompactStrings)
        throws Exception {
        probe("line", disableCompactStrings);
    }

    private static void probe(String mode, boolean disableCompactStrings) throws Exception {
        String classpath = String.join(File.pathSeparator, location(EditorEngineClipboardMemoryTest.class),
            location(EditorEngine.class), location(dev.tuink.core.SearchMatcher.class), location(EditorDocument.class), location(GraphemeText.class),
            location(LanguageRegistry.class));
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx64m"));
        if (disableCompactStrings) command.add("-XX:-CompactStrings");
        command.addAll(List.of("-cp", classpath, MemoryProbe.class.getName(), mode));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Clipboard probe exceeded its deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), mode + ", compactStrings=" + !disableCompactStrings + ": " + output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class MemoryProbe {
        public static void main(String[] args) {
            EditorClipboard clipboard = new EditorClipboard();
            clipboard.setText("previous copied text 😀");
            EditorEngine engine;
            if (args[0].equals("selection")) {
                engine = engine(List.of(""), clipboard);
                String chunk = "a".repeat(1_048_575) + "\n";
                int admitted = 0;
                try {
                    for (; admitted < 80; admitted++) engine.pasteText(chunk);
                    throw new AssertionError("The fixture did not reach paste admission");
                } catch (DocumentEditLimitException expected) {
                    require(admitted >= 4, "The fixture must retain a substantial admitted document");
                }
                engine.insertInput("X");
                require(engine.undo(), "Could not prepare a redo branch");
                engine.selectAll();
                engine.state().setViewport(new ViewportState(1, 2, 3, 4));

                reject(engine, clipboard, engine::copySelection);
                reject(engine, clipboard, engine::cutSelection);
                engine.restoreSelection(new Position(0, 3), new Position(0, 0));
                require(engine.copySelection() && clipboard.text().equals("aaa"),
                    "A small copy must still work after a refused large copy/cut");
                List<String> beforeRedo = engine.model().snapshotLines();
                require(engine.redo(), "Refusal or small copy discarded existing redo");
                require(engine.undo() && engine.model().snapshotLines().equals(beforeRedo),
                    "Existing redo no longer round-trips");
            } else {
                engine = engine(List.of("a".repeat(10 * 1_048_576), "tail"), clipboard);
                engine.moveHome();
                engine.state().setViewport(new ViewportState(0, 1, 2, 3));

                reject(engine, clipboard, engine::copyCurrentLine);
                reject(engine, clipboard, engine::cutCurrentLine);
                engine.moveCursorTo(new Position(1, 0));
                require(engine.copyCurrentLine() && clipboard.text().equals("tail"),
                    "A small current-line copy must remain usable after refusal");
                require(!engine.undo(), "Rejected extraction must not manufacture history");
            }
        }

        private static EditorEngine engine(List<String> lines, EditorClipboard clipboard) {
            TextModel model = new TextModel(new SimpleLineBuffer());
            model.replaceLines(lines, new DocumentFileMetadata(StandardCharsets.UTF_16LE, BomKind.UTF_16_LE,
                "\r\n", java.util.Collections.nCopies(lines.size() - 1, "\r\n")));
            EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model), clipboard);
            engine.setGutterDiffMode(GutterDiffMode.DISABLED);
            engine.setAutoIndentEnabled(false);
            engine.setAutoListEnabled(false);
            return engine;
        }

        private static void reject(EditorEngine engine, EditorClipboard clipboard, Runnable attempted) {
            List<String> lines = engine.model().snapshotLines();
            DocumentFileMetadata metadata = engine.model().fileMetadata();
            EditorViewMemento view = engine.state().viewMemento();
            long revision = engine.model().revision();
            long epoch = engine.model().contentEpoch();
            long navigation = engine.navigationRevision();
            boolean dirty = engine.state().isDirty();
            String copied = clipboard.text();
            try {
                attempted.run();
                throw new AssertionError("Oversized clipboard extraction was admitted");
            } catch (ClipboardLimitException expected) {
                require(expected.getMessage().contains("clipboard unchanged"), "Missing actionable clipboard refusal");
            }
            require(clipboard.text() == copied, "Refusal replaced the existing clipboard");
            require(engine.model().snapshotLines().equals(lines), "Refusal changed document content");
            require(engine.model().fileMetadata().equals(metadata), "Refusal changed encoding, BOM or separators");
            require(engine.state().viewMemento().equals(view), "Refusal changed cursor, selection or viewport");
            require(engine.model().revision() == revision && engine.model().contentEpoch() == epoch,
                "Refusal changed revision or content epoch");
            require(engine.navigationRevision() == navigation && engine.state().isDirty() == dirty,
                "Refusal changed navigation or dirty state");
            RecoverySnapshot recovery = engine.captureRecoverySnapshot();
            require(recovery.lines().equals(lines) && recovery.fileMetadata().equals(metadata),
                "Refusal left an invalid recovery snapshot");
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
