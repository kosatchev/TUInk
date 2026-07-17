package dev.tuink.tui.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.jline.terminal.Attributes;
import org.jline.terminal.Attributes.LocalFlag;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

class TerminalSessionTest {
    private static final String ENABLE_MOUSE = "\u001B[?1000h\u001B[?1002h\u001B[?1006h";
    private static final String DISABLE_MOUSE = "\u001B[?1000l\u001B[?1002l\u001B[?1006l";

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
        assertEquals(24, session.height());
        assertEquals(80, session.width());
        assertEquals("\u001B[?1049h\u001B[?25h\u001B[2J" + ENABLE_MOUSE, backend.output());
        assertSame(hooks.added, hooks.current);

        session.close();

        assertTrue(session.isClosed());
        assertTrue(backend.currentAttributes.getLocalFlag(LocalFlag.ISIG));
        assertEquals(1, backend.closeCount);
        assertEquals(1, hooks.removeCount);
        assertEquals(
            "\u001B[?1049h\u001B[?25h\u001B[2J" + ENABLE_MOUSE
                + "\u001B[0m" + DISABLE_MOUSE + "\u001B[?25h\u001B[?1049l\u001B[2J\u001B[H",
            backend.output()
        );

        session.close();
        assertEquals(1, backend.closeCount);
        assertEquals(1, hooks.removeCount);
    }

    @Test
    void canToggleMouseTrackingWithoutDuplicatingSequences() throws Exception {
        FakeBackend backend = new FakeBackend("xterm");
        TerminalSession session = TerminalSession.start(
            new TerminalSession.Options(false, false, false),
            backend,
            new FakeShutdownHooks()
        );

        assertEquals("\u001B[?25h\u001B[2J", backend.output());
        session.setMouseTracking(false);
        session.setMouseTracking(true);
        session.setMouseTracking(true);
        session.setMouseTracking(false);

        assertEquals("\u001B[?25h\u001B[2J" + ENABLE_MOUSE + DISABLE_MOUSE, backend.output());
        session.close();
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
            "\u001B[0m" + DISABLE_MOUSE + "\u001B[?25h\u001B[?1049l\u001B[2J\u001B[H"
        ));
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
            "\u001B[0m" + DISABLE_MOUSE + "\u001B[?25h\u001B[?1049l\u001B[2J\u001B[H"
        ));
    }

    private static final class FakeBackend implements TerminalSession.Backend {
        private final String type;
        private final StringWriter output = new StringWriter();
        private PrintWriter writer = new PrintWriter(output, true);
        private final Attributes originalAttributes = new Attributes();
        private Attributes currentAttributes = new Attributes();
        private boolean enteredRawMode;
        private int closeCount;
        private IOException closeFailure;
        private Runnable resizeHandler;
        private Runnable interruptHandler;

        private FakeBackend(String type) {
            this.type = type;
            originalAttributes.setLocalFlag(LocalFlag.ISIG, true);
            currentAttributes = new Attributes(originalAttributes);
        }

        private String output() {
            return output.toString();
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

        @Override
        public String type() {
            return type;
        }

        @Override
        public PrintWriter writer() {
            return writer;
        }

        @Override
        public NonBlockingReader reader() {
            return null;
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
        }

        @Override
        public int height() {
            return 24;
        }

        @Override
        public int width() {
            return 80;
        }

        @Override
        public void onResize(Runnable handler) {
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
