package dev.tuiframe.jline;

import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.jline.presentation.ScreenPresenter;
import dev.tuiframe.runtime.TuiEventLoop;
import java.io.IOException;
import java.util.Objects;

/**
 * Owns the reusable JLine runtime objects for one terminal application.
 *
 * <p>The runtime wires physical input decoding, resize and interrupt signals,
 * frame scheduling, and ANSI presentation around one {@link TerminalSession}.
 * The application remains responsible only for updating its model, painting
 * frames, and deciding when to stop.</p>
 */
public final class TerminalRuntime implements AutoCloseable {
    private final TerminalSession session;
    private final TerminalCapabilities capabilities;
    private final TuiEventLoop<TuiInputEvent> eventLoop;
    private final ScreenPresenter screenPresenter;

    private TerminalRuntime(TerminalSession session, boolean colorEnabled) {
        this.session = Objects.requireNonNull(session, "session");
        this.capabilities = session.capabilities().withColorEnabled(colorEnabled);
        KeyDecoder decoder = new KeyDecoder(session.reader(), session.backspaceSendsCtrlH());
        this.eventLoop = new TuiEventLoop<>(timeoutMillis -> {
            KeyDecoder.ReadResult result = decoder.read(timeoutMillis);
            return switch (result.status()) {
                case EVENT -> TuiEventLoop.Read.event(result.event());
                case IDLE -> TuiEventLoop.Read.idle();
                case END -> TuiEventLoop.Read.end();
            };
        });
        this.screenPresenter = new ScreenPresenter(session.writer(), capabilities);
        session.onResize(() -> eventLoop.requestResize(
            new ResizeEvent(height(), width())
        ));
        session.onInterrupt(eventLoop::requestStop);
    }

    public static TerminalRuntime open(TerminalSession.Options options, boolean colorEnabled) throws IOException {
        return attach(TerminalSession.open(options), colorEnabled);
    }

    static TerminalRuntime start(
        TerminalSession.Options options,
        TerminalSession.Backend backend,
        TerminalSession.ShutdownHooks shutdownHooks,
        boolean colorEnabled
    ) throws IOException {
        return attach(TerminalSession.start(options, backend, shutdownHooks), colorEnabled);
    }

    private static TerminalRuntime attach(TerminalSession session, boolean colorEnabled) throws IOException {
        try {
            return new TerminalRuntime(session, colorEnabled);
        } catch (RuntimeException failure) {
            try {
                session.close();
            } catch (IOException | RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public TerminalCapabilities capabilities() {
        return capabilities;
    }

    public TuiEventLoop<TuiInputEvent> eventLoop() {
        return eventLoop;
    }

    public ScreenPresenter screenPresenter() {
        return screenPresenter;
    }

    /** Returns the current terminal height, normalized to at least one row. */
    public int height() {
        return Math.max(1, session.height());
    }

    /** Returns the current terminal width, normalized to at least one column. */
    public int width() {
        return Math.max(1, session.width());
    }

    public void setMouseTracking(boolean enabled) {
        session.setMouseTracking(enabled);
    }

    @Override
    public void close() throws IOException {
        eventLoop.requestStop();
        session.close();
    }
}
