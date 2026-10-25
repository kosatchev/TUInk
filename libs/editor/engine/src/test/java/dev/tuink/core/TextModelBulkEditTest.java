package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextModelBulkEditTest {
    @Test
    void deletingAndUndoingAMillionLinesAvoidsRepeatedSeparatorShifts() {
        int lineCount = 1_000_000;
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(Collections.nCopies(lineCount, ""));
        EditorEngine engine = new EditorEngine(
            model, new EditorState(), new LogicalEditorTextLayout(model)
        );
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        engine.selectAll();

        // The former per-separator removal takes tens of seconds for this
        // document. Leave ample headroom for the linear edit and its history.
        assertTimeout(Duration.ofSeconds(15), () -> {
            engine.deleteForward();
            assertEquals(List.of(""), model.snapshotLines());
            assertEquals(List.of(), model.fileMetadata().lineSeparators());
            assertTrue(engine.undo());
            assertEquals(lineCount, model.lineCount());
            assertEquals(lineCount - 1, model.fileMetadata().lineSeparators().size());
            assertTrue(engine.redo());
            assertEquals(List.of(""), model.snapshotLines());
            assertEquals(List.of(), model.fileMetadata().lineSeparators());
        });
    }

    @Test
    void deletingAndUndoingALargePrefixMovesTheRetainedSuffixAsABlock() {
        int prefixLines = 500_000;
        int suffixLines = 500_000;
        List<String> original = new ArrayList<>(prefixLines + suffixLines);
        original.addAll(Collections.nCopies(prefixLines, "prefix"));
        original.addAll(Collections.nCopies(suffixLines, "retained suffix"));
        List<String> separators = new ArrayList<>(original.size() - 1);
        List<String> separatorCycle = List.of("\r\n", "\n", "\r");
        for (int line = 1; line < original.size(); line++) {
            separators.add(separatorCycle.get(line % separatorCycle.size()));
        }
        DocumentFileMetadata metadata = new DocumentFileMetadata(
            StandardCharsets.UTF_8, BomKind.NONE, "\r\n", separators
        );
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(original, metadata);
        EditorEngine engine = new EditorEngine(
            model, new EditorState(), new LogicalEditorTextLayout(model)
        );
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        engine.beginSelectionAt(new Position(0, 0));
        engine.extendSelectionTo(new Position(prefixLines, 0));

        // Single-line ArrayList removals/inserts move this half-million-line
        // suffix once per edited line. Both directions must use block edits.
        assertTimeout(Duration.ofSeconds(15), () -> {
            engine.deleteForward();
            assertEquals(original.subList(prefixLines, original.size()), model.snapshotLines());
            assertEquals(
                separators.subList(prefixLines, separators.size()),
                model.fileMetadata().lineSeparators()
            );
            assertSame(original.get(prefixLines + 1), model.getLine(1));

            assertTrue(engine.undo());
            assertEquals(original, model.snapshotLines());
            assertEquals(metadata, model.fileMetadata());
            assertSame(original.get(prefixLines + 1), model.getLine(prefixLines + 1));

            assertTrue(engine.redo());
            assertEquals(original.subList(prefixLines, original.size()), model.snapshotLines());
            assertEquals(
                separators.subList(prefixLines, separators.size()),
                model.fileMetadata().lineSeparators()
            );
        });
    }

    @Test
    void customBuffersWithOnlySingleLineOperationsRetainBulkEditAndHistorySupport() {
        TextModel model = new TextModel(new SingleLineOnlyBuffer());
        DocumentFileMetadata metadata = new DocumentFileMetadata(
            StandardCharsets.UTF_8, BomKind.NONE, "\r\n", List.of("\n", "\r")
        );
        model.replaceLines(List.of("prefix", "middle", "suffix"), metadata);
        EditorEngine engine = new EditorEngine(
            model, new EditorState(), new LogicalEditorTextLayout(model)
        );
        engine.beginSelectionAt(new Position(0, 3));
        engine.extendSelectionTo(new Position(1, 3));

        engine.insertInput("A\nB\nC");

        assertEquals(List.of("preA", "B", "Cdle", "suffix"), model.snapshotLines());
        assertEquals(List.of("\r\n", "\r\n", "\r"), model.fileMetadata().lineSeparators());
        assertTrue(engine.undo());
        assertEquals(List.of("prefix", "middle", "suffix"), model.snapshotLines());
        assertEquals(metadata, model.fileMetadata());
        assertTrue(engine.redo());
        assertEquals(List.of("preA", "B", "Cdle", "suffix"), model.snapshotLines());
    }

    @Test
    void bulkSeparatorUpdatesPreserveTheUntouchedMixedSeparators() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(
            List.of("a", "b", "c", "d", "e", "f"),
            new DocumentFileMetadata(
                StandardCharsets.UTF_8, BomKind.NONE, "\r\n",
                List.of("\r\n", "\n", "\r", "\n", "\r\n")
            )
        );

        model.deleteRange(new Position(1, 0), new Position(3, 0));
        assertEquals(List.of("a", "d", "e", "f"), model.snapshotLines());
        assertEquals(List.of("\r\n", "\n", "\r\n"), model.fileMetadata().lineSeparators());

        model.insertText(new Position(1, 1), "x\ny\nz");
        assertEquals(List.of("a", "dx", "y", "z", "e", "f"), model.snapshotLines());
        assertEquals(
            List.of("\r\n", "\r\n", "\r\n", "\n", "\r\n"),
            model.fileMetadata().lineSeparators()
        );
    }

    /** An existing storage implementation need not implement the new bulk methods. */
    private static final class SingleLineOnlyBuffer implements TextBuffer {
        private final SimpleLineBuffer delegate = new SimpleLineBuffer();

        @Override
        public TextBuffer createEmptySibling() {
            return new SingleLineOnlyBuffer();
        }

        @Override
        public int lineCount() {
            return delegate.lineCount();
        }

        @Override
        public String getLine(int line) {
            return delegate.getLine(line);
        }

        @Override
        public void setLine(int line, String text) {
            delegate.setLine(line, text);
        }

        @Override
        public void insertLine(int index, String text) {
            delegate.insertLine(index, text);
        }

        @Override
        public String removeLine(int index) {
            return delegate.removeLine(index);
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }
}
