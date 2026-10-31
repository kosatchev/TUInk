package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuralIndentLookbackTest {
    @Test
    void reverseTraversalExcludesTheCurrentLineAndStopsAtBofOr256PreviousLines() {
        for (int before : new int[] {0, 1, 256, 257, 1024}) {
            List<Integer> requested = new ArrayList<>();
            CharSequence source = new StringBuilder();
            StructuralIndentLookback lookback = new StructuralIndentLookback(before, index -> {
                assertTrue(index >= Math.max(0, before - 256) && index < before);
                requested.add(index);
                return source;
            });
            int seen = 0;

            while (lookback.previous()) {
                assertEquals(before - 1 - seen, lookback.lineIndex());
                assertSame(source, lookback.text());
                seen++;
            }

            assertEquals(Math.min(before, 256), seen);
            assertEquals(seen, requested.size());
            assertFalse(lookback.previous());
            assertEquals(seen, requested.size());
        }
    }

    @Test
    void anExactCodeUnitBudgetStillAdmitsEmptyLinesButStopsBeforeTheNextNonemptyLine() {
        List<Integer> requested = new ArrayList<>();
        CharSequence fullBudget = new UnreadableText(16_384);
        StructuralIndentLookback lookback = new StructuralIndentLookback(5, index -> {
            requested.add(index);
            return switch (index) {
                case 4 -> fullBudget;
                case 3, 2 -> "";
                case 1 -> new UnreadableText(1);
                default -> throw new AssertionError("An older line must not be fetched after exhaustion");
            };
        });

        assertTrue(lookback.previous());
        assertEquals(4, lookback.lineIndex());
        assertSame(fullBudget, lookback.text());
        assertTrue(lookback.previous());
        assertEquals(3, lookback.lineIndex());
        assertTrue(lookback.previous());
        assertEquals(2, lookback.lineIndex());
        assertFalse(lookback.previous());
        assertFalse(lookback.previous());
        assertEquals(List.of(4, 3, 2, 1), requested);
    }

    @Test
    void oversizedLinesEndTheWindowBeforeAnyCharacterInspection() {
        for (int length : new int[] {16_385, Integer.MAX_VALUE}) {
            List<Integer> requested = new ArrayList<>();
            StructuralIndentLookback lookback = new StructuralIndentLookback(2, index -> {
                assertEquals(1, index, "over-budget lines must terminate the traversal");
                requested.add(index);
                return new UnreadableText(length);
            });

            assertFalse(lookback.previous());
            assertFalse(lookback.previous());
            assertEquals(List.of(1), requested);
        }
    }

    /** Length is available, but inspecting or flattening this source is forbidden. */
    private record UnreadableText(int length) implements CharSequence {
        @Override
        public char charAt(int index) {
            throw new AssertionError("The lookback must not inspect characters");
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            throw new AssertionError("The lookback must retain the original source");
        }

        @Override
        public String toString() {
            throw new AssertionError("The lookback must not flatten its source");
        }
    }
}
