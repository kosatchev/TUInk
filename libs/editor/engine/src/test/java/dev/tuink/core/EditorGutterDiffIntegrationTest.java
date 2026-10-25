package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.diff.jgit.JgitLineDiff;
import dev.tuink.editor.diff.LineDiff;
import dev.tuink.editor.document.ImmutableText;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EditorGutterDiffIntegrationTest {
    @TempDir Path directory;

    @Test
    void headlessDefaultsDoNotScheduleOrComputeOptionalGutterWork() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model));
        engine.insertInput("draft");
        for (GutterDiffMode mode : GutterDiffMode.values()) {
            engine.setGutterDiffMode(mode);
            assertEquals(-1L, engine.nanosUntilGutterDiffRefresh(Long.MAX_VALUE));
            assertFalse(engine.refreshGutterDiffIfDue());
            assertEquals(GutterDiffMarker.NONE, engine.gutterDiffMarkerForLine(0));
        }
        assertTrue(engine.undo());
        assertTrue(engine.redo());
        assertEquals("draft", model.getLine(0));
        assertNull(JvmDocumentStorage.pathOf(engine.state().getCurrentResource()));
    }

    @ParameterizedTest
    @EnumSource(value = GutterDiffMode.class, names = {"MYERS", "HISTOGRAM", "STABLE"})
    void theInjectedProviderKeepsAlgorithmBudgetImmutableLinesAndMarkerProjection(GutterDiffMode mode) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("prefix", "old", "suffix"));
        LineDiff provider = new JgitLineDiff();
        List<LineDiff.Algorithm> algorithms = new ArrayList<>();
        LineDiff recording = (before, after, algorithm, maximumWork) -> {
            assertEquals(250_000L, maximumWork);
            assertTrue(before.stream().allMatch(ImmutableText.class::isInstance));
            assertTrue(after.stream().allMatch(ImmutableText.class::isInstance));
            algorithms.add(algorithm);
            return provider.compute(before, after, algorithm, maximumWork);
        };
        EditorEngine engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model),
            new EditorClipboard(), recording);
        engine.setGutterDiffMode(mode);
        engine.selectAll();
        engine.insertInput("prefix\nnew\nextra\nsuffix");

        var markers = engine.gutterDiffMarkers();

        assertEquals(GutterDiffFlag.NONE, markers.apply(0).flag());
        assertEquals(GutterDiffFlag.MODIFIED, markers.apply(1).flag());
        assertEquals(mode == GutterDiffMode.STABLE ? GutterDiffFlag.MODIFIED : GutterDiffFlag.ADDED,
            markers.apply(2).flag());
        assertEquals(GutterDiffFlag.NONE, markers.apply(3).flag());
        assertEquals(List.of(mode == GutterDiffMode.HISTOGRAM
            ? LineDiff.Algorithm.HISTOGRAM : LineDiff.Algorithm.MYERS), algorithms,
            "shared open/saved baselines should use one provider calculation");
    }

    @Test
    void largeRewriteShowsCurrentAndHistoricalMarkersAcrossSave() throws IOException {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(IntStream.range(0, 16_000).mapToObj(i -> "a" + i).toList());
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model),
            new EditorClipboard(), new JgitLineDiff());
        engine.selectAll();
        engine.insertInput(IntStream.range(0, 16_000)
            .mapToObj(i -> "b" + i).collect(java.util.stream.Collectors.joining("\n")));

        for (int line : List.of(0, 8_000, 15_999)) {
            assertEquals(new GutterDiffMarker(GutterDiffFlag.MODIFIED, false), engine.gutterDiffMarkerForLine(line));
        }
        engine.saveAs(JvmDocumentStorage.resource(directory.resolve("rewritten.txt")));
        for (int line : List.of(0, 8_000, 15_999)) {
            assertEquals(new GutterDiffMarker(GutterDiffFlag.MODIFIED, true), engine.gutterDiffMarkerForLine(line));
        }
    }
}
