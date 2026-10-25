package dev.tuiframe.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;

class TuiApplicationRunnerTest {
    @Test
    void rendersInitialFullFrameThenEventFrameAndDeliversEndOfInput() throws Exception {
        ScriptedInput<String> input = new ScriptedInput<>(
            TuiEventLoop.Read.event("key"),
            TuiEventLoop.Read.end()
        );
        TuiEventLoop<String> loop = new TuiEventLoop<>(input);
        RecordingFrontend<String> frontend = new RecordingFrontend<>();
        List<String> trace = new ArrayList<>();
        frontend.onPrepare = () -> trace.add("prepare");
        frontend.onRender = ticket -> {
            trace.add("render:" + ticket.sequence() + ":" + ticket.fullRedraw());
            return true;
        };
        frontend.onFrameEnded = rendered -> trace.add("ended:" + rendered);
        frontend.onPoll = poll -> trace.add(
            "poll:" + poll.event() + ":" + poll.endOfInput()
        );

        new TuiApplicationRunner<>(loop).run(frontend);

        assertEquals(
            List.of(
                "prepare",
                "render:1:true",
                "ended:true",
                "poll:key:false",
                "prepare",
                "render:2:false",
                "ended:true",
                "poll:null:true"
            ),
            trace
        );
        assertFalse(loop.running());
    }

    @Test
    void idleReevaluatesDeadlineWithoutDispatchingInputOrRenderingAnotherFrame() throws Exception {
        ScriptedInput<String> input = new ScriptedInput<>(
            TuiEventLoop.Read.idle(),
            TuiEventLoop.Read.end()
        );
        TuiEventLoop<String> loop = new TuiEventLoop<>(input, () -> 42L);
        RecordingFrontend<String> frontend = new RecordingFrontend<>();
        AtomicInteger deadlines = new AtomicInteger();
        frontend.nanosUntilWakeup = now -> deadlines.getAndIncrement() == 0
            ? 1_000_001L
            : 0L;

        new TuiApplicationRunner<>(loop).run(frontend);

        assertEquals(2, frontend.prepareCount);
        assertEquals(1, frontend.frames.size());
        assertTrue(frontend.frames.get(0).fullRedraw());
        assertEquals(List.of(2L, 1L), input.timeouts);
        assertEquals(2, deadlines.get());
        assertEquals(2, frontend.polls.size());
        assertEquals(null, frontend.polls.get(0).event());
        assertFalse(frontend.polls.get(0).endOfInput());
        assertTrue(frontend.polls.get(1).endOfInput());
    }

    @Test
    void nonRenderedFrameRetriesWithItsFullRedrawRequirement() throws Exception {
        ScriptedInput<String> input = new ScriptedInput<>(
            TuiEventLoop.Read.idle(),
            TuiEventLoop.Read.end()
        );
        TuiEventLoop<String> loop = new TuiEventLoop<>(input);
        RecordingFrontend<String> frontend = new RecordingFrontend<>();
        AtomicInteger renders = new AtomicInteger();
        frontend.onRender = ticket -> renders.getAndIncrement() > 0;

        new TuiApplicationRunner<>(loop).run(frontend);

        assertEquals(List.of(true, true), fullRedrawFlags(frontend.frames));
        assertEquals(List.of(false, true), frontend.endedFrames);
    }

    @Test
    void invalidationDuringRenderSchedulesAnotherNonFullFrame() throws Exception {
        ScriptedInput<String> input = new ScriptedInput<>(
            TuiEventLoop.Read.idle(),
            TuiEventLoop.Read.end()
        );
        TuiEventLoop<String> loop = new TuiEventLoop<>(input);
        RecordingFrontend<String> frontend = new RecordingFrontend<>();
        AtomicInteger renders = new AtomicInteger();
        frontend.onRender = ticket -> {
            if (renders.getAndIncrement() == 0) {
                loop.invalidate();
            }
            return true;
        };

        new TuiApplicationRunner<>(loop).run(frontend);

        assertEquals(List.of(true, false), fullRedrawFlags(frontend.frames));
        assertEquals(List.of(true, true), frontend.endedFrames);
    }

