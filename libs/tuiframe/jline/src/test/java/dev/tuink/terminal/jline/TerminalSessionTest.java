package dev.tuink.terminal.jline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.terminal.capability.ColorDepth;
import dev.tuink.terminal.capability.TerminalCapabilities;
import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.input.ResizeEvent;
import dev.tuink.terminal.input.TextInput;
import dev.tuink.terminal.input.TuiInputEvent;
import dev.tuink.terminal.runtime.TuiApplicationRunner;
import dev.tuink.terminal.runtime.TuiEventLoop;
import dev.tuink.terminal.screen.Frame;
import dev.tuink.terminal.screen.ScreenCursor;
import dev.tuink.terminal.screen.ScrollHint;
import dev.tuink.terminal.style.CellStyle;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.jline.terminal.Attributes;
import org.jline.terminal.Attributes.LocalFlag;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

class TerminalSessionTest {
    private static final String ENABLE_MOUSE = "\u001B[?1000h\u001B[?1002h\u001B[?1006h";
    private static final String DISABLE_MOUSE = "\u001B[?1000l\u001B[?1002l\u001B[?1006l";
    private static final String ENABLE_BRACKETED_PASTE = "\u001B[?2004h";
    private static final String DISABLE_BRACKETED_PASTE = "\u001B[?2004l";

    @Test
    void ownsRawAlternateScreenMouseAndCleanupLifecycle() throws Exception {
        FakeBackend backend = new FakeBackend("xterm-256color");
        FakeShutdownHooks hooks = new FakeShutdownHooks();

        TerminalSession session = TerminalSession.start(
            new TerminalSession.Options(true, true, false),
            backend,
            hooks
        );

        assertTrue(backend.enteredRawMode);
        assertFalse(backend.currentAttributes.getLocalFlag(LocalFlag.ISIG));
        assertSame(session.writer(), backend.writer);
        assertSame(TerminalCapabilities.DEFAULT, session.capabilities());
        assertEquals(24, session.height());
        assertEquals(80, session.width());
        assertEquals(
            "\u001B[?1049h\u001B[?25h\u001B[2J" + ENABLE_BRACKETED_PASTE + ENABLE_MOUSE,
            backend.output()
        );
        assertSame(hooks.added, hooks.current);
        assertEquals("terminal-session-cleanup", hooks.added.getName());

        session.close();

        assertTrue(session.isClosed());
        assertTrue(backend.currentAttributes.getLocalFlag(LocalFlag.ISIG));
        assertEquals(1, backend.closeCount);
        assertEquals(1, hooks.removeCount);
        assertEquals(
            "\u001B[?1049h\u001B[?25h\u001B[2J" + ENABLE_BRACKETED_PASTE + ENABLE_MOUSE
                + "\u001B[0m" + DISABLE_MOUSE + DISABLE_BRACKETED_PASTE
                + "\u001B[?25h\u001B[?1049l\u001B[2J\u001B[H",
            backend.output()
        );

        session.close();
        assertEquals(1, backend.closeCount);
        assertEquals(1, hooks.removeCount);
    }

    @Test
    void composedRuntimeWiresDecoderResizeInterruptAndPresentation() throws Exception {
        FakeBackend backend = new FakeBackend("xterm-256color");
        backend.capabilities = new TerminalCapabilities(
            ColorDepth.ANSI_256,
            true,
            true,
            true
        );
        TerminalRuntime runtime = TerminalRuntime.start(
            new TerminalSession.Options(false, false, false),
            backend,
            new FakeShutdownHooks(),
            false
        );

        assertEquals(ColorDepth.MONOCHROME, runtime.capabilities().colorDepth());
        assertEquals(24, runtime.height());
        assertEquals(80, runtime.width());
        assertTrue(runtime.eventLoop().running());

        backend.enqueueInput("x");
        TuiEventLoop.Poll<TuiInputEvent> inputPoll = runtime.eventLoop().poll(now -> -1L);
        assertEquals(new TextInput("x"), inputPoll.event());

        CellBuffer cells = new CellBuffer(1, 1);
        cells.set(0, 0, "Z", CellStyle.DEFAULT);
        int outputBeforeFrame = backend.output().length();
        assertTrue(runtime.screenPresenter().present(
            new Frame(cells, ScreenCursor.hidden(), ScrollHint.none())
        ).updated());
        assertTrue(backend.output().substring(outputBeforeFrame).contains("Z"));

        backend.height = 3;
        backend.width = 4;
        backend.resizeHandler.run();
        backend.height = 1;
        backend.width = 1;
        backend.resizeHandler.run();
        TuiEventLoop.Poll<?> resizePoll = runtime.eventLoop().poll(now -> -1L);
        ResizeEvent resize = assertInstanceOf(ResizeEvent.class, resizePoll.event());
        assertEquals(1, resize.rows());
        assertEquals(1, resize.columns());
        assertEquals(1, runtime.height());
        assertEquals(1, runtime.width());

        backend.interruptHandler.run();
        assertFalse(runtime.eventLoop().running());
        runtime.close();
        assertEquals(1, backend.closeCount);
    }

