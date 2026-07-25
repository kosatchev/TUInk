package dev.tuink.tui.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.tui.geometry.Point;
import dev.tuink.tui.geometry.Rect;
import dev.tuink.tui.render.CellBuffer;
import dev.tuink.tui.screen.Frame;
import dev.tuink.tui.screen.ScreenCursor;
import dev.tuink.tui.screen.ScrollHint;
import dev.tuink.tui.style.CellStyle;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class ScreenPresenterTest {
    private static final String SHOW_CURSOR = "\u001B[?25h";

    @Test
    void firstFrameIsAFullRedrawAndAnIdenticalFrameWritesNothing() {
        RecordingPrintWriter writer = new RecordingPrintWriter();
        ScreenPresenter presenter = new ScreenPresenter(writer, true);
        Frame frame = frame(buffer("abc", 1, 4), 0, 0, true);

        ScreenPresenter.PresentResult first = presenter.present(frame);

        assertTrue(first.updated());
        assertTrue(first.fullRedraw());
        assertEquals(writer.output().length(), first.emittedCharacters());
        assertEquals(0L, first.patchNanos());
        assertTrue(writer.output().contains("abc"));
        int flushesAfterFirstFrame = writer.flushCount();
        writer.clearOutput();

        ScreenPresenter.PresentResult unchanged = presenter.present(frame);

        assertFalse(unchanged.updated());
        assertFalse(unchanged.fullRedraw());
        assertEquals(0, unchanged.emittedCharacters());
        assertEquals(0L, unchanged.patchNanos());
        assertEquals("", writer.output());
        assertEquals(flushesAfterFirstFrame, writer.flushCount());
    }

    @Test
    void cursorOnlyUpdateConvertsZeroBasedCoordinatesToAnsiCoordinates() {
        RecordingPrintWriter writer = new RecordingPrintWriter();
        ScreenPresenter presenter = new ScreenPresenter(writer, false);
        CellBuffer cells = buffer("abc", 2, 4);
        presenter.present(frame(cells, 0, 0, false));
        writer.clearOutput();

        ScreenPresenter.PresentResult result = presenter.present(frame(cells, 2, 1, true));

        assertTrue(result.updated());
        assertFalse(result.fullRedraw());
        assertEquals(SHOW_CURSOR + "\u001B[2;3H", writer.output());
        assertEquals(writer.output().length(), result.emittedCharacters());
        assertFalse(writer.output().contains("abc"));
    }

    @Test
    void cellPatchRestoresAnUnchangedVisibleCursorPosition() {
        RecordingPrintWriter writer = new RecordingPrintWriter();
        ScreenPresenter presenter = new ScreenPresenter(writer, false);
        presenter.present(frame(buffer("abcde", 1, 5), 2, 0, true));
        writer.clearOutput();

        ScreenPresenter.PresentResult result = presenter.present(
            frame(buffer("abXde", 1, 5), 2, 0, true)
        );

        assertTrue(result.updated());
        assertFalse(result.fullRedraw());
        assertTrue(writer.output().contains("X"));
        assertTrue(
            writer.output().endsWith("\u001B[1;3H"),
            "cell output must not leave the terminal cursor at the patched cell"
        );
    }

    @Test
    void invalidateAndDimensionChangesForceFullRedraws() {
        RecordingPrintWriter writer = new RecordingPrintWriter();
        ScreenPresenter presenter = new ScreenPresenter(writer, true);
        Frame original = frame(buffer("old", 1, 4), 0, 0, false);
        presenter.present(original);
        writer.clearOutput();

        presenter.invalidate();
        ScreenPresenter.PresentResult invalidated = presenter.present(original);

        assertTrue(invalidated.updated());
        assertTrue(invalidated.fullRedraw());
        assertTrue(writer.output().contains("old"));
        writer.clearOutput();

        Frame resized = frame(buffer("new", 2, 5), 0, 0, false);
        ScreenPresenter.PresentResult dimensionChanged = presenter.present(resized, true);

        assertTrue(dimensionChanged.updated());
        assertTrue(dimensionChanged.fullRedraw());
        assertTrue(dimensionChanged.patchNanos() >= 0L);
        assertTrue(writer.output().contains("new"));
    }

    @Test
    void presenterInstancesDoNotShareFrameOrCursorState() {
        RecordingPrintWriter firstWriter = new RecordingPrintWriter();
        RecordingPrintWriter secondWriter = new RecordingPrintWriter();
        ScreenPresenter first = new ScreenPresenter(firstWriter, true);
        ScreenPresenter second = new ScreenPresenter(secondWriter, false);
        Frame frame = frame(buffer("shared", 1, 7), 3, 0, true);

        ScreenPresenter.PresentResult firstResult = first.present(frame);
        ScreenPresenter.PresentResult secondResult = second.present(frame);

        assertTrue(firstResult.fullRedraw());
        assertTrue(secondResult.fullRedraw());
        assertTrue(firstWriter.output().contains("shared"));
        assertTrue(secondWriter.output().contains("shared"));

        firstWriter.clearOutput();
        secondWriter.clearOutput();
        assertFalse(first.present(frame).updated());
        assertFalse(second.present(frame).updated());
        assertEquals("", firstWriter.output());
        assertEquals("", secondWriter.output());
    }

    @Test
    void wideGraphemesAreEmittedOnceAndControlPayloadsStaySanitized() {
        RecordingPrintWriter writer = new RecordingPrintWriter();
        ScreenPresenter presenter = new ScreenPresenter(writer, false);
        CellBuffer cells = new CellBuffer(1, 8);
        cells.set(0, 0, "中", CellStyle.DEFAULT);
        cells.set(0, 2, "\u001B]52;c;secret\u0007", CellStyle.DEFAULT);

        ScreenPresenter.PresentResult result = presenter.present(frame(cells, 0, 0, false));
        String output = writer.output();

        assertTrue(result.fullRedraw());
        assertEquals(1, occurrences(output, "中"));
        assertFalse(output.contains("]52;c;secret"));
        assertFalse(output.contains("\u0007"));
        assertTrue(output.contains("�"));
    }

    @Test
    void horizontalScrollHintFallsBackToSafeRowDrawingForWideCells() {
        RecordingPrintWriter writer = new RecordingPrintWriter();
        ScreenPresenter presenter = new ScreenPresenter(writer, false);
        CellBuffer previous = new CellBuffer(1, 7);
        previous.set(0, 0, "A", CellStyle.DEFAULT);
        previous.set(0, 1, "中", CellStyle.DEFAULT);
        putAscii(previous, 0, 3, "BC");
        presenter.present(frame(previous, 0, 0, false));
        writer.clearOutput();

        CellBuffer current = new CellBuffer(1, 7);
        current.set(0, 0, "中", CellStyle.DEFAULT);
        putAscii(current, 0, 2, "BCDE");
        ScrollHint hint = new ScrollHint.Horizontal(new Rect(0, 0, 7, 1), 1);
        Frame shifted = new Frame(current, new ScreenCursor(new Point(0, 0), false), hint);

        ScreenPresenter.PresentResult result = presenter.present(shifted);
        String output = writer.output();

        assertTrue(result.updated());
        assertFalse(result.fullRedraw());
        assertFalse(output.contains("\u001B[1P"), "must not delete through a wide cell");
        assertFalse(output.contains("\u001B[1@"), "must not insert through a wide cell");
        assertEquals(1, occurrences(output, "中"));
        assertTrue(output.contains("BCDE"));
    }

    @Test
    void horizontalScrollHintWithoutMatchingOverlapFallsBackToOrdinaryDiff() {
        RecordingPrintWriter writer = new RecordingPrintWriter();
        ScreenPresenter presenter = new ScreenPresenter(writer, false);
        presenter.present(frame(buffer("abcde", 1, 5), 0, 0, false));
        writer.clearOutput();

        Frame unrelated = new Frame(
            buffer("xxxxx", 1, 5),
            ScreenCursor.hidden(),
            new ScrollHint.Horizontal(new Rect(0, 0, 5, 1), 1)
        );
        ScreenPresenter.PresentResult result = presenter.present(unrelated);
        String output = writer.output();

        assertTrue(result.updated());
        assertFalse(result.fullRedraw());
        assertFalse(output.contains("\u001B[1P"), "unverified overlap must not use DCH");
        assertFalse(output.contains("\u001B[1@"), "unverified overlap must not use ICH");
        assertTrue(output.contains("xxxxx"));
    }

    @Test
    void validRightScrollHintUsesDeleteCharacterAndDrawsEnteringCells() {
        RecordingPrintWriter writer = new RecordingPrintWriter();
        ScreenPresenter presenter = new ScreenPresenter(writer, false);
        presenter.present(frame(buffer("abcde", 1, 5), 0, 0, false));
        writer.clearOutput();

        Frame shifted = new Frame(
            buffer("bcdeX", 1, 5),
            ScreenCursor.hidden(),
            new ScrollHint.Horizontal(new Rect(0, 0, 5, 1), 1)
        );
        ScreenPresenter.PresentResult result = presenter.present(shifted);
        String output = writer.output();

        assertTrue(result.updated());
        assertFalse(result.fullRedraw());
        assertTrue(output.contains("\u001B[1P"));
        assertTrue(output.contains("X"));
    }

    @Test
    void validVerticalScrollHintsUseTerminalScrollAndDrawEnteringRows() {
        RecordingPrintWriter writer = new RecordingPrintWriter();
        ScreenPresenter presenter = new ScreenPresenter(writer, false);
        presenter.present(frame(rows("aaaaa", "bbbbb", "ccccc", "ddddd"), 0, 0, false));
        writer.clearOutput();
        Rect region = new Rect(0, 0, 5, 4);

        Frame scrolledDown = new Frame(
            rows("bbbbb", "ccccc", "ddddd", "eeeee"),
            ScreenCursor.hidden(),
            new ScrollHint.Vertical(region)
        );
        presenter.present(scrolledDown);

        assertTrue(writer.output().contains("\u001B[1S"));
        assertTrue(writer.output().contains("eeeee"));
        writer.clearOutput();

        Frame scrolledUp = new Frame(
            rows("zzzzz", "bbbbb", "ccccc", "ddddd"),
            ScreenCursor.hidden(),
            new ScrollHint.Vertical(region)
        );
        presenter.present(scrolledUp);

        assertTrue(writer.output().contains("\u001B[1T"));
        assertTrue(writer.output().contains("zzzzz"));
    }

    @Test
    void failedOutputInvalidatesPhysicalStateAndRetryUsesAFullRedraw() {
        RecoverablePrintWriter writer = new RecoverablePrintWriter();
        ScreenPresenter presenter = new ScreenPresenter(writer, true);
        Frame initial = frame(buffer("first", 1, 6), 0, 0, false);
        Frame changed = frame(buffer("next", 1, 6), 0, 0, true);
        presenter.present(initial);
        writer.clearOutput();
        writer.failNextWrite();

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> presenter.present(changed)
        );

        assertEquals("Failed to present terminal frame", failure.getMessage());
        writer.recover();
        writer.clearOutput();

        ScreenPresenter.PresentResult retry = presenter.present(changed);

        assertTrue(retry.updated());
        assertTrue(retry.fullRedraw());
        assertTrue(writer.output().contains("next"));
        assertTrue(writer.output().contains(SHOW_CURSOR));
        assertTrue(writer.output().contains("\u001B[1;1H"));
    }

    @Test
    void runtimeFailureAfterPartialWriteInvalidatesStateBeforeRetry() {
        RecoverablePrintWriter writer = new RecoverablePrintWriter();
        ScreenPresenter presenter = new ScreenPresenter(writer, true);
        Frame initial = frame(buffer("first", 1, 6), 0, 0, false);
        Frame changed = frame(buffer("later", 1, 6), 0, 0, true);
        presenter.present(initial);
        writer.clearOutput();
        writer.throwOnNextWrite();

        InjectedWriteFailure failure = assertThrows(
            InjectedWriteFailure.class,
            () -> presenter.present(changed)
        );

        assertEquals("injected partial write failure", failure.getMessage());
        writer.recover();
        writer.clearOutput();

        ScreenPresenter.PresentResult retry = presenter.present(changed);

        assertTrue(retry.updated());
        assertTrue(retry.fullRedraw());
        assertTrue(writer.output().contains("later"));
        assertTrue(writer.output().contains(SHOW_CURSOR));
        assertTrue(writer.output().endsWith("\u001B[1;1H"));
    }

    private static Frame frame(CellBuffer cells, int cursorX, int cursorY, boolean cursorVisible) {
        return new Frame(
            cells,
            new ScreenCursor(new Point(cursorX, cursorY), cursorVisible),
            ScrollHint.none()
        );
    }

    private static CellBuffer buffer(String text, int rows, int cols) {
        CellBuffer buffer = new CellBuffer(rows, cols);
        putAscii(buffer, 0, 0, text);
        return buffer;
    }

    private static CellBuffer rows(String... rows) {
        int cols = rows[0].length();
        CellBuffer buffer = new CellBuffer(rows.length, cols);
        for (int row = 0; row < rows.length; row++) {
            putAscii(buffer, row, 0, rows[row]);
        }
        return buffer;
    }

    private static void putAscii(CellBuffer buffer, int row, int startCol, String text) {
        int col = startCol;
        for (int offset = 0; offset < text.length() && col < buffer.cols(); offset++) {
            buffer.set(row, col++, String.valueOf(text.charAt(offset)), CellStyle.DEFAULT);
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static class RecordingPrintWriter extends PrintWriter {
        private final StringWriter output;
        private int flushCount;

        private RecordingPrintWriter() {
            this(new StringWriter());
        }

        private RecordingPrintWriter(StringWriter output) {
            super(output);
            this.output = output;
        }

        @Override
        public void flush() {
            flushCount++;
            super.flush();
        }

        protected final String output() {
            return output.toString();
        }

        protected final void clearOutput() {
            output.getBuffer().setLength(0);
        }

        private int flushCount() {
            return flushCount;
        }
    }

    private static final class RecoverablePrintWriter extends RecordingPrintWriter {
        private boolean failNextWrite;
        private boolean throwOnNextWrite;

        @Override
        public void write(String value, int offset, int length) {
            if (!failNextWrite && !throwOnNextWrite) {
                super.write(value, offset, length);
                return;
            }
            boolean throwAfterPartialWrite = throwOnNextWrite;
            failNextWrite = false;
            throwOnNextWrite = false;
            int partialLength = Math.max(1, length / 2);
            super.write(value, offset, partialLength);
            if (throwAfterPartialWrite) {
                throw new InjectedWriteFailure("injected partial write failure");
            }
            setError();
        }

        private void failNextWrite() {
            failNextWrite = true;
        }

        private void throwOnNextWrite() {
            throwOnNextWrite = true;
        }

        private void recover() {
            failNextWrite = false;
            throwOnNextWrite = false;
            clearError();
        }
    }

    private static final class InjectedWriteFailure extends RuntimeException {
        private InjectedWriteFailure(String message) {
            super(message);
        }
    }
}
