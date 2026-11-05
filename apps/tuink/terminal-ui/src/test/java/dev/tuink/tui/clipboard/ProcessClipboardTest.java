package dev.tuink.tui.clipboard;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Only owned scripts and private files stand in for clipboard services. */
class ProcessClipboardTest {
    @TempDir Path directory;
    private Path bin;

    @BeforeEach void prepare() throws Exception {
        assumeTrue(java.io.File.separatorChar == '/');
        bin = Files.createDirectory(directory.resolve("bin"));
    }

    @Test void waylandPreservesUnicodeEmptyTextAndExactTrailingNewlinesWithoutPuttingTextInArguments() throws Exception {
        helper("wl-copy", copy()); helper("wl-paste", paste());
        var env = environment(); env.put("WAYLAND_DISPLAY", "private-test-display");
        var clipboard = clipboard("Linux", env, 1_000, 1_024);
        String text = "--help; not-a-command\nПривет 😀\tvalue\n\n";
        assertTrue(clipboard.writeText(text));
        assertEquals(text, Files.readString(data()));
        assertEquals(Optional.of(text), clipboard.readText());
        assertEquals(List.of("--type", "text/plain;charset=utf-8"), Files.readAllLines(directory.resolve("copy-args")));
        assertEquals(List.of("--no-newline", "--type", "text"), Files.readAllLines(directory.resolve("paste-args")));
        assertEquals("C.UTF-8", Files.readString(directory.resolve("locale")));
        assertTrue(clipboard.writeText(""));
        assertEquals(Optional.of(""), clipboard.readText(), "empty text is distinct from unavailable text");
    }

    @Test void failedWaylandFallsBackToLocalXclipAndItsBackgroundOwnershipMode() throws Exception {
        helper("wl-copy", "exit 7\n"); helper("wl-paste", "exit 7\n");
        helper("xclip", multiplex());
        var env = environment(); env.put("WAYLAND_DISPLAY", "private-test-display"); env.put("DISPLAY", ":98.0");
        var clipboard = clipboard("Linux", env, 1_000, 1_024);
        assertTrue(clipboard.writeText("xclip text"));
        assertEquals("xclip text", Files.readString(data()));
        var arguments = Files.readAllLines(directory.resolve("args"));
        assertTrue(arguments.containsAll(List.of("-selection", "clipboard", "-target", "UTF8_STRING", "-silent")));
        assertFalse(arguments.contains("-quiet"));
        assertEquals(Optional.of("xclip text"), clipboard.readText());
    }

    @Test void xselWorksWhenItIsTheOnlyLocalX11Helper() throws Exception {
        helper("xsel", multiplex());
        var env = environment(); env.put("DISPLAY", "unix:42");
        var clipboard = clipboard("Linux", env, 1_000, 1_024);
        assertTrue(clipboard.writeText("xsel text"));
        assertEquals(List.of("--clipboard", "--input", "--logfile", "/dev/null"),
            Files.readAllLines(directory.resolve("args")));
        assertEquals(Optional.of("xsel text"), clipboard.readText());
    }

    @Test void macUsesItsPlainTextPasteboardWithAnExplicitUtf8Locale() throws Exception {
        helper("pbcopy", copy()); helper("pbpaste", paste());
        var env = environment(); env.put("LC_ALL", "C");
        var clipboard = clipboard("Mac OS X", env, 1_000, 1_024);
        assertTrue(clipboard.writeText("строка\n"));
        assertEquals(Optional.of("строка\n"), clipboard.readText());
        assertEquals(List.of("-Prefer", "txt"), Files.readAllLines(directory.resolve("paste-args")));
        assertEquals("en_US.UTF-8", Files.readString(directory.resolve("locale")));
    }

    @Test void headlessUnsupportedRemoteAndSshSessionsNeverInvokeDesktopHelpers() throws Exception {
        helper("xclip", "printf started > \"$MARKER\"\n" + multiplex());
        helper("wl-copy", "printf started > \"$MARKER\"\n" + copy());
        helper("wl-paste", "printf started > \"$MARKER\"\n" + paste());
        assertUnavailable(clipboard("Linux", environment(), 100, 1_024));
        var windows = environment(); windows.put("DISPLAY", ":42");
        assertUnavailable(clipboard("Windows 11", windows, 100, 1_024));
        for (String display : List.of("remote.example:0", "localhost:10.0", "127.0.0.1:0")) {
            var env = environment(); env.put("DISPLAY", display);
            assertUnavailable(clipboard("Linux", env, 100, 1_024));
        }
        for (String ssh : List.of("SSH_CONNECTION", "SSH_CLIENT", "SSH_TTY")) {
            var env = environment(); env.put("DISPLAY", ":42"); env.put("WAYLAND_DISPLAY", "private-test-display"); env.put(ssh, "remote-session");
            assertUnavailable(clipboard("Linux", env, 100, 1_024));
        }
        assertFalse(Files.exists(directory.resolve("started")));
    }

    @Test void malformedOrOversizedInputIsRejectedBeforeTheHelperStarts() throws Exception {
        helper("wl-copy", "printf started > \"$MARKER\"\n" + copy()); helper("wl-paste", paste());
        var env = environment(); env.put("WAYLAND_DISPLAY", "private-test-display");
        var clipboard = clipboard("Linux", env, 1_000, 32);
        assertFalse(clipboard.writeText("é".repeat(17)));
        assertFalse(clipboard.writeText("\ud800"));
        assertFalse(clipboard.writeText("\udc00"));
        assertFalse(clipboard.writeText(null));
        assertFalse(Files.exists(directory.resolve("started")));
        assertTrue(clipboard.writeText("é".repeat(16)));
        assertEquals(32, Files.size(data()));
    }