    @Test
    void closingRuntimeStopsEventLoopWithoutInterrupt() throws Exception {
        FakeBackend backend = new FakeBackend("xterm-256color");
        TerminalRuntime runtime = TerminalRuntime.start(
            new TerminalSession.Options(false, false, false),
            backend,
            new FakeShutdownHooks(),
            true
        );

        assertTrue(runtime.eventLoop().running());

        runtime.close();

        assertFalse(runtime.eventLoop().running());
        assertEquals(1, backend.closeCount);
    }

    @Test
    void runnerAndRuntimeRestoreTerminalOnceForEveryApplicationExitPath() throws Exception {
        for (RunnerExit exit : RunnerExit.values()) {
            verifyRunnerRuntimeCleanup(exit);
        }
    }

    private static void verifyRunnerRuntimeCleanup(RunnerExit exit) throws Exception {
        FakeBackend backend = new FakeBackend("xterm-256color");
        FakeShutdownHooks hooks = new FakeShutdownHooks();
        List<TuiEventLoop.Poll<TuiInputEvent>> polls = new ArrayList<>();
        IllegalStateException frontendFailure = new IllegalStateException("frontend failed");
        if (exit == RunnerExit.END_OF_INPUT) {
            backend.enqueueEndOfInput();
        } else {
            backend.enqueueInput("x");
        }

        try (TerminalRuntime runtime = TerminalRuntime.start(
            new TerminalSession.Options(true, true, false),
            backend,
            hooks,
            true
        )) {
            if (exit == RunnerExit.INTERRUPT_WITH_EVENT) {
                backend.interruptOnNextRead();
            }
            TuiApplicationRunner.Frontend<TuiInputEvent> frontend = new TuiApplicationRunner.Frontend<>() {
                @Override
                public boolean running() {
                    return true;
                }

                @Override
                public void prepareTurn() {
                }

                @Override
                public boolean renderFrame(TuiEventLoop.FrameTicket frameTicket) {
                    return true;
                }

                @Override
                public long nanosUntilWakeup(long nowNanos) {
                    return -1L;
                }

                @Override
                public void handlePoll(TuiEventLoop.Poll<TuiInputEvent> poll) {
                    polls.add(poll);
                    if (exit == RunnerExit.FRONTEND_CALLBACK_EXCEPTION) {
                        throw frontendFailure;
                    }
                }
            };
            TuiApplicationRunner<TuiInputEvent> runner = new TuiApplicationRunner<>(runtime.eventLoop());

            if (exit == RunnerExit.FRONTEND_CALLBACK_EXCEPTION) {
                assertSame(frontendFailure, assertThrows(IllegalStateException.class, () -> runner.run(frontend)));
            } else {
                runner.run(frontend);
            }

            assertEquals(1, polls.size(), exit.name());
            if (exit == RunnerExit.END_OF_INPUT) {
                assertTrue(polls.get(0).endOfInput(), exit.name());
            } else {
                assertEquals(new TextInput("x"), polls.get(0).event(), exit.name());
            }
            if (exit == RunnerExit.INTERRUPT_WITH_EVENT) {
                assertFalse(runtime.eventLoop().running(), exit.name());
            }
        }

        assertTrue(backend.currentAttributes.getLocalFlag(LocalFlag.ISIG), exit.name());
        assertEquals(1, backend.attributesRestoreCount, exit.name());
        assertEquals(1, backend.closeCount, exit.name());
        assertEquals(1, hooks.removeCount, exit.name());
        assertEquals(null, hooks.current, exit.name());
    }

