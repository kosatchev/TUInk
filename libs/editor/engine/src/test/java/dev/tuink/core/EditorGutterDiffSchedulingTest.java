package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.diff.jgit.JgitLineDiff;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EditorGutterDiffSchedulingTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"3, 50000000", "20000, 150000000"})
    void rapidEditsKeepADeadlineUntilTheirMarkersAreRefreshed(int lines, long interval) {
        Fixture fixture = new Fixture(lines);
        assertEquals(-1, fixture.deadline());
        fixture.edit(0, 'X');
        assertEquals(0, fixture.deadline());
        assertTrue(fixture.engine.refreshGutterDiffIfDue());
        assertEquals(GutterDiffFlag.MODIFIED, fixture.engine.gutterDiffMarkerForLine(0).flag());

        fixture.edit(1, 'Y');
        long revision = fixture.model.revision();
        Position cursor = fixture.engine.state().getCursor();
        assertEquals(interval, fixture.deadline());
        assertEquals(GutterDiffFlag.NONE, fixture.engine.gutterDiffMarkerForLine(1).flag());
        fixture.now.addAndGet(interval - 1);
        assertEquals(1, fixture.deadline());
        assertFalse(fixture.engine.refreshGutterDiffIfDue());
        fixture.now.incrementAndGet();

        assertEquals(0, fixture.deadline());
        assertTrue(fixture.engine.refreshGutterDiffIfDue());
        assertEquals(GutterDiffFlag.MODIFIED, fixture.engine.gutterDiffMarkerForLine(1).flag());
        assertEquals(revision, fixture.model.revision());
        assertEquals(cursor, fixture.engine.state().getCursor());
        assertEquals(-1, fixture.deadline());
        assertFalse(fixture.engine.refreshGutterDiffIfDue(), "completed work must not keep scheduling frames");
    }

    @Test
    void frameReadsKeepOneDiffWhenTheDeadlinePassesBetweenLines() {
        Fixture fixture = new Fixture(3);
        fixture.edit(0, 'X');
        assertTrue(fixture.engine.refreshGutterDiffIfDue());
        fixture.edit(1, 'Y');
        fixture.edit(2, 'Z');
        var markers = fixture.engine.gutterDiffMarkers();
        assertEquals(GutterDiffFlag.NONE, markers.apply(1).flag());

        fixture.now.addAndGet(50_000_000L);

        assertEquals(GutterDiffFlag.NONE, markers.apply(2).flag());
        assertEquals(0, fixture.deadline(), "reading a frame must leave due work scheduled");
        assertTrue(fixture.engine.refreshGutterDiffIfDue());
        var nextFrame = fixture.engine.gutterDiffMarkers();
        assertEquals(GutterDiffFlag.MODIFIED, nextFrame.apply(1).flag());
        assertEquals(GutterDiffFlag.MODIFIED, nextFrame.apply(2).flag());
        assertEquals(-1, fixture.deadline());
    }

    @Test
    void undoBeforeTheDeadlineRefreshesTheCurrentDocumentInsteadOfTheSupersededEdit() {
        Fixture fixture = new Fixture(3);
        fixture.edit(0, 'X');
        assertTrue(fixture.engine.refreshGutterDiffIfDue());
        fixture.edit(1, 'Y');
        assertTrue(fixture.engine.undo());
        fixture.now.addAndGet(50_000_000L);

        assertTrue(fixture.engine.refreshGutterDiffIfDue());

        assertEquals(GutterDiffFlag.MODIFIED, fixture.engine.gutterDiffMarkerForLine(0).flag());
        assertEquals(GutterDiffFlag.NONE, fixture.engine.gutterDiffMarkerForLine(1).flag());
        assertEquals(List.of("Xline 0", "line 1", "line 2"), fixture.model.snapshotLines());
        assertEquals(-1, fixture.deadline());
    }

    @Test
    void disabledDiffHasNoDeadlineAndEnablingItSchedulesTheOutstandingChanges() {
        Fixture fixture = new Fixture(3);
        fixture.edit(0, 'X');
        assertTrue(fixture.engine.refreshGutterDiffIfDue());
        fixture.engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        fixture.edit(1, 'Y');

        assertEquals(-1, fixture.deadline());
        assertFalse(fixture.engine.refreshGutterDiffIfDue());
        assertEquals(GutterDiffFlag.NONE, fixture.engine.gutterDiffMarkerForLine(0).flag());

        fixture.engine.setGutterDiffMode(GutterDiffMode.MYERS);
        assertEquals(50_000_000L, fixture.deadline());
        fixture.now.addAndGet(50_000_000L);
        assertTrue(fixture.engine.refreshGutterDiffIfDue());
        assertEquals(GutterDiffFlag.MODIFIED, fixture.engine.gutterDiffMarkerForLine(1).flag());
        assertEquals(-1, fixture.deadline());
    }

    @Test
    void aSaveInAnotherViewSchedulesMarkersEvenWithoutATextRevisionChange() throws Exception {
        Fixture fixture = new Fixture(3);
        fixture.edit(0, 'X');
        assertTrue(fixture.engine.refreshGutterDiffIfDue());
        long revision = fixture.model.revision();
        TabSession other = fixture.engine.document().attach(LogicalEditorTextLayout::new, new EditorClipboard());
        other.engine().saveAs(directory.resolve("saved.txt"));

        assertEquals(revision, fixture.model.revision());
        assertEquals(50_000_000L, fixture.deadline());
        fixture.now.addAndGet(50_000_000L);
        assertTrue(fixture.engine.refreshGutterDiffIfDue());
        assertEquals(new GutterDiffMarker(GutterDiffFlag.MODIFIED, true),
            fixture.engine.gutterDiffMarkerForLine(0));
        assertEquals(-1, fixture.deadline());
    }

    @Test
    void theWakeupHonorsTheAdaptiveRebuildInterval() {
        Fixture fixture = new Fixture(3);
        fixture.snapshotCost.set(200_000_000L);
        fixture.edit(0, 'X');
        assertTrue(fixture.engine.refreshGutterDiffIfDue());
        fixture.snapshotCost.set(0);
        fixture.edit(1, 'Y');

        assertEquals(350_000_000L, fixture.deadline());
        fixture.now.addAndGet(50_000_000L);
        assertFalse(fixture.engine.refreshGutterDiffIfDue());
        assertEquals(300_000_000L, fixture.deadline());
        fixture.now.addAndGet(300_000_000L);
        assertTrue(fixture.engine.refreshGutterDiffIfDue());
        assertEquals(GutterDiffFlag.MODIFIED, fixture.engine.gutterDiffMarkerForLine(1).flag());
        assertEquals(-1, fixture.deadline());
    }

    private static final class Fixture {
        final AtomicLong now = new AtomicLong(1_000_000_000L);
        final AtomicLong snapshotCost = new AtomicLong();
        final TextModel model = new TextModel(new SimpleLineBuffer()) {
            @Override
            public List<LineText> snapshotTextLines() {
                now.addAndGet(snapshotCost.get());
                return super.snapshotTextLines();
            }
        };
        final EditorEngine engine;

        Fixture(int lines) {
            model.replaceLines(IntStream.range(0, lines).mapToObj(line -> "line " + line).toList());
            engine = new EditorEngine(model, new EditorState(), new LogicalEditorTextLayout(model),
                new EditorClipboard(), new JgitLineDiff(), now::get);
        }

        void edit(int line, char character) {
            engine.moveCursorTo(new Position(line, 0));
            engine.insertChar(character);
        }

        long deadline() {
            return engine.nanosUntilGutterDiffRefresh(now.get());
        }
    }
}