    @Test
    void typedResizeDuringPollIsDeliveredAndForcesAFullFrame() throws Exception {
        AtomicReference<TuiEventLoop<String>> loopReference = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        TuiEventLoop<String> loop = new TuiEventLoop<>(timeout -> {
            if (reads.getAndIncrement() == 0) {
                loopReference.get().requestResize("40x120");
                return TuiEventLoop.Read.idle();
            }
            return TuiEventLoop.Read.end();
        });
        loopReference.set(loop);
        RecordingFrontend<String> frontend = new RecordingFrontend<>();

        new TuiApplicationRunner<>(loop).run(frontend);

        assertEquals(List.of(true, true), fullRedrawFlags(frontend.frames));
        assertEquals("40x120", frontend.polls.get(0).event());
        assertTrue(frontend.polls.get(0).resized());
        assertFalse(frontend.polls.get(0).endOfInput());
        assertTrue(frontend.polls.get(1).endOfInput());
    }

    @Test
    void interruptDuringPollDeliversTheCurrentEventThenStops() throws Exception {
        AtomicReference<TuiEventLoop<String>> loopReference = new AtomicReference<>();
        TuiEventLoop<String> loop = new TuiEventLoop<>(timeout -> {
            loopReference.get().requestStop();
            return TuiEventLoop.Read.event("last");
        });
        loopReference.set(loop);
        RecordingFrontend<String> frontend = new RecordingFrontend<>();

        new TuiApplicationRunner<>(loop).run(frontend);

        assertEquals(1, frontend.prepareCount);
        assertEquals(1, frontend.frames.size());
        assertEquals(1, frontend.polls.size());
        assertEquals("last", frontend.polls.get(0).event());
        assertFalse(loop.running());
    }

    @Test
    void stoppedLoopDoesNotInvokeFrontendCallbacks() throws Exception {
        TuiEventLoop<String> loop = new TuiEventLoop<>(timeout -> {
            throw new AssertionError("input must not be polled");
        });
        loop.requestStop();
        RecordingFrontend<String> frontend = new RecordingFrontend<>();

        new TuiApplicationRunner<>(loop).run(frontend);

        assertEquals(0, frontend.runningChecks);
        assertEquals(0, frontend.prepareCount);
        assertEquals(List.of(), frontend.frames);
        assertEquals(List.of(), frontend.polls);
    }

    @Test
    void stoppingDuringPreparationSkipsRenderingAndLeavesQueuedInputUnread() throws Exception {
        for (boolean stopRuntime : List.of(false, true)) {
            ScriptedInput<String> input = new ScriptedInput<>(TuiEventLoop.Read.event("late edit"));
            TuiEventLoop<String> loop = new TuiEventLoop<>(input);
            RecordingFrontend<String> frontend = new RecordingFrontend<>();
            frontend.onPrepare = () -> {
                if (stopRuntime) loop.requestStop();
                else frontend.running = false;
            };

            new TuiApplicationRunner<>(loop).run(frontend);

            assertEquals(1, frontend.prepareCount);
            assertEquals(List.of(), frontend.frames);
            assertEquals(List.of(), frontend.endedFrames);
            assertEquals(List.of(), frontend.polls);
            assertEquals(List.of(), input.timeouts);
        }
    }

    @Test
    void stoppingDuringAFrameCompletesItAndLeavesQueuedInputUnread() throws Exception {
        for (boolean duringRender : List.of(false, true)) {
            ScriptedInput<String> input = new ScriptedInput<>(TuiEventLoop.Read.event("late edit"));
            TuiEventLoop<String> loop = new TuiEventLoop<>(input);
            RecordingFrontend<String> frontend = new RecordingFrontend<>();
            frontend.onRender = ticket -> {
                if (duringRender) frontend.running = false;
                return true;
            };
            frontend.onFrameEnded = rendered -> {
                if (!duringRender) loop.requestStop();
            };

            new TuiApplicationRunner<>(loop).run(frontend);

            assertEquals(1, frontend.prepareCount);
            assertEquals(1, frontend.frames.size());
            assertEquals(List.of(true), frontend.endedFrames);
            assertEquals(List.of(), frontend.polls);
            assertEquals(List.of(), input.timeouts);
        }
    }