    @Test
    void runtimeNormalizesUnavailableDimensions() throws Exception {
        FakeBackend dimensionsBackend = new FakeBackend("xterm-256color");
        dimensionsBackend.height = 0;
        dimensionsBackend.width = 0;
        TerminalRuntime runtime = TerminalRuntime.start(
            new TerminalSession.Options(false, false, false),
            dimensionsBackend,
            new FakeShutdownHooks(),
            true
        );

        assertEquals(1, runtime.height());
        assertEquals(1, runtime.width());
        dimensionsBackend.resizeHandler.run();
        ResizeEvent resize = assertInstanceOf(
            ResizeEvent.class,
            runtime.eventLoop().poll(now -> -1L).event()
        );
        assertEquals(new ResizeEvent(1, 1), resize);
        runtime.close();
    }

    @Test
    void runtimeClosesSessionWhenAttachFails() {
        FakeBackend failingBackend = new FakeBackend("xterm-256color");
        failingBackend.resizeRegistrationFailure = new IllegalStateException("resize registration failed");

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> TerminalRuntime.start(
                new TerminalSession.Options(false, false, false),
                failingBackend,
                new FakeShutdownHooks(),
                true
            )
        );
        assertEquals("resize registration failed", failure.getMessage());
        assertEquals(1, failingBackend.closeCount);
    }

    @Test
    void canToggleMouseTrackingWithoutDuplicatingSequences() throws Exception {
        FakeBackend backend = new FakeBackend("xterm");
        TerminalSession session = TerminalSession.start(
            new TerminalSession.Options(false, false, false),
            backend,
            new FakeShutdownHooks()
        );

        assertEquals("\u001B[?25h\u001B[2J" + ENABLE_BRACKETED_PASTE, backend.output());
        session.setMouseTracking(false);
        session.setMouseTracking(true);
        session.setMouseTracking(true);
        session.setMouseTracking(false);

        assertEquals(
            "\u001B[?25h\u001B[2J" + ENABLE_BRACKETED_PASTE + ENABLE_MOUSE + DISABLE_MOUSE,
            backend.output()
        );
        session.close();
    }

    @Test
    void unsupportedMouseTrackingNeverWritesMouseControlSequences() throws Exception {
        FakeBackend backend = new FakeBackend("linux");
        backend.capabilities = new TerminalCapabilities(
            ColorDepth.ANSI_16,
            false,
            true,
            false
        );
        TerminalSession session = TerminalSession.start(
            new TerminalSession.Options(false, true, false),
            backend,
            new FakeShutdownHooks()
        );

        assertSame(backend.capabilities, session.capabilities());
        assertFalse(backend.output().contains(ENABLE_MOUSE));
        session.setMouseTracking(true);
        session.setMouseTracking(false);
        session.close();

        assertFalse(backend.output().contains(ENABLE_MOUSE));
        assertFalse(backend.output().contains(DISABLE_MOUSE));
    }

    @Test
    void classifiesColorMouseAndScrollCapabilitiesConservatively() {
        assertEquals(
            new TerminalCapabilities(ColorDepth.ANSI_256, true, true, true),
            TerminalSession.detectCapabilities(
                "xterm-256color",
                256,
                null,
                "\\E[<",
                true,
                true
            )
        );
        assertTrue(TerminalSession.detectCapabilities(
            "xterm-256color",
            256,
            null,
            "\u001B[<",
            false,
            false
        ).mouseTracking());
        assertTrue(TerminalSession.detectCapabilities(
            "xterm-256color",
            256,
            null,
            "\\033[<",
            false,
            false
        ).mouseTracking());
        assertTrue(TerminalSession.detectCapabilities(
            "xterm-256color",
            256,
            null,
            "\\x1b[<",
            false,
            false
        ).mouseTracking());
        assertEquals(
            new TerminalCapabilities(ColorDepth.TRUECOLOR, true, true, false),
            TerminalSession.detectCapabilities(
                "screen-256color",
                256,
                " TrueColor ",
                "\u001B[M",
                true,
                false
            )
        );
        assertEquals(
            new TerminalCapabilities(ColorDepth.TRUECOLOR, false, true, false),
            TerminalSession.detectCapabilities(
                "linux",
                8,
                "24bit",
                "\u001B[M",
                true,
                false
            )
        );
        assertEquals(
            TerminalCapabilities.MINIMAL,
            TerminalSession.detectCapabilities(
                "unknown",
                null,
                "truecolor",
                null,
                false,
                false
            )
        );
        assertEquals(
            ColorDepth.TRUECOLOR,
            TerminalSession.detectCapabilities(
                "direct",
                16_777_216,
                null,
                null,
                false,
                false
            ).colorDepth()
        );
    }

    @Test
    void delegatesSignalsToApplicationHandlers() throws Exception {
        FakeBackend backend = new FakeBackend("xterm");
        TerminalSession session = TerminalSession.start(
            new TerminalSession.Options(false, false, false),
            backend,
            new FakeShutdownHooks()
        );
        int[] signals = new int[2];

        session.onResize(() -> signals[0]++);
        session.onInterrupt(() -> signals[1]++);
        backend.resizeHandler.run();
        backend.interruptHandler.run();

        assertEquals(1, signals[0]);
        assertEquals(1, signals[1]);
        session.close();
    }

    @Test
    void rejectsDumbTerminalBeforeEnteringRawMode() {
        FakeBackend backend = new FakeBackend("dumb");
        FakeShutdownHooks hooks = new FakeShutdownHooks();

        IOException error = assertThrows(
            IOException.class,
            () -> TerminalSession.start(
                new TerminalSession.Options(true, true, false),
                backend,
                hooks
            )
        );

        assertTrue(error.getMessage().contains("Interactive terminal is not available"));
        assertFalse(backend.enteredRawMode);
        assertEquals(1, backend.closeCount);
        assertEquals(0, hooks.addCount);
    }

    @Test
    void keepsDumbTerminalDiagnosisWhenBackendCloseFails() {
        FakeBackend backend = new FakeBackend("dumb");
        backend.closeFailure = new IOException("close failed");

        IOException error = assertThrows(
            IOException.class,
            () -> TerminalSession.start(
                new TerminalSession.Options(true, true, false),
                backend,
                new FakeShutdownHooks()
            )
        );

        assertTrue(error.getMessage().contains("Interactive terminal is not available"));
        assertEquals(1, error.getSuppressed().length);
        assertEquals("close failed", error.getSuppressed()[0].getMessage());
        assertEquals(1, backend.closeCount);
    }

    @Test
    void restoresTerminalWhenInitializationWritesOnlyPartially() {
        FakeBackend backend = new FakeBackend("xterm");
        backend.failNextPrintAfter(4);
        FakeShutdownHooks hooks = new FakeShutdownHooks();

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> TerminalSession.start(
                new TerminalSession.Options(true, true, false),
                backend,
                hooks
            )
        );

        assertEquals("write failed", error.getMessage());
        assertTrue(backend.currentAttributes.getLocalFlag(LocalFlag.ISIG));
        assertEquals(1, backend.closeCount);
        assertEquals(1, hooks.removeCount);
        assertTrue(backend.output().endsWith(
            "\u001B[0m" + DISABLE_MOUSE + DISABLE_BRACKETED_PASTE
                + "\u001B[?25h\u001B[?1049l\u001B[2J\u001B[H"
        ));
    }

    @Test
    void detectsPrintWriterIoErrorsDuringInitializationAndStillCleansUp() {
        FakeBackend backend = new FakeBackend("xterm");
        backend.failWritesWithIo();
        FakeShutdownHooks hooks = new FakeShutdownHooks();

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> TerminalSession.start(
                new TerminalSession.Options(true, true, false),
                backend,
                hooks
            )
        );

        assertEquals("Failed to write terminal control sequence", error.getMessage());
        assertTrue(backend.currentAttributes.getLocalFlag(LocalFlag.ISIG));
        assertEquals(1, backend.closeCount);
        assertEquals(1, hooks.removeCount);
        assertEquals(1, error.getSuppressed().length);
    }

    @Test
    void restoresTerminalEvenWhenShutdownHookCannotBeRemoved() throws Exception {
        FakeBackend backend = new FakeBackend("xterm");
        FakeShutdownHooks hooks = new FakeShutdownHooks();
        hooks.removeFailure = new SecurityException("denied");
        TerminalSession session = TerminalSession.start(
            new TerminalSession.Options(true, false, false),
            backend,
            hooks
        );

        IOException error = assertThrows(IOException.class, session::close);

        assertEquals("Failed to remove terminal shutdown hook", error.getMessage());
        assertEquals("denied", error.getCause().getMessage());
        assertTrue(session.isClosed());
        assertTrue(backend.currentAttributes.getLocalFlag(LocalFlag.ISIG));
        assertEquals(1, backend.closeCount);
        assertTrue(backend.output().endsWith(
            "\u001B[0m" + DISABLE_MOUSE + DISABLE_BRACKETED_PASTE
                + "\u001B[?25h\u001B[?1049l\u001B[2J\u001B[H"
        ));
    }

    private static final class FakeBackend implements TerminalSession.Backend {
        private final String type;
        private final StringWriter output = new StringWriter();
        private final ArrayDeque<Integer> input = new ArrayDeque<>();
        private PrintWriter writer = new PrintWriter(output, true);
        private final Attributes originalAttributes = new Attributes();
        private Attributes currentAttributes = new Attributes();
        private TerminalCapabilities capabilities = TerminalCapabilities.DEFAULT;
        private boolean enteredRawMode;
        private int attributesRestoreCount;
        private int closeCount;
        private IOException closeFailure;
        private RuntimeException resizeRegistrationFailure;
        private Runnable resizeHandler;
        private Runnable interruptHandler;
        private Runnable beforeNextRead;
        private int height = 24;
        private int width = 80;
        private final NonBlockingReader reader = new NonBlockingReader() {
            @Override
            protected int read(long timeout, boolean isPeek) {
                Runnable beforeRead = beforeNextRead;
                beforeNextRead = null;
                if (beforeRead != null) {
                    beforeRead.run();
                }
                Integer next = input.peekFirst();
                if (next == null) {
                    return READ_EXPIRED;
                }
                if (!isPeek) {
                    input.removeFirst();
                }
                return next;
            }

            @Override
            public int readBuffered(char[] buffer, int offset, int length, long timeout) {
                return READ_EXPIRED;
            }

            @Override
            public void close() {
            }
        };

        private FakeBackend(String type) {
            this.type = type;
            originalAttributes.setLocalFlag(LocalFlag.ISIG, true);
            currentAttributes = new Attributes(originalAttributes);
        }

        private String output() {
            return output.toString();
        }

        private void enqueueInput(String text) {
            for (int index = 0; index < text.length(); index++) {
                input.addLast((int) text.charAt(index));
            }
        }

        private void enqueueEndOfInput() {
            input.addLast(-1);
        }

        private void interruptOnNextRead() {
            beforeNextRead = interruptHandler;
        }

        private void failNextPrintAfter(int characterCount) {
            writer = new PrintWriter(output, true) {
                private boolean fail = true;

                @Override
                public void print(String value) {
                    if (fail) {
                        fail = false;
                        super.print(value.substring(0, Math.min(characterCount, value.length())));
                        throw new IllegalStateException("write failed");
                    }
                    super.print(value);
                }
            };
        }

        private void failWritesWithIo() {
            writer = new PrintWriter(new Writer() {
                @Override
                public void write(char[] buffer, int offset, int length) throws IOException {
                    throw new IOException("write failed");
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public TerminalCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public PrintWriter writer() {
            return writer;
        }

        @Override
        public NonBlockingReader reader() {
            return reader;
        }

        @Override
        public Attributes enterRawMode() {
            enteredRawMode = true;
            return new Attributes(originalAttributes);
        }

        @Override
        public Attributes attributes() {
            return new Attributes(currentAttributes);
        }

        @Override
        public void attributes(Attributes attributes) {
            currentAttributes = new Attributes(attributes);
            if (attributes.getLocalFlag(LocalFlag.ISIG)) {
                attributesRestoreCount++;
            }
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public void onResize(Runnable handler) {
            if (resizeRegistrationFailure != null) {
                throw resizeRegistrationFailure;
            }
            resizeHandler = handler;
        }

        @Override
        public void onInterrupt(Runnable handler) {
            interruptHandler = handler;
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private enum RunnerExit {
        END_OF_INPUT,
        INTERRUPT_WITH_EVENT,
        FRONTEND_CALLBACK_EXCEPTION
    }

    private static final class FakeShutdownHooks implements TerminalSession.ShutdownHooks {
        private Thread added;
        private Thread current;
        private int addCount;
        private int removeCount;
        private RuntimeException removeFailure;

        @Override
        public void add(Thread hook) {
            addCount++;
            added = hook;
            current = hook;
        }

        @Override
        public boolean remove(Thread hook) {
            removeCount++;
            if (removeFailure != null) {
                throw removeFailure;
            }
            if (current == hook) {
                current = null;
                return true;
            }
            return false;
        }
    }
}
