package dev.tuiframe.jline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.capability.ColorDepth;
import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.runtime.TuiApplicationRunner;
import dev.tuiframe.runtime.TuiEventLoop;
import dev.tuiframe.screen.Frame;
import dev.tuiframe.screen.ScreenCursor;
import dev.tuiframe.screen.ScrollHint;
import dev.tuiframe.style.CellStyle;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jline.terminal.Attributes;
import org.jline.terminal.Attributes.ControlChar;
import org.jline.terminal.Attributes.LocalFlag;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.InfoCmp.Capability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
                + "\u001B[?25h\u001B[?1049l",
            backend.output()
        );

        session.close();
        assertEquals(1, backend.closeCount);
        assertEquals(1, hooks.removeCount);
    }

    @Test
    void leavingAlternateScreenRestoresTheShellContentAndCursor() throws Exception {
        ScreenBuffers screen = new ScreenBuffers();
        screen.apply("shell output\u001B[7;9H");
        FakeBackend backend = new FakeBackend("xterm-256color");
        TerminalSession session = TerminalSession.start(
            new TerminalSession.Options(true, false, false),
            backend,
            new FakeShutdownHooks()
        );
        session.writer().print("editor frame");
        screen.apply(backend.output());
        int outputBeforeClose = backend.output().length();
        assertTrue(screen.alternateActive);
        assertEquals("editor frame", screen.visibleText());

        session.close();
        screen.apply(backend.output().substring(outputBeforeClose));

        assertFalse(screen.alternateActive);
        assertEquals("shell output", screen.visibleText());
        assertEquals(6, screen.cursorRow);
        assertEquals(8, screen.cursorColumn);
    }

    @Test
    void leavingPrimaryScreenModeClearsTheEditorFrameAndHomesTheShellCursor() throws Exception {
        ScreenBuffers screen = new ScreenBuffers();
        screen.apply("previous shell output\u001B[7;9H");
        FakeBackend backend = new FakeBackend("xterm-256color");
        TerminalSession session = TerminalSession.start(
            new TerminalSession.Options(false, false, false),
            backend,
            new FakeShutdownHooks()
        );
        session.writer().print("editor frame");
        screen.apply(backend.output());
        int outputBeforeClose = backend.output().length();
        assertFalse(screen.alternateActive);
        assertEquals("editor frame", screen.visibleText());

        session.close();
        screen.apply(backend.output().substring(outputBeforeClose));

        assertFalse(screen.alternateActive);
        assertEquals("", screen.visibleText());
        assertEquals(0, screen.cursorRow);
        assertEquals(0, screen.cursorColumn);
        screen.apply("shell prompt");
        assertEquals("shell prompt", screen.visibleText());
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
    void runtimeUsesBackendTerminfoKeysWithoutAssumingATerminalName() throws Exception {
        FakeBackend backend = new FakeBackend("custom-terminal");
        backend.keySequences = Map.of(Capability.key_f1, "\\E[[A", Capability.key_btab, "\\E^I");
        backend.enqueueInput("\u001B[[A\u001B\tx");
        try (TerminalRuntime runtime = TerminalRuntime.start(
            new TerminalSession.Options(false, false, false), backend, new FakeShutdownHooks(), false
        )) {
            assertEquals(KeyStroke.of(KeyStroke.Key.F1), runtime.eventLoop().poll(now -> -1L).event());
            assertEquals(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT),
                runtime.eventLoop().poll(now -> -1L).event());
            assertEquals(new TextInput("x"), runtime.eventLoop().poll(now -> -1L).event());
        }
    }

    @Test
    void runtimeUsesTerminfoBackspaceAndPreservesCtrlHOnDelTerminals() throws Exception {
        for (String sequence : List.of("^H", "^?", "\\010", "\\177")) {
            boolean ctrlHBackspace = sequence.equals("^H") || sequence.equals("\\010");
            FakeBackend backend = new FakeBackend("xterm");
            backend.backspaceSequence = sequence;
            // A stale line-discipline setting must not override a known physical key.
            backend.originalAttributes.setControlChar(ControlChar.VERASE, ctrlHBackspace ? 127 : 8);
            backend.enqueueInput("\b\u007F");

            try (TerminalRuntime runtime = TerminalRuntime.start(
                new TerminalSession.Options(false, false, false),
                backend,
                new FakeShutdownHooks(),
                false
            )) {
                assertEquals(
                    ctrlHBackspace ? KeyStroke.of(KeyStroke.Key.BACKSPACE)
                        : KeyStroke.of(KeyStroke.Key.H, KeyStroke.Modifier.CTRL),
                    runtime.eventLoop().poll(now -> -1L).event()
                );
                assertEquals(KeyStroke.of(KeyStroke.Key.BACKSPACE), runtime.eventLoop().poll(now -> -1L).event());
            }
        }
    }

    @Test
    void runtimeFallsBackToTheOriginalEraseCharacterBeforeRawMode() throws Exception {
        for (boolean ctrlHBackspace : List.of(false, true)) {
            FakeBackend backend = new FakeBackend("vt100");
            backend.originalAttributes.setControlChar(ControlChar.VERASE, ctrlHBackspace ? 8 : 127);
            backend.currentAttributes.setControlChar(ControlChar.VERASE, ctrlHBackspace ? 127 : 8);
            backend.enqueueInput("\b\u007F");

            try (TerminalRuntime runtime = TerminalRuntime.start(
                new TerminalSession.Options(false, false, false),
                backend,
                new FakeShutdownHooks(),
                false
            )) {
                assertEquals(
                    ctrlHBackspace ? KeyStroke.of(KeyStroke.Key.BACKSPACE)
                        : KeyStroke.of(KeyStroke.Key.H, KeyStroke.Modifier.CTRL),
                    runtime.eventLoop().poll(now -> -1L).event()
                );
                assertEquals(KeyStroke.of(KeyStroke.Key.BACKSPACE), runtime.eventLoop().poll(now -> -1L).event());
            }
            assertEquals(ctrlHBackspace ? 8 : 127, backend.currentAttributes.getControlChar(ControlChar.VERASE));
        }
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
    void aSignalRegistrationErrorRestoresTheStartedSessionBeforePropagatingWithCleanupSuppressed() {
        for (boolean failOnResize : List.of(false, true)) {
            FakeBackend backend = new FakeBackend("xterm");
            AssertionError failure = new AssertionError("signal registration failed");
            if (failOnResize) backend.resizeRegistrationError = failure;
            else backend.interruptRegistrationError = failure;
            backend.closeFailure = new IOException("backend cleanup failed");
            FakeShutdownHooks hooks = new FakeShutdownHooks();

            assertSame(failure, assertThrows(AssertionError.class,
                () -> TerminalRuntime.start(new TerminalSession.Options(true, false, false),
                    backend, hooks, true)));

            assertTrue(backend.enteredRawMode);
            assertTrue(backend.currentAttributes.getLocalFlag(LocalFlag.ISIG));
            assertEquals(1, backend.attributesRestoreCount);
            assertEquals(1, backend.closeCount);
            assertEquals(1, hooks.removeCount);
            assertEquals(null, hooks.current);
            assertEquals(1, failure.getSuppressed().length);
            assertSame(backend.closeFailure, failure.getSuppressed()[0]);
            assertTrue(backend.output().endsWith(
                "\u001B[0m" + DISABLE_MOUSE + DISABLE_BRACKETED_PASTE + "\u001B[?25h\u001B[?1049l"));
        }
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

    @ParameterizedTest
    @ValueSource(strings = {"dumb", "DUMB", "DuMb", "dumb-color", "DUMB-COLOR", "DuMb-CoLoR"})
    void rejectsDumbTerminalBeforeEnteringRawMode(String terminalType) {
        FakeBackend backend = new FakeBackend(terminalType);
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
        assertTrue(backend.currentAttributes.getLocalFlag(LocalFlag.ISIG));
        assertEquals("", backend.output());
        assertEquals(0, backend.attributesRestoreCount);
        assertEquals(1, backend.closeCount);
        assertEquals(0, hooks.addCount);
        assertEquals(0, hooks.removeCount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dumb", "DUMB", "DuMb", "dumb-color", "DUMB-COLOR", "DuMb-CoLoR"})
    void keepsDumbTerminalDiagnosisWhenBackendCloseFails(String terminalType) {
        FakeBackend backend = new FakeBackend(terminalType);
        backend.closeFailure = new IOException("close failed");
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
        assertEquals(1, error.getSuppressed().length);
        assertSame(backend.closeFailure, error.getSuppressed()[0]);
        assertFalse(backend.enteredRawMode);
        assertTrue(backend.currentAttributes.getLocalFlag(LocalFlag.ISIG));
        assertEquals("", backend.output());
        assertEquals(0, backend.attributesRestoreCount);
        assertEquals(1, backend.closeCount);
        assertEquals(0, hooks.addCount);
        assertEquals(0, hooks.removeCount);
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
                + "\u001B[?25h\u001B[?1049l"
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
                + "\u001B[?25h\u001B[?1049l"
        ));
    }

    @Test
    void anErrorBeforeSessionConstructionStillRestoresAttributesAndClosesTheBackend() {
        FakeBackend backend = new FakeBackend("xterm");
        AssertionError failure = new AssertionError("attributes unavailable");
        backend.attributesReadFailure = failure;

        assertSame(failure, assertThrows(AssertionError.class,
            () -> TerminalSession.start(new TerminalSession.Options(true, false, false),
                backend, new FakeShutdownHooks())));

        assertEquals(1, backend.attributesRestoreCount);
        assertEquals(1, backend.closeCount);
        assertTrue(backend.currentAttributes.getLocalFlag(LocalFlag.ISIG));
    }

    @Test
    void anErrorAfterSessionConstructionKeepsItsIdentityAndSuppressesCleanupFailure() {
        FakeBackend backend = new FakeBackend("xterm");
        backend.closeFailure = new IOException("cleanup failed");
        FakeShutdownHooks hooks = new FakeShutdownHooks();
        AssertionError failure = new AssertionError("hook unavailable");
        hooks.addFailure = failure;

        assertSame(failure, assertThrows(AssertionError.class,
            () -> TerminalSession.start(new TerminalSession.Options(true, false, false), backend, hooks)));

        assertEquals(1, backend.closeCount);
        assertEquals(1, backend.attributesRestoreCount);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(backend.closeFailure, failure.getSuppressed()[0]);
    }

    @Test
    void anErrorRemovingTheShutdownHookDoesNotSkipTerminalRestoration() throws Exception {
        FakeBackend backend = new FakeBackend("xterm");
        FakeShutdownHooks hooks = new FakeShutdownHooks();
        AssertionError failure = new AssertionError("hook removal failed");
        hooks.removeError = failure;
        TerminalSession session = TerminalSession.start(new TerminalSession.Options(true, false, false), backend, hooks);

        IOException error = assertThrows(IOException.class, session::close);

        assertSame(failure, error.getCause());
        assertTrue(session.isClosed());
        assertEquals(1, backend.attributesRestoreCount);
        assertEquals(1, backend.closeCount);
        assertTrue(backend.currentAttributes.getLocalFlag(LocalFlag.ISIG));
    }

    /** Interprets screen switching, erasing and cursor movement for lifecycle tests. */
    private static final class ScreenBuffers {
        private final StringBuilder primary = new StringBuilder();
        private final StringBuilder alternate = new StringBuilder();
        private boolean alternateActive;
        private int cursorRow;
        private int cursorColumn;
        private int savedCursorRow;
        private int savedCursorColumn;

        private String visibleText() {
            return (alternateActive ? alternate : primary).toString();
        }

        private void apply(String output) {
            for (int offset = 0; offset < output.length();) {
                if (output.charAt(offset) != '\u001B') {
                    (alternateActive ? alternate : primary).append(output.charAt(offset++));
                    cursorColumn++;
                    continue;
                }
                assertEquals('[', output.charAt(offset + 1));
                int end = offset + 2;
                while (end < output.length()
                    && (output.charAt(end) < '@' || output.charAt(end) > '~')) {
                    end++;
                }
                assertTrue(end < output.length(), "complete terminal sequence");
                String parameters = output.substring(offset + 2, end);
                char command = output.charAt(end);
                if (parameters.equals("?1049") && command == 'h') {
                    savedCursorRow = cursorRow;
                    savedCursorColumn = cursorColumn;
                    alternateActive = true;
                    alternate.setLength(0);
                    cursorRow = 0;
                    cursorColumn = 0;
                } else if (parameters.equals("?1049") && command == 'l') {
                    alternateActive = false;
                    cursorRow = savedCursorRow;
                    cursorColumn = savedCursorColumn;
                } else if (command == 'J' && parameters.equals("2")) {
                    (alternateActive ? alternate : primary).setLength(0);
                } else if (command == 'H') {
                    String[] coordinates = parameters.split(";", -1);
                    cursorRow = coordinates[0].isEmpty() ? 0 : Integer.parseInt(coordinates[0]) - 1;
                    cursorColumn = coordinates.length < 2 || coordinates[1].isEmpty()
                        ? 0 : Integer.parseInt(coordinates[1]) - 1;
                } else {
                    assertTrue(command == 'm' || parameters.startsWith("?") && (command == 'h' || command == 'l'));
                }
                offset = end + 1;
            }
        }
    }

    private static final class FakeBackend implements TerminalSession.Backend {
        private final String type;
        private final StringWriter output = new StringWriter();
        private final ArrayDeque<Integer> input = new ArrayDeque<>();
        private PrintWriter writer = new PrintWriter(output, true);
        private final Attributes originalAttributes = new Attributes();
        private Attributes currentAttributes = new Attributes();
        private TerminalCapabilities capabilities = TerminalCapabilities.DEFAULT;
        private String backspaceSequence;
        private Map<Capability, String> keySequences = Map.of();
        private boolean enteredRawMode;
        private int attributesRestoreCount;
        private int closeCount;
        private IOException closeFailure;
        private Error attributesReadFailure;
        private RuntimeException resizeRegistrationFailure;
        private Error resizeRegistrationError;
        private Error interruptRegistrationError;
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
        public String backspaceSequence() {
            return backspaceSequence;
        }

        @Override
        public String keySequence(Capability capability) {
            return keySequences.get(capability);
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
            if (attributesReadFailure != null) throw attributesReadFailure;
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
            if (resizeRegistrationError != null) throw resizeRegistrationError;
            if (resizeRegistrationFailure != null) {
                throw resizeRegistrationFailure;
            }
            resizeHandler = handler;
        }

        @Override
        public void onInterrupt(Runnable handler) {
            if (interruptRegistrationError != null) throw interruptRegistrationError;
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
        private Error addFailure;
        private Error removeError;

        @Override
        public void add(Thread hook) {
            if (addFailure != null) throw addFailure;
            addCount++;
            added = hook;
            current = hook;
        }

        @Override
        public boolean remove(Thread hook) {
            if (removeError != null) throw removeError;
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
