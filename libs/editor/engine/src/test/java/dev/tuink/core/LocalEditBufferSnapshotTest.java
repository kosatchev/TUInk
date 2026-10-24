package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LocalEditBufferSnapshotTest {
    @Test
    void capturesLongHistoryReplayWithoutMaterializingUntouchedLines() {
        SimpleLineBuffer source = new SimpleLineBuffer();
        source.setLine(0, "original");
        for (int line = 1; line < 20_000; line++) source.insertLine(line, "untouched");
        LocalEditBuffer staged = new LocalEditBuffer(source);
        for (int edit = 0; edit < 10_000; edit++) staged.setLine(0, "edit " + edit);
        LineText.Work before = LineText.work();

        List<LineText> snapshot = staged.snapshotTextLines();

        assertEquals(20_000, snapshot.size());
        assertEquals("edit 9999", snapshot.get(0).toString());
        assertSame(source.lineText(19_999), snapshot.get(19_999));
        assertEquals(before.materializedCodeUnits(), LineText.work().materializedCodeUnits());
        staged.setLine(0, "next");
        assertEquals("edit 9999", snapshot.get(0).toString());
        assertEquals("original", source.getLine(0));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
    }

    @Test
    void bulkReadMatchesCoordinateReplayWithLineInsertionRemovalAndReplacement() {
        SimpleLineBuffer source = new SimpleLineBuffer();
        List<String> expected = new ArrayList<>(List.of("start"));
        source.setLine(0, "start");
        LocalEditBuffer staged = new LocalEditBuffer(source);
        Random random = new Random(220);
        for (int edit = 0; edit < 400; edit++) {
            int line = random.nextInt(expected.size());
            if (edit % 3 == 0) {
                String text = "insert " + edit;
                staged.insertLine(line, text);
                expected.add(line, text);
            } else if (edit % 3 == 1 && expected.size() > 1) {
                staged.removeLines(line, line + 1);
                expected.remove(line);
            } else {
                String text = "replace " + edit;
                staged.setLine(line, text);
                expected.set(line, text);
            }
            assertEquals(expected, staged.snapshotTextLines().stream().map(LineText::toString).toList());
        }
        TextBuffer prepared = staged.preparePublication();
        staged.publishTo(prepared);
        assertEquals(expected, java.util.stream.IntStream.range(0, prepared.lineCount())
            .mapToObj(prepared::getLine).toList());
    }
}
