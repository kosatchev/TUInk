package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextModelRevisionTest {
    @TempDir
    Path tempDir;

    @Test
    void ordinaryEditsAdvanceRevisionWithoutReplacingContentEpoch() {
        TextModel model = new TextModel(new SimpleLineBuffer());

        model.insertChar(new Position(0, 0), 'a');
        assertEquals(1, model.revision());
        assertEquals(0, model.contentEpoch());

        model.insertNewline(new Position(0, 1));
        assertEquals(2, model.revision());
        assertEquals(0, model.contentEpoch());

        model.deleteBackward(new Position(1, 0));
        assertEquals(3, model.revision());
        assertEquals(0, model.contentEpoch());
    }

    @Test
    void noOpEditsAndPersistenceDoNotAdvanceRevision() throws IOException {
        TextModel model = new TextModel(new SimpleLineBuffer());

        model.insertText(new Position(0, 0), "");
        model.deleteBackward(new Position(0, 0));
        model.deleteForward(new Position(0, 0));
        model.deleteRange(new Position(0, 0), new Position(0, 0));
        model.save(JvmDocumentStorage.resource(tempDir.resolve("empty.txt")));

        assertEquals(0, model.revision());
        assertEquals(0, model.contentEpoch());
    }

    @Test
    void fullReplacementAdvancesBothGenerations() {
        TextModel model = new TextModel(new SimpleLineBuffer());

        model.replaceLines(List.of("first", "second"));
        assertEquals(1, model.revision());
        assertEquals(1, model.contentEpoch());

        model.replaceLines(List.of());
        assertEquals(2, model.revision());
        assertEquals(2, model.contentEpoch());
    }

    @Test
    void loadingEmptyFileOverContentInvalidatesEveryVersionedConsumer() throws IOException {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("previous content"));
        long revision = model.revision();
        long epoch = model.contentEpoch();
        Path empty = tempDir.resolve("empty.txt");
        Files.writeString(empty, "");

        model.load(JvmDocumentStorage.resource(empty));

        assertEquals(List.of(""), model.snapshotLines());
        assertEquals(revision + 1, model.revision());
        assertEquals(epoch + 1, model.contentEpoch());
    }

    @Test
    void failedLoadKeepsVersionsAndCacheIdentityIsStableAndUnique() {
        TextModel first = new TextModel(new SimpleLineBuffer());
        TextModel second = new TextModel(new SimpleLineBuffer());
        long identity = first.cacheIdentity();

        assertThrows(IOException.class, () -> first.load(JvmDocumentStorage.resource(tempDir.resolve("missing.txt"))));

        assertEquals(0, first.revision());
        assertEquals(0, first.contentEpoch());
        assertEquals(identity, first.cacheIdentity());
        assertNotEquals(identity, second.cacheIdentity());
    }

    @Test
    void constructorDefensivelyImportsAndValidatesTheStorageBuffer() {
        SimpleLineBuffer source = new SimpleLineBuffer();
        source.setLine(0, "owned");
        TextModel model = new TextModel(source);

        source.setLine(0, "external mutation");
        source.insertLine(1, "extra");

        assertEquals(List.of("owned"), model.snapshotLines());
        assertEquals(0, model.revision());
        assertEquals(List.of(), model.fileMetadata().lineSeparators());

        SimpleLineBuffer malformed = new SimpleLineBuffer();
        malformed.setLine(0, "two\nlines");
        assertThrows(IllegalArgumentException.class, () -> new TextModel(malformed));
    }

    @Test
    void constructorPreservesTheSuppliedStorageStrategyWithoutRetainingItsAlias() {
        final class StrategyBuffer extends SimpleLineBuffer {
            private final int generation;

            private StrategyBuffer(int generation) {
                this.generation = generation;
            }

            @Override
            public TextBuffer createEmptySibling() {
                return new StrategyBuffer(generation + 1);
            }

            @Override
            public void setLine(int line, String text) {
                if (generation == 1 && "edited".equals(text)) {
                    throw new StrategyObservedException();
                }
                super.setLine(line, text);
            }
        }
        StrategyBuffer source = new StrategyBuffer(0);
        TextModel model = new TextModel(source);

        assertThrows(
            StrategyObservedException.class,
            () -> model.insertText(new Position(0, 0), "edited")
        );
        assertEquals(1, source.lineCount());
        assertEquals("", source.getLine(0));
    }

    @Test
    void changedSuffixStartsAtTheEarliestLineAcrossUnconsumedMutations() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("one", "two", "three", "four"));
        long before = model.revision();
        assertEquals(model.lineCount(), model.firstChangedLineSince(before));

        model.insertChar(new Position(3, 0), 'x');
        assertEquals(3, model.firstChangedLineSince(before));
        long afterFirst = model.revision();
        model.insertNewline(new Position(2, 1));
        assertEquals(2, model.firstChangedLineSince(before));
        assertEquals(2, model.firstChangedLineSince(afterFirst));
        model.deleteBackward(new Position(2, 0));
        assertEquals(1, model.firstChangedLineSince(before));
        model.deleteForward(new Position(0, 0));
        assertEquals(0, model.firstChangedLineSince(before));
    }

    @Test
    void changeHistoryCoversEveryMutationKindAndFallsBackWhenItsWindowIsLost() throws IOException {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("one", "two", "three"));
        long before = model.revision();
        model.insertText(new Position(2, 0), "x\ny");
        assertEquals(2, model.firstChangedLineSince(before));
        before = model.revision();
        model.deleteRange(new Position(1, 1), new Position(2, 0));
        assertEquals(1, model.firstChangedLineSince(before));
        before = model.revision();
        model.deleteBackward(new Position(1, 1));
        assertEquals(1, model.firstChangedLineSince(before));
        before = model.revision();
        model.deleteForward(new Position(1, model.getLine(1).length()));
        assertEquals(1, model.firstChangedLineSince(before));

        before = model.revision();
        for (int index = 0; index < 128; index++) {
            model.insertChar(new Position(1, 0), 'x');
        }
        assertEquals(1, model.firstChangedLineSince(before));
        model.insertChar(new Position(1, 0), 'x');
        assertEquals(0, model.firstChangedLineSince(before));
        assertEquals(1, model.firstChangedLineSince(before + 1));
        assertEquals(0, model.firstChangedLineSince(-1));
        assertEquals(0, model.firstChangedLineSince(model.revision() + 1));

        before = model.revision();
        model.replaceLines(List.of("replaced"));
        assertEquals(0, model.firstChangedLineSince(before));
        Path source = tempDir.resolve("loaded.txt");
        Files.writeString(source, "loaded");
        before = model.revision();
        model.load(JvmDocumentStorage.resource(source));
        assertEquals(0, model.firstChangedLineSince(before));
        before = model.revision();
        model.insertText(new Position(0, 0), "");
        model.save(JvmDocumentStorage.resource(source));
        assertEquals(model.lineCount(), model.firstChangedLineSince(before));
    }

    private static final class StrategyObservedException extends RuntimeException {
    }
}