    @Test void malformedUtf8AndOverBudgetOutputAreUnavailableRatherThanTruncatedOrReplaced() throws Exception {
        helper("wl-copy", copy()); helper("wl-paste", paste());
        var env = environment(); env.put("WAYLAND_DISPLAY", "private-test-display");
        var clipboard = clipboard("Linux", env, 1_000, 32);
        Files.write(data(), new byte[] {(byte) 0xc3, 0x28});
        assertEquals(Optional.empty(), clipboard.readText());
        Files.write(data(), new byte[33]);
        assertEquals(Optional.empty(), clipboard.readText());
        Files.write(data(), new byte[0]);
        assertEquals(Optional.of(""), clipboard.readText());
    }

    @Test void hangingReadAndBlockedWriteAreTimedOutAndTheirProcessesAreTerminated() throws Exception {
        String hang = "printf '%s' \"$$\" > \"$PID_FILE\"\nexec /bin/sleep 5\n";
        helper("wl-copy", hang); helper("wl-paste", hang);
        var env = environment(); env.put("WAYLAND_DISPLAY", "private-test-display");
        var clipboard = clipboard("Linux", env, 150, 1_000_000);
        long started = System.nanoTime();
        assertEquals(Optional.empty(), clipboard.readText());
        assertFalse(aliveRecordedProcess());
        assertFalse(clipboard.writeText("x".repeat(500_000)), "writing beyond pipe capacity must also have a deadline");
        assertFalse(aliveRecordedProcess());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
    }

    @Test void successfulDetachedClipboardOwnersAreNotKilledOrWaitedFor() throws Exception {
        helper("wl-copy", "/bin/cat > \"$DATA_FILE\"\n/bin/sleep 5 &\nprintf '%s' \"$!\" > \"$PID_FILE\"\nexit 0\n");
        helper("wl-paste", paste());
        var env = environment(); env.put("WAYLAND_DISPLAY", "private-test-display");
        var clipboard = clipboard("Linux", env, 800, 1_024);
        try {
            long started = System.nanoTime();
            assertTrue(clipboard.writeText("owned text"));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0);
            assertTrue(aliveRecordedProcess(), "the detached owner must survive a successful copy");
            assertEquals("owned text", Files.readString(data()));
        } finally {
            if (Files.exists(directory.resolve("pid"))) ProcessHandle.of(readPid()).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test void interruptedCallerKeepsItsInterruptAndDoesNotStartAHelper() throws Exception {
        helper("wl-copy", "printf started > \"$MARKER\"\n" + copy()); helper("wl-paste", paste());
        var env = environment(); env.put("WAYLAND_DISPLAY", "private-test-display");
        var clipboard = clipboard("Linux", env, 100, 1_024);
        Thread.currentThread().interrupt();
        try {
            assertFalse(clipboard.writeText("text"));
            assertEquals(Optional.empty(), clipboard.readText());
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(Files.exists(directory.resolve("started")));
        } finally { Thread.interrupted(); }
    }

    private Map<String, String> environment() {
        var env = new HashMap<String, String>();
        env.put("PATH", bin.toString()); env.put("DATA_FILE", data().toString());
        env.put("COPY_ARGS", directory.resolve("copy-args").toString()); env.put("PASTE_ARGS", directory.resolve("paste-args").toString());
        env.put("ARGS", directory.resolve("args").toString()); env.put("LOCALE_FILE", directory.resolve("locale").toString());
        env.put("MARKER", directory.resolve("started").toString()); env.put("PID_FILE", directory.resolve("pid").toString());
        return env;
    }
    private static ProcessClipboard clipboard(String os, Map<String, String> env, long millis, int maxBytes) {
        return new ProcessClipboard(os, env, Duration.ofMillis(millis), maxBytes);
    }
    private static void assertUnavailable(ProcessClipboard clipboard) {
        assertEquals(Optional.empty(), clipboard.readText()); assertFalse(clipboard.writeText("text"));
    }
    private Path data() { return directory.resolve("data"); }
    private long readPid() throws Exception { return Long.parseLong(Files.readString(directory.resolve("pid"))); }
    private boolean aliveRecordedProcess() throws Exception { return ProcessHandle.of(readPid()).map(ProcessHandle::isAlive).orElse(false); }
    private void helper(String name, String body) throws Exception {
        Path path = bin.resolve(name); Files.writeString(path, "#!/bin/sh\nset -eu\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }
    private static String copy() {
        return "printf '%s\\n' \"$@\" > \"$COPY_ARGS\"\nprintf '%s' \"$LC_ALL\" > \"$LOCALE_FILE\"\nexec /bin/cat > \"$DATA_FILE\"\n";
    }
    private static String paste() {
        return "printf '%s\\n' \"$@\" > \"$PASTE_ARGS\"\nprintf '%s' \"$LC_ALL\" > \"$LOCALE_FILE\"\nexec /bin/cat \"$DATA_FILE\"\n";
    }
    private static String multiplex() {
        return "printf '%s\\n' \"$@\" > \"$ARGS\"\ncase \" $* \" in *' -out '*|*' --output '*) exec /bin/cat \"$DATA_FILE\";; *) exec /bin/cat > \"$DATA_FILE\";; esac\n";
    }
}
