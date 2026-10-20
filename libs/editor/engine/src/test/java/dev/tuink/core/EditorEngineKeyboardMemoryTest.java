package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.language.LanguageRegistry;
import dev.tuink.unicode.GraphemeText;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.junit.jupiter.api.Test;

class EditorEngineKeyboardMemoryTest {
    @Test
    void repeatedEndOfFileEntersAndUndoShareA400000LineDocumentOn128MiB() throws Exception {
        String classpath = String.join(File.pathSeparator, location(EditorEngineKeyboardMemoryTest.class),
            location(EditorEngine.class), location(GraphemeText.class), location(DiffAlgorithm.class),
            location(LanguageRegistry.class));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx128m", "-cp", classpath, MemoryProbe.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Keyboard memory probe exceeded its deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class MemoryProbe {
        public static void main(String[] args) {
            AtomicInteger reads = new AtomicInteger();
            AtomicInteger siblings = new AtomicInteger();
            CountingBuffer source = new CountingBuffer(reads, siblings);
            for (int line = 1; line < 400_000; line++) source.insertLine(line, "x");
            SnapshotCountingModel model = new SnapshotCountingModel(source);
            EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
            engine.setGutterDiffMode(GutterDiffMode.DISABLED);
            engine.setAutoIndentEnabled(false);
            engine.setAutoListEnabled(false);
            engine.moveDocumentEnd(false);
            reads.set(0);
            siblings.set(0);
            model.snapshots = 0;

            for (int step = 0; step < 256; step++) engine.insertNewline();
            require(model.lineCount() == 400_256, "Enter lost logical lines");
            for (int step = 0; step < 256; step++) require(engine.undo(), "Enter history was lost");
            require(model.lineCount() == 400_000, "Undo lost the original line count");
            require(!engine.state().isDirty(), "Undo lost the savepoint");
            require(model.snapshots == 0, "Keyboard edits materialized a document snapshot");
            require(siblings.get() == 0, "Keyboard edits copied the document storage");
            require(reads.get() < 30_000, "Keyboard edits traversed untouched lines: " + reads);
        }

        private static void require(boolean valid, String message) {
            if (!valid) throw new AssertionError(message);
        }
    }

    private static final class SnapshotCountingModel extends TextModel {
        int snapshots;
        SnapshotCountingModel(TextBuffer source) { super(source); }
        @Override public List<String> snapshotLines() {
            snapshots++;
            return super.snapshotLines();
        }
    }

    private static final class CountingBuffer extends SimpleLineBuffer {
        private final AtomicInteger reads;
        private final AtomicInteger siblings;
        CountingBuffer(AtomicInteger reads, AtomicInteger siblings) {
            this.reads = reads;
            this.siblings = siblings;
        }
        @Override public TextBuffer createEmptySibling() {
            siblings.incrementAndGet();
            return new CountingBuffer(reads, siblings);
        }
        @Override public String getLine(int line) {
            reads.incrementAndGet();
            return super.getLine(line);
        }
        @Override protected boolean supportsPreparedPublication() { return true; }
    }
}
