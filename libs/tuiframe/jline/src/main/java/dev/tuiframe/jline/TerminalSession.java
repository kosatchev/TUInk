package dev.tuiframe.jline;

import dev.tuiframe.capability.ColorDepth;
import dev.tuiframe.capability.TerminalCapabilities;
import java.io.IOError;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jline.terminal.Attributes;
import org.jline.terminal.Attributes.ControlChar;
import org.jline.terminal.Attributes.LocalFlag;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.Curses;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

/**
 * Owns one interactive terminal lifecycle.
 *
 * <p>The session is responsible for raw mode, signal handlers, alternate
 * screen, bracketed paste and mouse tracking. Closing it is idempotent and
 * restores terminal state even when application code exits exceptionally.</p>
 */
public final class TerminalSession implements AutoCloseable {
    private static final String ENABLE_MOUSE_TRACKING = "\u001B[?1000h\u001B[?1002h\u001B[?1006h";
    private static final String DISABLE_MOUSE_TRACKING = "\u001B[?1000l\u001B[?1002l\u001B[?1006l";
    private static final String ENABLE_BRACKETED_PASTE = "\u001B[?2004h";
    private static final String DISABLE_BRACKETED_PASTE = "\u001B[?2004l";
    private static final String ENTER_ALTERNATE_SCREEN = "\u001B[?1049h";
    private static final String LEAVE_ALTERNATE_SCREEN = "\u001B[?1049l";
    private static final String SHOW_CURSOR_AND_CLEAR = "\u001B[?25h\u001B[2J";
    private static final String RESET_STYLE = "\u001B[0m";
    private static final String SHOW_CURSOR = "\u001B[?25h";
    private static final String CLEAR_AND_HOME = "\u001B[2J\u001B[H";

    private final Backend backend;
    private final ShutdownHooks shutdownHooks;
    private final Options options;
    private final Attributes originalAttributes;
    private final PrintWriter writer;
    private final TerminalCapabilities capabilities;
    private final boolean backspaceSendsCtrlH;
    private final TerminalKeyMap keyMap;
    private final TerminalLogScope logScope;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread shutdownHook;
    private boolean mouseTrackingEnabled;

    private TerminalSession(
        Backend backend,
        ShutdownHooks shutdownHooks,
        Options options,
        Attributes originalAttributes,
        TerminalLogScope logScope
    ) {
        this.backend = backend;
        this.shutdownHooks = shutdownHooks;
        this.options = options;
        this.originalAttributes = originalAttributes;
        this.logScope = logScope;
        this.writer = backend.writer();
        this.capabilities = Objects.requireNonNull(backend.capabilities(), "backend capabilities");
        this.backspaceSendsCtrlH = detectCtrlHBackspace(backend.backspaceSequence(), originalAttributes);
        this.keyMap = TerminalKeyMap.fromCapabilities(backend::keySequence);
        this.mouseTrackingEnabled = options.mouseTrackingEnabled() && capabilities.mouseTracking();
        this.shutdownHook = new Thread(this::closeQuietly, "terminal-session-cleanup");
    }

