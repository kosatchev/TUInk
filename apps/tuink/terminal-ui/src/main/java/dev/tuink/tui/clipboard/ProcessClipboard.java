package dev.tuink.tui.clipboard;

import dev.tuink.editor.clipboard.ClipboardPort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded desktop clipboard helpers. The caller owns scheduling and presentation lifetime. */
public final class ProcessClipboard implements ClipboardPort {
    static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;
    static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(1);

    private record Backend(List<String> read, List<String> write) {}
    private final List<Backend> backends;
    private final Map<String, String> environment;
    private final long timeoutNanos;
    private final int maxBytes;

    /** Headless, SSH and unsupported sessions keep their application's local clipboard. */
    public static Optional<ClipboardPort> detect() {
        try {
            var clipboard = new ProcessClipboard(System.getProperty("os.name", ""), System.getenv(),
                OPERATION_TIMEOUT, MAX_TEXT_BYTES);
            return clipboard.backends.isEmpty() ? Optional.empty() : Optional.of(clipboard);
        } catch (SecurityException unavailable) {
            return Optional.empty();
        }
    }

    ProcessClipboard(String osName, Map<String, String> environment, Duration timeout, int maxBytes) {
        if (timeout.isNegative() || timeout.isZero() || maxBytes < 1 || maxBytes > MAX_TEXT_BYTES)
            throw new IllegalArgumentException("Invalid clipboard operation limits");
        this.environment = Map.copyOf(environment);
        timeoutNanos = timeout.toNanos();
        this.maxBytes = maxBytes;
        backends = detectBackends(osName, this.environment);
    }

    @Override public Optional<String> readText() {
        long deadline = System.nanoTime() + timeoutNanos;
        for (int index = 0; index < backends.size(); index++) {
            if (Thread.currentThread().isInterrupted()) return Optional.empty();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                byte[] bytes = transfer(backends.get(index).read(), null, true,
                    System.nanoTime() + remaining / (backends.size() - index));
                return Optional.of(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (IOException | ExecutionException | TimeoutException | SecurityException unavailable) {
                // A compositor may not expose the needed protocol; try another local desktop provider.
            }
        }
        return Optional.empty();
    }

    @Override public boolean writeText(String text) {
        if (backends.isEmpty()) return false;
        byte[] bytes;
        try { bytes = encode(text); }
        catch (CharacterCodingException invalid) { return false; }
        if (bytes == null) return false;
        long deadline = System.nanoTime() + timeoutNanos;
        for (int index = 0; index < backends.size(); index++) {
            if (Thread.currentThread().isInterrupted()) return false;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                transfer(backends.get(index).write(), bytes, false,
                    System.nanoTime() + remaining / (backends.size() - index));
                return true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException | ExecutionException | TimeoutException | SecurityException unavailable) {
                // Helper status confirms acceptance; failure cannot roll back an external clipboard.
            }
        }
        return false;
    }

