package dev.tuiframe.jline.presentation;

import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.screen.Frame;
import java.io.PrintWriter;
import java.util.Objects;

/**
 * Presents virtual terminal frames through one stateful ANSI output session.
 *
 * <p>The presenter owns the previous physical frame, cursor state and ANSI
 * caches. A frame handed to {@link #present(Frame)} must not be mutated after
 * the call; retaining the buffer avoids a full-screen copy on every render.</p>
 */
public final class ScreenPresenter {
    private static final String SHOW_CURSOR = "\u001B[?25h";
    private static final String HIDE_CURSOR = "\u001B[?25l";

    private final PrintWriter writer;
    private final TerminalCapabilities capabilities;
    private final AnsiDiff ansiDiff = new AnsiDiff();
    private Frame previousFrame;
    private Point cursorPosition;
    private boolean cursorVisible;
    private boolean cursorKnown;

    public ScreenPresenter(PrintWriter writer, boolean colorEnabled) {
        this(writer, TerminalCapabilities.DEFAULT.withColorEnabled(colorEnabled));
    }

    public ScreenPresenter(PrintWriter writer, TerminalCapabilities capabilities) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    public synchronized PresentResult present(Frame frame) {
        return present(frame, false);
    }

    /**
     * Presents a frame and optionally measures only ANSI patch construction.
     * Writer output and flushing are intentionally excluded from patch timing.
     */
    public synchronized PresentResult present(Frame frame, boolean measurePatchTime) {
        Objects.requireNonNull(frame, "frame");
        CellBuffer current = frame.cells();
        CellBuffer previous = previousFrame == null ? null : previousFrame.cells();
        boolean fullRedraw = previous == null
            || previous.rows() != current.rows()
            || previous.cols() != current.cols();

        long patchStart = measurePatchTime ? System.nanoTime() : 0L;
        String patch = ansiDiff.diff(previous, current, capabilities, frame.scrollHint());
        long patchNanos = measurePatchTime ? System.nanoTime() - patchStart : 0L;

        Point desiredPosition = frame.cursor().position();
        boolean desiredVisible = frame.cursor().visible();
        boolean cursorMoved = !cursorKnown || !desiredPosition.equals(cursorPosition);
        boolean visibilityChanged = !cursorKnown || desiredVisible != cursorVisible;
        boolean positionCursor = desiredVisible && (!patch.isEmpty() || cursorMoved || visibilityChanged);

        if (patch.isEmpty() && !visibilityChanged && !positionCursor) {
            commit(frame, desiredPosition, desiredVisible);
            return new PresentResult(false, fullRedraw, 0, patchNanos);
        }

        String visibilitySequence = visibilityChanged
            ? desiredVisible ? SHOW_CURSOR : HIDE_CURSOR
            : "";
        String positionSequence = positionCursor ? moveTo(desiredPosition) : "";
        int emittedCharacters = patch.length() + visibilitySequence.length() + positionSequence.length();
        try {
            if (!patch.isEmpty()) {
                writer.print(patch);
            }
            if (!visibilitySequence.isEmpty()) {
                writer.print(visibilitySequence);
            }
            if (!positionSequence.isEmpty()) {
                writer.print(positionSequence);
            }
            if (writer.checkError()) {
                throw new IllegalStateException("Failed to present terminal frame");
            }
        } catch (RuntimeException failure) {
            // Some bytes may already have reached the terminal. Treat all
            // physical state as unknown so a retry starts with a full redraw.
            invalidate();
            throw failure;
        }

        commit(frame, desiredPosition, desiredVisible);
        return new PresentResult(true, fullRedraw, emittedCharacters, patchNanos);
    }

    /** Marks the physical screen and cursor as unknown for the next frame. */
    public synchronized void invalidate() {
        previousFrame = null;
        cursorPosition = null;
        cursorVisible = false;
        cursorKnown = false;
    }

    private void commit(Frame frame, Point desiredPosition, boolean desiredVisible) {
        previousFrame = frame;
        cursorPosition = desiredPosition;
        cursorVisible = desiredVisible;
        cursorKnown = true;
    }

    private static String moveTo(Point position) {
        return "\u001B[" + (position.y() + 1) + ';' + (position.x() + 1) + 'H';
    }

    public record PresentResult(
        boolean updated,
        boolean fullRedraw,
        int emittedCharacters,
        long patchNanos
    ) {
        public PresentResult {
            if (emittedCharacters < 0 || patchNanos < 0L) {
                throw new IllegalArgumentException("Presentation metrics must not be negative");
            }
        }
    }
}
