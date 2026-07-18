package dev.tuink.tui.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TuiEventLoopTest {
    private static final TuiEventLoop.Deadline NO_DEADLINE = now -> -1L;

    @Test
    void startsWithOneFullFrameBeforePolling() throws Exception {
        ScriptedInput<String> input = new ScriptedInput<>(TuiEventLoop.Read.idle());
        TuiEventLoop<String> loop = new TuiEventLoop<>(input, () -> 100L);

        TuiEventLoop.FrameTicket frame = loop.beginFrame();

        assertTrue(frame.fullRedraw());
        loop.endFrame(frame, true);
        assertNull(loop.beginFrame());
        loop.poll(NO_DEADLINE);
        assertEquals(List.of(16L), input.timeouts);
    }

    @Test
    void roundsAndCapsDeadlineTimeouts() throws Exception {
        ScriptedInput<String> input = new ScriptedInput<>(
            TuiEventLoop.Read.idle(),
            TuiEventLoop.Read.idle(),
            TuiEventLoop.Read.idle(),
            TuiEventLoop.Read.idle(),
            TuiEventLoop.Read.idle()
        );
        FakeClock clock = new FakeClock(42L);
        TuiEventLoop<String> loop = new TuiEventLoop<>(input, clock);

        loop.poll(now -> 0L);
        loop.poll(now -> 1L);
        loop.poll(now -> 1_000_000L);
        loop.poll(now -> 1_000_001L);
        loop.poll(now -> 80_000_000L);

        assertEquals(List.of(0L, 1L, 1L, 2L, 50L), input.timeouts);
    }

    @Test
    void eventInvalidatesTheNextFrame() throws Exception {
        ScriptedInput<String> input = new ScriptedInput<>(TuiEventLoop.Read.event("key"));
        TuiEventLoop<String> loop = cleanLoop(input);

        TuiEventLoop.Poll<String> poll = loop.poll(NO_DEADLINE);
        TuiEventLoop.FrameTicket frame = loop.beginFrame();

        assertEquals("key", poll.event());
        assertFalse(poll.resized());
        assertFalse(frame.fullRedraw());
    }

    @Test
    void resizeRequestsCoalesceIntoOneFullRedraw() throws Exception {
        ScriptedInput<String> input = new ScriptedInput<>(TuiEventLoop.Read.idle());
        TuiEventLoop<String> loop = cleanLoop(input);
        loop.requestResize();
        loop.requestResize();

        TuiEventLoop.Poll<String> poll = loop.poll(NO_DEADLINE);
        TuiEventLoop.FrameTicket frame = loop.beginFrame();

        assertTrue(poll.resized());
        assertTrue(frame.fullRedraw());
    }

    @Test
    void typedResizeIsDeliveredWithoutWaitingForTerminalInput() throws Exception {
        ScriptedInput<String> input = new ScriptedInput<>();
        TuiEventLoop<String> loop = cleanLoop(input);
        loop.requestResize("40x120");

        TuiEventLoop.Poll<String> poll = loop.poll(NO_DEADLINE);
        TuiEventLoop.FrameTicket frame = loop.beginFrame();

        assertEquals("40x120", poll.event());
        assertTrue(poll.resized());
        assertTrue(frame.fullRedraw());
        assertEquals(List.of(), input.timeouts);
    }

    @Test
    void typedResizeQueuedDuringInputDoesNotDiscardThatInput() throws Exception {
        AtomicReference<TuiEventLoop<String>> loopReference = new AtomicReference<>();
        TuiEventLoop<String> loop = new TuiEventLoop<>(timeout -> {
            loopReference.get().requestResize("40x120");
            return TuiEventLoop.Read.event("key");
        });
        loopReference.set(loop);
        TuiEventLoop.FrameTicket initial = loop.beginFrame();
        loop.endFrame(initial, true);

        TuiEventLoop.Poll<String> key = loop.poll(NO_DEADLINE);
        TuiEventLoop.Poll<String> resize = loop.poll(NO_DEADLINE);

        assertEquals("key", key.event());
        assertFalse(key.resized());
        assertEquals("40x120", resize.event());
        assertTrue(resize.resized());
    }

    @Test
    void failedFrameRetriesAndKeepsItsFullRedrawRequirement() {
        TuiEventLoop<String> loop = new TuiEventLoop<>(timeout -> TuiEventLoop.Read.idle());

        TuiEventLoop.FrameTicket first = loop.beginFrame();
        loop.endFrame(first, false);
        TuiEventLoop.FrameTicket retry = loop.beginFrame();

        assertTrue(retry.fullRedraw());
    }

    @Test
    void invalidationDuringRenderIsNotLost() {
        TuiEventLoop<String> loop = new TuiEventLoop<>(timeout -> TuiEventLoop.Read.idle());

        TuiEventLoop.FrameTicket first = loop.beginFrame();
        loop.invalidate();
        loop.endFrame(first, true);

        assertFalse(loop.beginFrame().fullRedraw());
    }

    @Test
    void stopDuringPollDoesNotDiscardTheCurrentEvent() throws Exception {
        AtomicReference<TuiEventLoop<String>> loopReference = new AtomicReference<>();
        TuiEventLoop<String> loop = new TuiEventLoop<>(timeout -> {
            loopReference.get().requestStop();
            return TuiEventLoop.Read.event("last");
        });
        loopReference.set(loop);

        TuiEventLoop.Poll<String> poll = loop.poll(NO_DEADLINE);

        assertEquals("last", poll.event());
        assertFalse(loop.running());
    }

    @Test
    void endOfInputStopsTheLoop() throws Exception {
        TuiEventLoop<String> loop = new TuiEventLoop<>(timeout -> TuiEventLoop.Read.end());

        TuiEventLoop.Poll<String> poll = loop.poll(NO_DEADLINE);

        assertTrue(poll.endOfInput());
        assertNull(poll.event());
        assertFalse(loop.running());
    }

    @Test
    void inputFailuresPropagate() {
        TuiEventLoop<String> loop = new TuiEventLoop<>(timeout -> {
            throw new IOException("read failed");
        });

        IOException error = assertThrows(IOException.class, () -> loop.poll(NO_DEADLINE));

        assertEquals("read failed", error.getMessage());
    }

    private static <E> TuiEventLoop<E> cleanLoop(ScriptedInput<E> input) {
        TuiEventLoop<E> loop = new TuiEventLoop<>(input);
        TuiEventLoop.FrameTicket initial = loop.beginFrame();
        loop.endFrame(initial, true);
        return loop;
    }

    private static final class ScriptedInput<E> implements TuiEventLoop.TimedInput<E> {
        private final Queue<TuiEventLoop.Read<E>> reads = new ArrayDeque<>();
        private final List<Long> timeouts = new ArrayList<>();

        @SafeVarargs
        private ScriptedInput(TuiEventLoop.Read<E>... reads) {
            this.reads.addAll(List.of(reads));
        }

        @Override
        public TuiEventLoop.Read<E> poll(long timeoutMillis) {
            timeouts.add(timeoutMillis);
            return reads.remove();
        }
    }

    private static final class FakeClock implements TuiEventLoop.NanoClock {
        private long now;

        private FakeClock(long now) {
            this.now = now;
        }

        @Override
        public long nanoTime() {
            return now;
        }
    }
}
