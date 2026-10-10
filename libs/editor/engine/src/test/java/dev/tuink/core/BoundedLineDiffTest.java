package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedLineDiffTest {
    @TempDir
    Path tempDir;

    @Test
    void sufficientBudgetKeepsSeparateEditsForBothAlgorithms() {
        for (var algorithm : algorithms()) {
            var edits = BoundedLineDiff.compute(
                algorithm,
                List.of("prefix", "a", "island", "b", "suffix"),
                List.of("prefix", "A", "island", "B", "suffix"),
                10_000L
            );
            assertEquals(List.of(new Edit(1, 2, 1, 2), new Edit(3, 4, 3, 4)), edits);
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
            assertEquals(List.of(new Edit(1, 4, 1, 5)), edits);
            assertTrue(BoundedLineDiff.compute(
                algorithm, List.of("same", "text"), List.of("same", "text"), 1L
            ).isEmpty());
        }
    }

    @Test
    void budgetChargesComparedCharactersAndSharedLongLineHashes() {
        String longIsland = "x".repeat(10_000);
        for (var algorithm : algorithms()) {
            assertEquals(List.of(new Edit(1, 4, 1, 4)), BoundedLineDiff.compute(
                algorithm,
                List.of("prefix", "a", longIsland, "b", "suffix"),
                List.of("prefix", "A", longIsland, "B", "suffix"),
                1_000L
            ));
            assertEquals(List.of(new Edit(1, 4, 1, 4)), BoundedLineDiff.compute(
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
        assertEquals(List.of(new Edit(1, 1, 1, 3)), BoundedLineDiff.compute(
            algorithm, List.of("prefix", "suffix"), List.of("prefix", "a", "b", "suffix"), 1L
        ));
        assertEquals(List.of(new Edit(1, 3, 1, 1)), BoundedLineDiff.compute(
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
            assertEquals(List.of(new Edit(0, 600, 0, 600)),
                BoundedLineDiff.compute(algorithm, before, after, 250_000L));
        }
    }

    @Test
    void largeRewriteShowsCurrentAndHistoricalMarkersAcrossSave() throws IOException {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(IntStream.range(0, 16_000).mapToObj(i -> "a" + i).toList());
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.selectAll();
        engine.insertInput(IntStream.range(0, 16_000)
            .mapToObj(i -> "b" + i).collect(java.util.stream.Collectors.joining("\n")));

        for (int line : List.of(0, 8_000, 15_999)) {
            assertEquals(new GutterDiffMarker(GutterDiffFlag.MODIFIED, false),
                engine.gutterDiffMarkerForLine(line));
        }
        engine.saveAs(tempDir.resolve("rewritten.txt"));
        for (int line : List.of(0, 8_000, 15_999)) {
            assertEquals(new GutterDiffMarker(GutterDiffFlag.MODIFIED, true),
                engine.gutterDiffMarkerForLine(line));
        }
    }

    private static List<DiffAlgorithm> algorithms() {
        return List.of(
            DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS),
            DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
        );
    }
}