    @Test
    void renderFailureEndsTicketAsFailedAndPropagates() {
        TuiEventLoop<String> loop = new TuiEventLoop<>(timeout -> {
            throw new AssertionError("input must not be polled");
        });
        RecordingFrontend<String> frontend = new RecordingFrontend<>();
        frontend.onRender = ticket -> {
            throw new IllegalStateException("render failed");
        };

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> new TuiApplicationRunner<>(loop).run(frontend)
        );

        assertEquals("render failed", error.getMessage());
        assertEquals(List.of(), frontend.endedFrames);
        assertEquals(List.of(), frontend.polls);
        TuiEventLoop.FrameTicket retry = loop.beginFrame();
        assertTrue(retry.fullRedraw());
        loop.endFrame(retry, true);
    }

    @Test
    void pollingFailurePropagates() {
        TuiEventLoop<String> loop = new TuiEventLoop<>(timeout -> {
            throw new IOException("read failed");
        });
        RecordingFrontend<String> frontend = new RecordingFrontend<>();

        IOException error = assertThrows(
            IOException.class,
            () -> new TuiApplicationRunner<>(loop).run(frontend)
        );

        assertEquals("read failed", error.getMessage());
        assertEquals(1, frontend.frames.size());
        assertEquals(List.of(true), frontend.endedFrames);
        assertEquals(List.of(), frontend.polls);
    }

    @Test
    void pollHandlerFailurePropagates() {
        TuiEventLoop<String> loop = new TuiEventLoop<>(
            timeout -> TuiEventLoop.Read.event("key")
        );
        RecordingFrontend<String> frontend = new RecordingFrontend<>();
        frontend.onPoll = poll -> {
            throw new IllegalArgumentException("input failed");
        };

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> new TuiApplicationRunner<>(loop).run(frontend)
        );

        assertEquals("input failed", error.getMessage());
        assertEquals(1, frontend.polls.size());
        TuiEventLoop.FrameTicket pending = loop.beginFrame();
        assertFalse(pending.fullRedraw());
        loop.endFrame(pending, true);
    }

    private static List<Boolean> fullRedrawFlags(List<TuiEventLoop.FrameTicket> frames) {
        return frames.stream().map(TuiEventLoop.FrameTicket::fullRedraw).toList();
    }

    private static final class RecordingFrontend<E> implements TuiApplicationRunner.Frontend<E> {
        private int runningChecks;
        private int prepareCount;
        private final List<TuiEventLoop.FrameTicket> frames = new ArrayList<>();
        private final List<Boolean> endedFrames = new ArrayList<>();
        private final List<TuiEventLoop.Poll<E>> polls = new ArrayList<>();
        private boolean running = true;
        private Runnable onPrepare = () -> { };
        private Function<TuiEventLoop.FrameTicket, Boolean> onRender = ticket -> true;
        private Consumer<Boolean> onFrameEnded = rendered -> { };
        private LongUnaryOperator nanosUntilWakeup = now -> -1L;
        private Consumer<TuiEventLoop.Poll<E>> onPoll = poll -> { };

        @Override
        public boolean running() {
            runningChecks++;
            return running;
        }

        @Override
        public void prepareTurn() {
            prepareCount++;
            onPrepare.run();
        }

        @Override
        public boolean renderFrame(TuiEventLoop.FrameTicket frameTicket) {
            frames.add(frameTicket);
            return onRender.apply(frameTicket);
        }

        @Override
        public void frameEnded(boolean rendered) {
            endedFrames.add(rendered);
            onFrameEnded.accept(rendered);
        }

        @Override
        public long nanosUntilWakeup(long nowNanos) {
            return nanosUntilWakeup.applyAsLong(nowNanos);
        }

        @Override
        public void handlePoll(TuiEventLoop.Poll<E> poll) {
            polls.add(poll);
            onPoll.accept(poll);
        }
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
}
