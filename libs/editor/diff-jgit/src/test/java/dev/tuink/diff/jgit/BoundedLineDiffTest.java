package dev.tuink.diff.jgit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.diff.LineDiff;
import dev.tuink.editor.diff.LineEdit;
import dev.tuink.editor.document.ImmutableText;
import java.util.List;
import java.util.stream.IntStream;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.junit.jupiter.api.Test;

class BoundedLineDiffTest {
    @Test
    void sufficientBudgetKeepsSeparateEditsForBothAlgorithms() {
        for (var algorithm : algorithms()) {
            var edits = BoundedLineDiff.compute(
                algorithm,
                List.of("prefix", "a", "island", "b", "suffix"),
                List.of("prefix", "A", "island", "B", "suffix"),
                10_000L
            );
            assertEquals(List.of(new LineEdit(1, 2, 1, 2), new LineEdit(3, 4, 3, 4)), edits);
        }
    }

    @Test
    void exhaustedBudgetKeepsTheVerifiedPrefixAndSuffix() {
        for (var algorithm : algorithms()) {
            var edits = BoundedLineDiff.compute(
                algorithm,
                List.of("prefix", "a", "island", "b", "suffix"),
                List.of("prefix", "A", "island", "B", "new", "suffix"),
                1L
            );
            assertEquals(List.of(new LineEdit(1, 4, 1, 5)), edits);
            assertTrue(BoundedLineDiff.compute(
                algorithm, List.of("same", "text"), List.of("same", "text"), 1L
            ).isEmpty());
        }
    }

    @Test
    void budgetChargesComparedCharactersAndSharedLongLineHashes() {
        String longIsland = "x".repeat(10_000);
        for (var algorithm : algorithms()) {
            assertEquals(List.of(new LineEdit(1, 4, 1, 4)), BoundedLineDiff.compute(
                algorithm,
                List.of("prefix", "a", longIsland, "b", "suffix"),
                List.of("prefix", "A", longIsland, "B", "suffix"),
                1_000L
            ));
            assertEquals(List.of(new LineEdit(1, 4, 1, 4)), BoundedLineDiff.compute(
                algorithm,
                List.of("prefix", "a" + longIsland, "island", "b" + longIsland, "suffix"),
                List.of("prefix", "A" + longIsland, "island", "B" + longIsland, "suffix"),
                1_000L
            ));
        }
    }

    @Test
    void conservativeFallbackRetainsInsertAndDeleteCoordinates() {
        var algorithm = algorithms().get(0);
        assertEquals(List.of(new LineEdit(1, 1, 1, 3)), BoundedLineDiff.compute(
            algorithm, List.of("prefix", "suffix"), List.of("prefix", "a", "b", "suffix"), 1L
        ));
        assertEquals(List.of(new LineEdit(1, 3, 1, 1)), BoundedLineDiff.compute(
            algorithm, List.of("prefix", "a", "b", "suffix"), List.of("prefix", "suffix"), 1L
        ));
        assertThrows(IllegalArgumentException.class, () -> BoundedLineDiff.compute(
            algorithm, List.of("a"), List.of("b"), 0L
        ));
    }

    @Test
    void budgetAlsoBoundsEditGraphWorkOnUnequalCachedHashes() {
        List<String> before = IntStream.range(0, 600).mapToObj(i -> "a" + i).toList();
        List<String> after = IntStream.range(0, 600)
            .mapToObj(i -> i == 300 ? before.get(i) : "b" + i).toList();
        // Precompute hashes so this cannot accidentally rely on expensive String hashing.
        before.forEach(String::hashCode);
        after.forEach(String::hashCode);

        for (var algorithm : algorithms()) {
            assertEquals(List.of(new LineEdit(0, 600, 0, 600)),
                BoundedLineDiff.compute(algorithm, before, after, 250_000L));
        }
    }

    private static List<DiffAlgorithm> algorithms() {
        return List.of(
            DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS),
            DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
        );
    }

    @Test
    void boundaryTrimmingDoesNotScanAnUnboundedEqualSequence() {
        CharSequence unreadable = new CharSequence() {
            @Override public int length() { return 10_000_000; }
            @Override public char charAt(int index) { throw new AssertionError("Boundary exceeded its work budget"); }
            @Override public CharSequence subSequence(int start, int end) { throw new AssertionError(); }
        };
        for (var algorithm : algorithms()) {
            assertEquals(List.of(new LineEdit(0, 1, 0, 1)), BoundedLineDiff.compute(
                algorithm, List.of(unreadable), List.of("x".repeat(unreadable.length())), 1_000L));
        }
    }

    @Test
    void publicProviderPreservesBothAlgorithmsAndImmutableCoordinates() {
        LineDiff provider = new JgitLineDiff();
        for (LineDiff.Algorithm algorithm : LineDiff.Algorithm.values()) {
            List<LineEdit> edits = provider.compute(List.of("prefix", "a", "island", "b", "suffix"),
                List.of("prefix", "A", "island", "B", "suffix"), algorithm, 10_000L);
            assertEquals(List.of(new LineEdit(1, 2, 1, 2), new LineEdit(3, 4, 3, 4)), edits);
            assertThrows(UnsupportedOperationException.class, () -> edits.add(new LineEdit(0, 0, 0, 0)));
        }
    }

    @Test
    void immutableLinesRetainSharedBoundaryFastPathsWithoutMaterialization() {
        ImmutableText shared = ImmutableText.of("x".repeat(1_000_000));
        ImmutableText changed = shared.replace(500_000, 500_001, "Y");
        ImmutableText.Work before = ImmutableText.work();
        LineDiff provider = new JgitLineDiff();
        for (LineDiff.Algorithm algorithm : LineDiff.Algorithm.values()) {
            assertEquals(List.of(new LineEdit(1, 2, 1, 2)), provider.compute(
                List.of(shared, shared, shared), List.of(shared, changed, shared), algorithm, 1_000L));
        }
        assertEquals(before.materializedCodeUnits(), ImmutableText.work().materializedCodeUnits());
        assertEquals(before.copiedCodeUnits(), ImmutableText.work().copiedCodeUnits());
    }
}
