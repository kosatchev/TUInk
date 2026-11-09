package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.CoreDocumentBackend;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.EditorDocument;
import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.LineText;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.unicode.GraphemeText;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DocumentCommandSessionMemoryTest {
    @Test
    void fourHundredThousandLinesAndRepeatedEnterUndoStayBoundedThroughThePublicSession() throws Exception {
        String classpath = String.join(File.pathSeparator, location(getClass()), location(EditorDocumentSession.class),
            location(CoreDocumentBackend.class), location(dev.tuink.core.SearchMatcher.class), location(DocumentCommand.class), location(GraphemeText.class));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx128m", "-cp", classpath, MemoryProbe.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Session command memory probe exceeded its deadline");
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
        public static void main(String[] args) throws Exception {
            AtomicInteger reads = new AtomicInteger();
            AtomicInteger siblings = new AtomicInteger();
            var source = new CountingBuffer(reads, siblings);
            for (int line = 1; line < 400_000; line++) source.insertLine(line, "x");
            var model = new SnapshotCountingModel(source);
            var tab = new EditorDocument(model).attach(LogicalEditorTextLayout::new, new EditorClipboard());
            tab.engine().setGutterDiffMode(GutterDiffMode.DISABLED);
            tab.engine().setAutoIndentEnabled(false);
            tab.engine().setAutoListEnabled(false);
            var session = EditorDocumentSession.connect(CoreDocumentBackend.adopt(new DocumentId("memory"), tab));
            var published = session.snapshot();
            var view = session.defaultViewId();
            session.execute(view, new DocumentCommand.Navigate(DocumentCommand.Navigation.DOCUMENT_END, false));
            reads.set(0);
            siblings.set(0);
            model.snapshots = 0;
            var enter = new DocumentCommand.Edit(DocumentCommand.EditAction.NEWLINE);
            var undo = new DocumentCommand.Edit(DocumentCommand.EditAction.UNDO);

            for (int step = 0; step < 256; step++) {
                var result = session.execute(view, enter);
                require(result instanceof DocumentCommandResult.Applied applied && applied.textChanged(), "Enter result");
            }
            require(model.lineCount() == 400_256, "Enter lost lines");
            require(backgroundSnapshot(session) == published, "Commands published a whole-document snapshot");
            for (int step = 0; step < 256; step++) {
                var result = session.execute(view, undo);
                require(result instanceof DocumentCommandResult.Applied applied && applied.performed(), "Undo result");
            }
            require(model.lineCount() == 400_000, "Undo lost original lines");
            require(!session.status().dirty(), "Undo lost the savepoint");
            require(backgroundSnapshot(session) == published, "History replay published a whole-document snapshot");
            require(model.snapshots == 0, "Commands copied line references: " + model.snapshots);
            require(siblings.get() == 0, "Commands copied document storage");
            require(reads.get() < 30_000, "Commands traversed untouched lines: " + reads);
            var current = session.snapshot();
            require(current != published && current.lineCount() == 400_000, "Explicit owner publication");
            int captures = model.snapshots;
            require(session.snapshot() == current && backgroundSnapshot(session) == current, "Published snapshot reuse");
            require(model.snapshots == captures, "Unchanged snapshot recaptured text");
        }

        private static DocumentSnapshot backgroundSnapshot(EditorDocumentSession session) throws InterruptedException {
            var result = new AtomicReference<DocumentSnapshot>();
            Thread reader = new Thread(() -> result.set(session.snapshot()));
            reader.start(); reader.join();
            return result.get();
        }

        private static void require(boolean value, String message) {
            if (!value) throw new AssertionError(message);
        }
    }

    private static final class SnapshotCountingModel extends TextModel {
        int snapshots;
        SnapshotCountingModel(TextBuffer buffer) { super(buffer); }
        @Override public List<String> snapshotLines() { snapshots++; return super.snapshotLines(); }
        @Override public List<LineText> snapshotTextLines() { snapshots++; return super.snapshotTextLines(); }
    }

    private static final class CountingBuffer extends SimpleLineBuffer {
        private final AtomicInteger reads;
        private final AtomicInteger siblings;
        CountingBuffer(AtomicInteger reads, AtomicInteger siblings) { this.reads = reads; this.siblings = siblings; }
        @Override public TextBuffer createEmptySibling() {
            siblings.incrementAndGet(); return new CountingBuffer(reads, siblings);
        }
        @Override protected boolean supportsPreparedPublication() { return true; }
        @Override public String getLine(int line) { reads.incrementAndGet(); return super.getLine(line); }
        @Override public LineText lineText(int line) { reads.incrementAndGet(); return super.lineText(line); }
    }
}
