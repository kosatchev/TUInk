package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LineTextTest {
    @Test
    void successiveLocalEditsCopyBlocksAndKeepEarlierTextImmutable() {
        String original = "a".repeat(2_000_000);
        LineText initial = LineText.of(original);
        LineText text = initial;
        for (int index = 0; index < 1_000; index++) {
            int column = original.length() / 2 + index;
            LineText previous = text;
            text = text.replace(column, column + 1, "b");
            assertNotEquals(previous.identity(), text.identity());
            assertEquals(previous.identity(), text.edit().sourceIdentity());
            assertTrue(text.edit().copiedCodeUnits() <= 12L * LineText.BLOCK_SIZE,
                "a local replacement must copy bounded blocks, not the line");
            assertTrue(text.edit().allocatedNodes() <= 32 * previous.treeHeight(),
                "path copying must depend on tree height");
            assertEquals('a', previous.charAt(column));
            assertEquals('b', text.charAt(column));
        }
        assertEquals(original, initial.toString());
        assertEquals("a".repeat(20) + "b".repeat(1_000) + "a".repeat(20),
            text.sliceString(999_980, 1_001_020));
        int logarithm = 32 - Integer.numberOfLeadingZeros(text.retainedNodes());
        assertTrue(text.treeHeight() <= 2 * logarithm);
    }

    @Test
    void randomSplicesAndSlicesAgreeWithStringContentAndHash() {
        Random random = new Random(0x7a112L);
        String expected = "abcdefgh".repeat(700);
        LineText actual = LineText.of(expected);
        for (int iteration = 0; iteration < 3_000; iteration++) {
            int start = random.nextInt(expected.length() + 1);
            int end = Math.min(expected.length(), start + random.nextInt(20));
            String inserted = List.of("", "x", "🙂", "e\u0301", "abcde").get(random.nextInt(5));
            expected = expected.substring(0, start) + inserted + expected.substring(end);
            actual = actual.replace(start, end, inserted);
            assertEquals(expected.length(), actual.length());
            assertEquals(expected.hashCode(), actual.hashCode());
            assertEquals(expected, actual.toString());
            int left = random.nextInt(expected.length() + 1);
            int right = left + random.nextInt(expected.length() - left + 1);
            assertEquals(expected.substring(left, right), actual.sliceString(left, right));
            assertEquals(expected.substring(left, right), actual.subSequence(left, right).toString());
        }
    }

    @Test
    void concatenationAndSplitsKeepCodePointAccessAcrossBlockEdges() {
        String original = "a".repeat(LineText.BLOCK_SIZE - 1) + "🙂e\u0301" + "z".repeat(9_000);
        LineText text = LineText.of(original);
        assertEquals(original.codePointAt(LineText.BLOCK_SIZE - 1), text.codePointAt(LineText.BLOCK_SIZE - 1));
        assertEquals(original.codePointBefore(LineText.BLOCK_SIZE + 1), text.codePointBefore(LineText.BLOCK_SIZE + 1));
        for (int position : List.of(0, 1, 2_047, 2_048, 2_050, original.length())) {
            LineText left = text.subSequence(0, position);
            LineText right = text.subSequence(position, text.length());
            assertEquals(original, left.replace(left.length(), left.length(), right).toString());
        }
        assertSame(text, text.subSequence(0, text.length()));
        assertSame(text, text.replace(4, 4, ""));
    }

    @Test
    void immutableModelTextSnapshotsSurviveSubsequentEditsWithoutMaterialization() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("a".repeat(1_000_000), "second"));
        List<LineText> before = model.snapshotTextLines();
        LineText.Work work = LineText.work();

        model.insertChar(new Position(0, 500_000), 'b');
        List<LineText> after = model.snapshotTextLines();

        assertEquals(work.materializedCodeUnits(), LineText.work().materializedCodeUnits());
        assertSame(before.get(1), after.get(1));
        assertEquals(1_000_000, before.getFirst().length());
        assertEquals('a', before.getFirst().charAt(500_000));
        assertEquals('b', after.getFirst().charAt(500_000));
        assertThrows(UnsupportedOperationException.class, () -> before.add(LineText.of("third")));
    }

    @Test
    void editorTypingAndUndoAvoidWholeLineMaterialization() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("a".repeat(1_000_000)));
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.moveCursorTo(new Position(0, 500_000));
        LineText.Work before = LineText.work();

        for (int index = 0; index < 100; index++) engine.insertChar('b');
        engine.backspace();
        assertTrue(engine.undo());

        LineText.Work after = LineText.work();
        assertTrue(after.materializedCodeUnits() - before.materializedCodeUnits() < 1_024,
            "only small deleted ranges may become strings for undo");
        assertTrue(after.copiedCodeUnits() - before.copiedCodeUnits() < 1_024L * LineText.BLOCK_SIZE,
            "typing must copy boundary blocks rather than the million-character line");
        assertEquals(1_000_100, model.lineLength(0));
        assertEquals("b".repeat(100), model.lineRange(0, 500_000, 500_100));
    }

    @Test
    void repeatedImmutableShortInputsShareStorageWithoutChangingStringIdentity() {
        String first = new String("repeated input");
        String second = new String("repeated input");
        LineText shared = LineText.of(first);
        assertSame(shared, LineText.of(first));
        assertSame(first, shared.toString());
        assertSame(second, LineText.of(second).toString());
    }
}
