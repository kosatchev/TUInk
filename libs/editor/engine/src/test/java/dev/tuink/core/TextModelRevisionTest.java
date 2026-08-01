package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        model.save(tempDir.resolve("empty.txt"));

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

        model.load(empty);

        assertEquals(List.of(""), model.snapshotLines());
        assertEquals(revision + 1, model.revision());
        assertEquals(epoch + 1, model.contentEpoch());
    }

    @Test
    void failedLoadKeepsVersionsAndCacheIdentityIsStableAndUnique() {
        TextModel first = new TextModel(new SimpleLineBuffer());
        TextModel second = new TextModel(new SimpleLineBuffer());
        long identity = first.cacheIdentity();

        assertThrows(IOException.class, () -> first.load(tempDir.resolve("missing.txt")));

        assertEquals(0, first.revision());
        assertEquals(0, first.contentEpoch());
        assertEquals(identity, first.cacheIdentity());
        assertNotEquals(identity, second.cacheIdentity());
    }
}