    private byte[] transfer(List<String> command, byte[] input, boolean read, long deadline)
        throws IOException, ExecutionException, TimeoutException, InterruptedException {
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        if (!read) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        builder.environment().putAll(environment);
        builder.environment().put("LC_ALL", command.get(0).endsWith("pbcopy") || command.get(0).endsWith("pbpaste")
            ? "en_US.UTF-8" : "C.UTF-8");
        Process process = builder.start();
        boolean accepted = false;
        FutureTask<byte[]> io = null;
        try {
            io = new FutureTask<>(() -> {
                if (read) {
                    process.getOutputStream().close();
                    return readBounded(process.getInputStream());
                }
                try (var stream = process.getOutputStream()) { stream.write(input); }
                return new byte[0];
            });
            Thread worker = new Thread(io, "tuink-clipboard-transfer");
            worker.setDaemon(true);
            worker.start();
            byte[] output = io.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) throw new TimeoutException();
            if (process.exitValue() != 0) throw new IOException("Clipboard helper did not accept the operation");
            accepted = true;
            return output;
        } finally {
            if (!accepted) destroy(process);
            if (io != null) io.cancel(true);
            close(process.getInputStream());
            close(process.getOutputStream());
            close(process.getErrorStream());
        }
    }

    private byte[] readBounded(InputStream stream) throws IOException {
        var bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[Math.min(maxBytes + 1, 8192)];
        for (int count; (count = stream.read(buffer, 0, Math.min(buffer.length, maxBytes - bytes.size() + 1))) != -1;) {
            if (count > maxBytes - bytes.size()) throw new IOException("Clipboard text exceeds its byte budget");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    private byte[] encode(String text) throws CharacterCodingException {
        if (text == null || text.length() > maxBytes) return null;
        long length = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value < 0x80) length++;
            else if (value < 0x800) length += 2;
            else if (Character.isHighSurrogate(value)) {
                if (++index == text.length() || !Character.isLowSurrogate(text.charAt(index))) return null;
                length += 4;
            } else if (Character.isLowSurrogate(value)) return null;
            else length += 3;
            if (length > maxBytes) return null;
        }
        byte[] result = new byte[(int) length];
        var encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer target = ByteBuffer.wrap(result);
        var encoded = encoder.encode(CharBuffer.wrap(text), target, true);
        if (encoded.isError()) encoded.throwException();
        if (!encoded.isUnderflow()) return null;
        var flushed = encoder.flush(target);
        if (flushed.isError()) flushed.throwException();
        if (!flushed.isUnderflow()) return null;
        return result;
    }

    private static List<Backend> detectBackends(String osName, Map<String, String> environment) {
        if (present(environment, "SSH_CONNECTION") || present(environment, "SSH_CLIENT") || present(environment, "SSH_TTY"))
            return List.of();
        String os = osName.toLowerCase(Locale.ROOT);
        List<Backend> result = new ArrayList<>();
        if (os.contains("mac") || os.contains("darwin")) {
            var copy = executable(environment, "pbcopy"); var paste = executable(environment, "pbpaste");
            if (copy != null && paste != null) result.add(new Backend(List.of(paste, "-Prefer", "txt"), List.of(copy)));
        } else if (os.contains("linux")) {
            if (present(environment, "WAYLAND_DISPLAY")) {
                var copy = executable(environment, "wl-copy"); var paste = executable(environment, "wl-paste");
                if (copy != null && paste != null) result.add(new Backend(
                    List.of(paste, "--no-newline", "--type", "text"),
                    List.of(copy, "--type", "text/plain;charset=utf-8")));
            }
            String display = environment.getOrDefault("DISPLAY", "");
            if (display.matches("(?:unix:|unix/:|:)[0-9]+(?:\\.[0-9]+)?")) {
                var xclip = executable(environment, "xclip");
                if (xclip != null) result.add(new Backend(
                    List.of(xclip, "-selection", "clipboard", "-out", "-target", "UTF8_STRING"),
                    List.of(xclip, "-selection", "clipboard", "-in", "-target", "UTF8_STRING", "-silent")));
                var xsel = executable(environment, "xsel");
                if (xsel != null) result.add(new Backend(List.of(xsel, "--clipboard", "--output"),
                    List.of(xsel, "--clipboard", "--input", "--logfile", "/dev/null")));
            }
        }
        return List.copyOf(result);
    }

    private static boolean present(Map<String, String> environment, String name) {
        return !environment.getOrDefault(name, "").isBlank();
    }
    private static String executable(Map<String, String> environment, String name) {
        for (String directory : environment.getOrDefault("PATH", "").split(java.io.File.pathSeparator, -1)) {
            if (directory.isBlank()) continue;
            try {
                Path path = Path.of(directory).resolve(name);
                if (Files.isRegularFile(path) && Files.isExecutable(path)) return path.toAbsolutePath().toString();
            } catch (InvalidPathException | SecurityException unavailable) {
                // An inaccessible PATH entry does not make another entry unusable.
            }
        }
        return null;
    }
    private static long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException();
        return remaining;
    }
    private static void destroy(Process process) {
        try {
            for (ProcessHandle child : process.descendants().toList()) child.destroyForcibly();
        } catch (RuntimeException unavailable) { /* Still terminate the process we started. */ }
        process.destroyForcibly();
        try { process.waitFor(100, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    private static void close(java.io.Closeable stream) {
        try { stream.close(); }
        catch (IOException ignored) { /* Failure is already represented by the operation result. */ }
    }
}