    public static TerminalSession open(Options options) throws IOException {
        Objects.requireNonNull(options, "options");
        TerminalLogScope logScope = TerminalLogScope.open();
        try {
            TerminalBuilder builder = TerminalBuilder.builder().system(true).encoding(StandardCharsets.UTF_8);
            if (options.forceExecProvider() || System.getProperty("os.name", "").startsWith("Linux")) {
                // JLine 3's Linux JNI provider drops baud rates when setting attributes.
                // The exec provider preserves them and also works in static images.
                builder
                    .provider("exec")
                    .jni(false)
                    .ffm(false)
                    .jna(false)
                    .jansi(false)
                    .exec(true)
                    .providers("exec,dumb");
            }
            return start(options, new JLineBackend(builder.build()), RuntimeShutdownHooks.INSTANCE, logScope);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                logScope.close();
            } catch (RuntimeException | Error restorationFailure) {
                failure.addSuppressed(restorationFailure);
            }
            throw failure;
        }
    }

    static TerminalSession start(Options options, Backend backend, ShutdownHooks shutdownHooks) throws IOException {
        return start(options, backend, shutdownHooks, null);
    }

    private static TerminalSession start(Options options, Backend backend, ShutdownHooks shutdownHooks,
        TerminalLogScope logScope) throws IOException {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(shutdownHooks, "shutdownHooks");

        if (Terminal.TYPE_DUMB.equalsIgnoreCase(backend.type())
            || Terminal.TYPE_DUMB_COLOR.equalsIgnoreCase(backend.type())) {
            IOException failure = new IOException(
                "Interactive terminal is not available (dumb terminal). "
                    + "Run this application in a real TTY."
            );
            try {
                backend.close();
            } catch (IOException | RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }

        Attributes original = null;
        TerminalSession session = null;
        try {
            original = backend.enterRawMode();
            Attributes raw = backend.attributes();
            raw.setLocalFlag(LocalFlag.ISIG, false);
            backend.attributes(raw);

            session = new TerminalSession(backend, shutdownHooks, options, original, logScope);
            shutdownHooks.add(session.shutdownHook);
            session.writeInitialization();
            return session;
        } catch (IOException | RuntimeException | Error e) {
            if (session != null) {
                try {
                    session.close();
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            } else {
                if (original != null) {
                    try {
                        backend.attributes(original);
                    } catch (IOException | RuntimeException | Error restoreFailure) {
                        e.addSuppressed(restoreFailure);
                    }
                }
                try {
                    backend.close();
                } catch (IOException | RuntimeException | Error closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            throw e;
        }
    }

    public PrintWriter writer() {
        return writer;
    }

    public NonBlockingReader reader() {
        return backend.reader();
    }

    public TerminalCapabilities capabilities() {
        return capabilities;
    }

    boolean backspaceSendsCtrlH() {
        return backspaceSendsCtrlH;
    }

    TerminalKeyMap keyMap() {
        return keyMap;
    }

    private static boolean detectCtrlHBackspace(String backspaceSequence, Attributes originalAttributes) {
        String decoded;
        try {
            // JLine retains terminfo notation, including ^H, ^? and octal escapes.
            decoded = "^?".equals(backspaceSequence) ? "\u007F" : Curses.tputs(backspaceSequence);
        } catch (IOError malformedCapability) {
            decoded = null;
        }
        if ("\b".equals(decoded)) {
            return true;
        }
        if ("\u007F".equals(decoded)) {
            return false;
        }
        // Prefer the physical key advertised by terminfo. When unavailable,
        // retain the user's pre-raw-mode erase convention. On a ^H terminal
        // its legacy Backspace and Ctrl+H bytes are inherently indistinguishable.
        return originalAttributes.getControlChar(ControlChar.VERASE) == '\b';
    }

    public int height() {
        return backend.height();
    }

    public int width() {
        return backend.width();
    }

    public void onResize(Runnable handler) {
        backend.onResize(Objects.requireNonNull(handler, "handler"));
    }

    public void onInterrupt(Runnable handler) {
        backend.onInterrupt(Objects.requireNonNull(handler, "handler"));
    }

    public synchronized void setMouseTracking(boolean enabled) {
        boolean effectiveEnabled = enabled && capabilities.mouseTracking();
        if (closed.get() || mouseTrackingEnabled == effectiveEnabled) {
            return;
        }
        write(effectiveEnabled ? ENABLE_MOUSE_TRACKING : DISABLE_MOUSE_TRACKING);
        mouseTrackingEnabled = effectiveEnabled;
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        Throwable failure = null;
        try {
            closeBackend();
        } catch (IOException | RuntimeException | Error closeFailure) {
            failure = closeFailure;
            throw closeFailure;
        } finally {
            if (logScope != null) {
                try {
                    logScope.close();
                } catch (RuntimeException | Error restorationFailure) {
                    if (failure == null) throw restorationFailure;
                    failure.addSuppressed(restorationFailure);
                }
            }
        }
    }

    private void closeBackend() throws IOException {
        IOException failure = null;
        try {
            shutdownHooks.remove(shutdownHook);
        } catch (IllegalStateException ignored) {
            // The JVM is already shutting down or this is the shutdown hook.
        } catch (RuntimeException | Error e) {
            failure = appendFailure(failure, "Failed to remove terminal shutdown hook", e);
        }

        try {
            StringBuilder cleanup = new StringBuilder(RESET_STYLE);
            if (capabilities.mouseTracking()) {
                cleanup.append(DISABLE_MOUSE_TRACKING);
            }
            cleanup.append(DISABLE_BRACKETED_PASTE);
            cleanup.append(SHOW_CURSOR);
            if (options.alternateScreenEnabled()) {
                cleanup.append(LEAVE_ALTERNATE_SCREEN);
            } else {
                // Only clear frames painted onto the primary screen. Leaving
                // the alternate screen restores the shell's content and cursor.
                cleanup.append(CLEAR_AND_HOME);
            }
            write(cleanup.toString());
        } catch (RuntimeException | Error e) {
            failure = appendFailure(failure, "Failed to restore terminal output state", e);
        }

        try {
            backend.attributes(originalAttributes);
        } catch (IOException | RuntimeException | Error e) {
            failure = appendFailure(failure, "Failed to restore terminal attributes", e);
        }

        try {
            backend.close();
        } catch (IOException | RuntimeException | Error e) {
            failure = appendFailure(failure, "Failed to close terminal", e);
        }

        if (failure != null) {
            throw failure;
        }
    }

    private void writeInitialization() {
        StringBuilder init = new StringBuilder();
        if (options.alternateScreenEnabled()) {
            init.append(ENTER_ALTERNATE_SCREEN);
        }
        init.append(SHOW_CURSOR_AND_CLEAR);
        init.append(ENABLE_BRACKETED_PASTE);
        if (mouseTrackingEnabled) {
            init.append(ENABLE_MOUSE_TRACKING);
        }
        write(init.toString());
    }

    private void write(String sequence) {
        writer.print(sequence);
        writer.flush();
        if (writer.checkError()) {
            throw new IllegalStateException("Failed to write terminal control sequence");
        }
    }

    private void closeQuietly() {
        try {
            close();
        } catch (IOException ignored) {
            // Best effort during process shutdown.
        }
    }

    private static IOException appendFailure(IOException current, String message, Throwable next) {
        IOException wrapped = next instanceof IOException io ? io : new IOException(message, next);
        if (current == null) {
            return wrapped;
        }
        current.addSuppressed(wrapped);
        return current;
    }

    public record Options(
        boolean alternateScreenEnabled,
        boolean mouseTrackingEnabled,
        boolean forceExecProvider
    ) {
    }

    interface Backend extends AutoCloseable {
        String type();

        default TerminalCapabilities capabilities() {
            return TerminalCapabilities.DEFAULT;
        }

        default String backspaceSequence() {
            return null;
        }

        default String keySequence(Capability capability) {
            return null;
        }

        PrintWriter writer();

        NonBlockingReader reader();

        Attributes enterRawMode() throws IOException;

        Attributes attributes();

        void attributes(Attributes attributes) throws IOException;

        int height();

        int width();

        void onResize(Runnable handler);

        void onInterrupt(Runnable handler);

        @Override
        void close() throws IOException;
    }

    interface ShutdownHooks {
        void add(Thread hook);

        boolean remove(Thread hook);
    }

    private static final class JLineBackend implements Backend {
        private final Terminal terminal;
        private final TerminalCapabilities capabilities;

        private JLineBackend(Terminal terminal) {
            this.terminal = terminal;
            this.capabilities = detectCapabilities(
                terminal.getType(),
                terminal.getNumericCapability(Capability.max_colors),
                System.getenv("COLORTERM"),
                terminal.getStringCapability(Capability.key_mouse),
                hasCapability(terminal, Capability.parm_dch)
                    && hasCapability(terminal, Capability.parm_ich),
                hasCapability(terminal, Capability.change_scroll_region)
                    && hasCapability(terminal, Capability.parm_index)
                    && hasCapability(terminal, Capability.parm_rindex)
            );
        }

        @Override
        public String type() {
            return terminal.getType();
        }

        @Override
        public TerminalCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public String backspaceSequence() {
            return terminal.getStringCapability(Capability.key_backspace);
        }

        @Override
        public String keySequence(Capability capability) {
            return terminal.getStringCapability(capability);
        }

        @Override
        public PrintWriter writer() {
            return terminal.writer();
        }

        @Override
        public NonBlockingReader reader() {
            return terminal.reader();
        }

        @Override
        public Attributes enterRawMode() {
            return terminal.enterRawMode();
        }

        @Override
        public Attributes attributes() {
            return terminal.getAttributes();
        }

        @Override
        public void attributes(Attributes attributes) {
            terminal.setAttributes(attributes);
        }

        @Override
        public int height() {
            return terminal.getHeight();
        }

        @Override
        public int width() {
            return terminal.getWidth();
        }

        @Override
        public void onResize(Runnable handler) {
            terminal.handle(Terminal.Signal.WINCH, signal -> handler.run());
        }

        @Override
        public void onInterrupt(Runnable handler) {
            terminal.handle(Terminal.Signal.INT, signal -> handler.run());
        }

        @Override
        public void close() throws IOException {
            terminal.close();
        }

        private static boolean hasCapability(Terminal terminal, Capability capability) {
            String sequence = terminal.getStringCapability(capability);
            return sequence != null && !sequence.isEmpty();
        }
    }

    static TerminalCapabilities detectCapabilities(
        String terminalType,
        Integer maximumColors,
        String colorTerm,
        String mouseSequence,
        boolean horizontalScroll,
        boolean verticalScroll
    ) {
        int colors = maximumColors == null ? 0 : Math.max(0, maximumColors);
        ColorDepth colorDepth;
        if (colors == 0) {
            colorDepth = ColorDepth.MONOCHROME;
        } else if (colors > 256 || isTrueColorHint(colorTerm)) {
            colorDepth = ColorDepth.TRUECOLOR;
        } else if (colors >= 256) {
            colorDepth = ColorDepth.ANSI_256;
        } else {
            colorDepth = ColorDepth.ANSI_16;
        }
        return new TerminalCapabilities(
            colorDepth,
            supportsSgrMouse(terminalType, mouseSequence),
            horizontalScroll,
            verticalScroll
        );
    }

    private static boolean isTrueColorHint(String colorTerm) {
        if (colorTerm == null) {
            return false;
        }
        String normalized = colorTerm.trim().toLowerCase(Locale.ROOT);
        return "truecolor".equals(normalized) || "24bit".equals(normalized);
    }

    private static boolean supportsSgrMouse(String terminalType, String mouseSequence) {
        if (mouseSequence == null || mouseSequence.isEmpty()) {
            return false;
        }
        String normalizedSequence = mouseSequence.toLowerCase(Locale.ROOT);
        if (mouseSequence.contains("\u001B[<")
            || normalizedSequence.contains("\\e[<")
            || normalizedSequence.contains("\\033[<")
            || normalizedSequence.contains("\\x1b[<")) {
            return true;
        }
        String normalizedType = terminalType == null ? "" : terminalType.toLowerCase(Locale.ROOT);
        // screen and tmux commonly publish the legacy kmous sequence while
        // forwarding the SGR protocol that this frontend enables and decodes.
        return normalizedType.startsWith("screen") || normalizedType.startsWith("tmux");
    }

    private enum RuntimeShutdownHooks implements ShutdownHooks {
        INSTANCE;

        @Override
        public void add(Thread hook) {
            Runtime.getRuntime().addShutdownHook(hook);
        }

        @Override
        public boolean remove(Thread hook) {
            return Runtime.getRuntime().removeShutdownHook(hook);
        }
    }
}
