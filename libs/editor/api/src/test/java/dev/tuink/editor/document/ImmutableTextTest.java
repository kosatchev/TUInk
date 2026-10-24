package dev.tuink.editor.document;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ImmutableTextTest {
    private static final DocumentId DOCUMENT = new DocumentId("shared-blocks");

    @Test
    void snapshotsShareEditedBlocksWithoutMaterializingOrValidatingTheWholeLineAgain() {
        ImmutableText original = ImmutableText.of("a".repeat(1_000_000));
        ImmutableText edited = original.replace(500_000, 500_001, "😀");
        ImmutableText.Work before = ImmutableText.work();

        DocumentSnapshot snapshot = DocumentSnapshot.fromImmutableLines(DOCUMENT, 1, List.of(edited));
        assertSame(edited, snapshot.lineText(0));
        assertTrue(snapshot.contains(new TextPosition(0, 1_000_001)));
        assertTrue(snapshot.isCodePointBoundary(new TextPosition(0, 500_000)));
        assertFalse(snapshot.isCodePointBoundary(new TextPosition(0, 500_001)));
        assertEquals('a', snapshot.lineText(0).charAt(800_000));
        ImmutableText next = edited.replace(0, 1, "z");
        assertEquals('a', snapshot.lineText(0).charAt(0));
        assertEquals('z', next.charAt(0));
        assertEquals(before.materializedCodeUnits(), ImmutableText.work().materializedCodeUnits());
    }

    @Test
    void utf16ValidationCombinesPairsAcrossBlockBoundariesAndTracksLaterSplits() {
        ImmutableText left = ImmutableText.of("a".repeat(2_047) + "\uD83D");
        ImmutableText right = ImmutableText.of("\uDE00" + "b".repeat(2_047));
        assertFalse(left.isWellFormedUtf16());
        assertFalse(right.isWellFormedUtf16());
        ImmutableText joined = left.replace(left.length(), left.length(), right);
        assertTrue(joined.isWellFormedUtf16());
        assertDoesNotThrow(() -> DocumentSnapshot.fromImmutableLines(DOCUMENT, 0, List.of(joined)));

        ImmutableText split = joined.replace(2_048, 2_049, "");
        assertFalse(split.isWellFormedUtf16());
        assertThrows(IllegalArgumentException.class,
            () -> DocumentSnapshot.fromImmutableLines(DOCUMENT, 1, List.of(split)));
        assertTrue(joined.isWellFormedUtf16());
    }

    @Test
    void importsCopyMutableTextAndSnapshotLineLists() {
        StringBuilder source = new StringBuilder("first");
        ImmutableText text = ImmutableText.of(source);
        List<ImmutableText> lines = new ArrayList<>(List.of(text));
        DocumentSnapshot snapshot = DocumentSnapshot.fromImmutableLines(DOCUMENT, 0, lines);
        source.setCharAt(0, 'X');
        lines.set(0, ImmutableText.of("other"));
        assertEquals("first", snapshot.line(0));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.lines().set(0, "mutated"));
    }

    @Test
    void cachedValidationMatchesFullValidationAfterPersistentEdits() {
        Random random = new Random(44);
        String expected = "abc".repeat(3_000);
        ImmutableText text = ImmutableText.of(expected);
        String[] inserts = {"", "x", "\n", "\r", "😀", "\uD83D", "\uDE00", "e\u0301"};
        for (int iteration = 0; iteration < 600; iteration++) {
            int start = random.nextInt(expected.length() + 1);
            int end = Math.min(expected.length(), start + random.nextInt(7));
            String insertion = inserts[random.nextInt(inserts.length)];
            expected = expected.substring(0, start) + insertion + expected.substring(end);
            text = text.replace(start, end, insertion);
            assertEquals(isWellFormed(expected), text.isWellFormedUtf16(), "edit " + iteration);
            assertEquals(expected.indexOf('\n') >= 0 || expected.indexOf('\r') >= 0,
                text.containsLineSeparator(), "edit " + iteration);
            assertEquals(expected, text.toString());
        }
    }

    private static boolean isWellFormed(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index == text.length() || !Character.isLowSurrogate(text.charAt(index))) return false;
            } else if (Character.isLowSurrogate(current)) return false;
        }
        return true;
    }
}
