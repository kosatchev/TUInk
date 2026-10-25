package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.storage.jvm.FileBaseline;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextModelReplacementDeltaTest {
    @TempDir
    Path directory;

    @Test
    void multilineUnicodeReplacementAndItsInverseKeepExactTextAndEols() {
        List<String> lines = List.of("before🙂", "middle", "tail", "far");
        DocumentFileMetadata original = metadata(List.of("\r", "\n", "\r\n"));
        TextModel model = model(lines, original);

        TextModel.ReplacementDelta delta = model.replaceRange(new Position(0, 6), new Position(2, 2), "β\r\n漢\r🙂");

        assertEquals(List.of("beforeβ", "漢", "🙂il", "far"), model.snapshotLines());
        assertEquals(EditOp.replace(new Position(0, 6), "🙂\nmiddle\nta", "β\n漢\n🙂"), delta.operation());
        assertEquals(new Position(2, 2), delta.endAfter());
        assertEquals(List.of("\r\n", "\r\n", "\r\n"), model.fileMetadata().lineSeparators());
        DocumentFileMetadata edited = model.fileMetadata();
        assertEquals(original.charset(), edited.charset());
        assertEquals(original.bomKind(), edited.bomKind());

        model.replayReplacement(delta.inverse());
        assertEquals(lines, model.snapshotLines());
        assertEquals(original, model.fileMetadata());
        model.replayReplacement(delta);
        assertEquals(List.of("beforeβ", "漢", "🙂il", "far"), model.snapshotLines());
        assertEquals(edited, model.fileMetadata());
        assertEquals(delta, delta.inverse().inverse());
    }

    @Test
    void replacementRetainsExistingClampingAndReversedRangeRules() {
        TextModel model = model(List.of("one", "two"), metadata(List.of("\r")));
        TextModel.ReplacementDelta reversed = model.replaceRange(new Position(1, 2), new Position(0, 1), "X");
        assertEquals(List.of("oXo"), model.snapshotLines());
        assertEquals(new Position(0, 1), reversed.operation().start());
        model.replayReplacement(reversed.inverse());
        assertEquals(List.of("one", "two"), model.snapshotLines());

        TextModel.ReplacementDelta clamped = model.replaceRange(new Position(0, 0), new Position(99, 99), "🦊");
        assertEquals(List.of("🦊"), model.snapshotLines());
        model.replayReplacement(clamped.inverse());
        assertEquals(List.of("one", "two"), model.snapshotLines());
    }

    @Test
    void emptyReplacementAndReplayLeaveVersionsAndMetadataUntouched() {
        TextModel model = model(List.of("a", "b", "c"), metadata(List.of("\r", "\n")));
        DocumentFileMetadata original = model.fileMetadata();
        long revision = model.revision();
        long epoch = model.contentEpoch();

        TextModel.ReplacementDelta empty = model.replaceRange(new Position(1, 0), new Position(1, 0), "");
        assertTrue(empty.isEmpty());
        model.replayReplacement(empty);
        model.replayReplacement(empty.inverse());

        assertEquals(revision, model.revision());
        assertEquals(epoch, model.contentEpoch());
        assertSame(original, model.fileMetadata());
        assertEquals(List.of(), model.lineChangesSince(revision));
    }

    @Test
    void identicalNonemptyTextCanStillReplaceItsLineSeparators() {
        TextModel model = model(List.of("a", "b", "c"), metadata(List.of("\r", "\n")));
        DocumentFileMetadata original = model.fileMetadata();

        TextModel.ReplacementDelta delta = model.replaceRange(new Position(0, 0), new Position(1, 1), "a\nb");

        assertFalse(delta.isEmpty());
        assertEquals(List.of("a", "b", "c"), model.snapshotLines());
        assertEquals(List.of("\r\n", "\n"), model.fileMetadata().lineSeparators());
        model.replayReplacement(delta.inverse());
        assertEquals(original, model.fileMetadata());
    }

    @Test
    void canonicalizationWaitsForTheCompletedReplacement() {
        for (String replacement : List.of("B", "")) {
            TextModel model = model(List.of("a", "b", "c"), metadata(List.of("\r", "\n")));
            DocumentFileMetadata original = model.fileMetadata();

            TextModel.ReplacementDelta delta = model.replaceRange(new Position(1, 0), new Position(1, 1), replacement);

            assertEquals(List.of("a", replacement, "c"), model.snapshotLines());
            assertEquals(replacement.isEmpty() ? List.of("\r", "\r\n") : List.of("\r", "\n"),
                model.fileMetadata().lineSeparators());
            assertEquals(List.of("\r", "\n"), delta.deletedSeparators());
            assertEquals(model.fileMetadata().lineSeparators(), delta.insertedSeparators());
            model.replayReplacement(delta.inverse());
            assertEquals(List.of("a", "b", "c"), model.snapshotLines());
            assertEquals(original, model.fileMetadata());
        }
    }

    @Test
    void adjacentEolsAreRestoredAfterAMultilineJoin() {
        List<String> lines = List.of("a", "b", "c", "d", "e");
        DocumentFileMetadata original = metadata(List.of("\r", "\n", "\r", "\n"));
        TextModel model = model(lines, original);

        TextModel.ReplacementDelta delta = model.replaceRange(new Position(1, 0), new Position(3, 1), "");

        assertEquals(List.of("a", "", "e"), model.snapshotLines());
        assertEquals(List.of("\r", "\r\n"), model.fileMetadata().lineSeparators());
        model.replayReplacement(delta.inverse());
        assertEquals(lines, model.snapshotLines());
        assertEquals(original, model.fileMetadata());
    }

    @Test
    void invalidTextAndSplitSurrogatesAreRejectedBeforeRemovingAnything() {
        for (String invalid : List.of("\uD800", "\uDC00", "valid\r\n\uD800")) {
            TextModel model = model(List.of("🙂abc", "tail"), metadata(List.of("\r")));
            assertRejectedWithoutMutation(model,
                () -> model.replaceRange(new Position(0, 0), new Position(1, 4), invalid));
        }
        for (boolean invalidStart : List.of(false, true)) {
            TextModel model = model(List.of("🙂abc", "tail"), metadata(List.of("\r")));
            assertRejectedWithoutMutation(model, () -> model.replaceRange(
                new Position(0, invalidStart ? 1 : 0), new Position(0, invalidStart ? 2 : 1), "X"
            ));
        }
    }

    @Test
    void malformedOrStaleReplayIsRejectedBeforeTheFirstMutation() {
        TextModel model = model(List.of("abc", "tail"), metadata(List.of("\r")));
        for (TextModel.ReplacementDelta invalid : List.of(
            delta("x", "X", List.of("\r"), List.of("\r")),
            delta("a", "\uD800", List.of("\r"), List.of("\r")),
            delta("a", "X\rY", List.of("\r"), List.of("\r")),
            delta("a", "X", List.of("\n"), List.of("\r")),
            delta("a", "X", List.of("\r"), List.of()),
            delta("a", "X", List.of("\r"), List.of("\r", "\n")),
            new TextModel.ReplacementDelta(EditOp.replace(new Position(3, 0), "", "X"), 0, List.of(), List.of())
        )) {
            assertRejectedWithoutMutation(model, () -> model.replayReplacement(invalid));
        }
    }

    @Test
    void deltaOwnsItsSeparatorSlicesDefensively() {
        List<String> deleted = new ArrayList<>(List.of("\r"));
        List<String> inserted = new ArrayList<>(List.of("\n"));
        TextModel.ReplacementDelta delta = delta("a", "X", deleted, inserted);

        deleted.set(0, "\r\n");
        inserted.clear();

        assertEquals(List.of("\r"), delta.deletedSeparators());
        assertEquals(List.of("\n"), delta.insertedSeparators());
        assertThrows(UnsupportedOperationException.class, () -> delta.deletedSeparators().add("\n"));
        assertThrows(UnsupportedOperationException.class, () -> delta.inverse().deletedSeparators().clear());
        assertThrows(IllegalArgumentException.class, () -> delta("a", "X", List.of("bad"), List.of("\n")));
    }

    @Test
    void singleLineDeltasAndReplayKeepUnchangedDocumentMetadataShared() {
        List<String> lines = java.util.stream.IntStream.range(0, 10_000).mapToObj(line -> "line " + line).toList();
        TextModel model = model(lines, DocumentFileMetadata.newDocument(lines.size()));
        DocumentFileMetadata original = model.fileMetadata();
        String suffix = model.getLine(9_999);

        TextModel.ReplacementDelta delta = model.replaceRange(new Position(5_000, 0), new Position(5_000, 0), "X");

        assertEquals(4_999, delta.firstSeparator());
        assertEquals(2, delta.deletedSeparators().size());
        assertEquals(2, delta.insertedSeparators().size());
        assertSame(original, model.fileMetadata());
        model.replayReplacement(delta.inverse());
        assertSame(original, model.fileMetadata());
        model.replayReplacement(delta);
        assertSame(original, model.fileMetadata());
        assertSame(suffix, model.getLine(9_999));
    }

    @Test
    void canonicalizedReplacementRoundTripsUtf16BomAndLeavesTheDiskBaselineToSave() throws Exception {
        TextModel model = model(List.of("a", "b", "c"), metadata(List.of("\r", "\n")));
        Path file = directory.resolve("utf16.txt");
        model.save(JvmDocumentStorage.resource(file));
        FileBaseline baseline = JvmDocumentStorage.baselineOf(model.fileBaseline());

        TextModel.ReplacementDelta delta = model.replaceRange(new Position(1, 0), new Position(1, 1), "");
        assertSame(baseline, JvmDocumentStorage.baselineOf(model.fileBaseline()));
        model.save(JvmDocumentStorage.resource(file));
        byte[] encoded = Files.readAllBytes(file);
        assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0xfe}, java.util.Arrays.copyOf(encoded, 2));
        TextModel reopened = new TextModel(new SimpleLineBuffer());
        reopened.load(JvmDocumentStorage.resource(file));
        assertEquals(model.snapshotLines(), reopened.snapshotLines());
        assertEquals(model.fileMetadata().charset(), reopened.fileMetadata().charset());
        assertEquals(model.fileMetadata().bomKind(), reopened.fileMetadata().bomKind());
        assertEquals(model.fileMetadata().lineSeparators(), reopened.fileMetadata().lineSeparators());

        FileBaseline saved = JvmDocumentStorage.baselineOf(model.fileBaseline());
        model.replayReplacement(delta.inverse());
        assertSame(saved, JvmDocumentStorage.baselineOf(model.fileBaseline()));
        assertEquals(List.of("a", "b", "c"), model.snapshotLines());
    }

    private static TextModel.ReplacementDelta delta(
        String deleted, String inserted, List<String> deletedSeparators, List<String> insertedSeparators
    ) {
        return new TextModel.ReplacementDelta(EditOp.replace(new Position(0, 0), deleted, inserted), 0,
            deletedSeparators, insertedSeparators);
    }

    private static void assertRejectedWithoutMutation(TextModel model, Runnable edit) {
        List<String> lines = model.snapshotLines();
        DocumentFileMetadata metadata = model.fileMetadata();
        FileBaseline baseline = JvmDocumentStorage.baselineOf(model.fileBaseline());
        long revision = model.revision();
        long epoch = model.contentEpoch();

        assertThrows(IllegalArgumentException.class, edit::run);

        assertEquals(lines, model.snapshotLines());
        assertSame(metadata, model.fileMetadata());
        assertSame(baseline, JvmDocumentStorage.baselineOf(model.fileBaseline()));
        assertEquals(revision, model.revision());
        assertEquals(epoch, model.contentEpoch());
        assertEquals(List.of(), model.lineChangesSince(revision));
    }

    private static DocumentFileMetadata metadata(List<String> separators) {
        return new DocumentFileMetadata(StandardCharsets.UTF_16LE, BomKind.UTF_16_LE, "\r\n", separators);
    }

    private static TextModel model(List<String> lines, DocumentFileMetadata metadata) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines, metadata);
        return model;
    }
}
