package dev.tuiframe.jline;

import dev.tuiframe.io.DiagnosticOutput;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** Keeps JLine's own diagnostics on the plain-output boundary while a terminal is owned. */
final class TerminalLogScope implements AutoCloseable {
    private static final Object LOCK = new Object();
    private static final Logger LOGGER = Logger.getLogger("org.jline");
    private static Handler[] previousHandlers;
    private static boolean previousParentHandlers;
    private static SafeHandler handler;
    private static int owners;

    private boolean closed;

    private TerminalLogScope() {}

    static TerminalLogScope open() {
        // Allocate the token before changing process-visible logger routing.
        TerminalLogScope scope = new TerminalLogScope();
        synchronized (LOCK) {
            if (owners == 0) {
                Handler[] retained = LOGGER.getHandlers();
                boolean parentHandlers = LOGGER.getUseParentHandlers();
                SafeHandler installed = new SafeHandler();
                try {
                    LOGGER.setUseParentHandlers(false);
                    for (Handler old : retained) LOGGER.removeHandler(old);
                    LOGGER.addHandler(installed);
                } catch (RuntimeException | Error failure) {
                    try {
                        restoreRouting(installed, retained, parentHandlers);
                    } catch (RuntimeException | Error restorationFailure) {
                        failure.addSuppressed(restorationFailure);
                    }
                    throw failure;
                }
                previousHandlers = retained;
                previousParentHandlers = parentHandlers;
                handler = installed;
            }
            owners++;
            return scope;
        }
    }

    @Override public void close() {
        synchronized (LOCK) {
            if (closed) return;
            closed = true;
            if (--owners != 0) return;
            try {
                restoreRouting(handler, previousHandlers, previousParentHandlers);
            } finally {
                previousHandlers = null;
                handler = null;
            }
        }
    }

    private static void restoreRouting(Handler installed, Handler[] retained, boolean parentHandlers) {
        Throwable failure = null;
        try {
            LOGGER.removeHandler(installed);
        } catch (RuntimeException | Error error) {
            failure = error;
        }
        for (Handler old : retained) {
            try {
                boolean present = false;
                for (Handler current : LOGGER.getHandlers()) present |= current == old;
                if (!present) LOGGER.addHandler(old);
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        try {
            LOGGER.setUseParentHandlers(parentHandlers);
        } catch (RuntimeException | Error error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    private static final class SafeHandler extends Handler {
        private SafeHandler() {
            setLevel(Level.ALL);
            setFormatter(new SimpleFormatter());
        }

        @Override public void publish(LogRecord record) {
            if (record == null || !isLoggable(record)) return;
            DiagnosticOutput.line(System.err,
                "JLine " + record.getLevel().getName() + ": " + getFormatter().formatMessage(record));
            if (record.getThrown() != null) DiagnosticOutput.failure(System.err, record.getThrown());
        }

        @Override public void flush() { System.err.flush(); }

        /** The handler borrows stderr and never closes it. */
        @Override public void close() { flush(); }
    }
}
