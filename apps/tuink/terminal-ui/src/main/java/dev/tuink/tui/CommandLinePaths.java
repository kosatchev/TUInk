package dev.tuink.tui;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;

/** Rejects native argv decoding ambiguity before a path can identify a document or log. */
final class CommandLinePaths {
    private CommandLinePaths() {}

    static Path literal(String value, boolean document) {
        if (value.indexOf('\uFFFD') >= 0) {
            throw new IllegalArgumentException("Command-line path contains U+FFFD and may have lost filename bytes. "
                + (document ? "Use the file picker or --file-uri with an ASCII percent-encoded file URI."
                    : "Choose a profile log path without U+FFFD."));
        }
        return Path.of(value);
    }

    static Path fileUri(String value) {
        if (value == null) throw new IllegalArgumentException("Command-line arguments must not be null");
        try {
            if (!value.chars().allMatch(ch -> ch < 128)) {
                throw new IllegalArgumentException();
            }
            URI uri = URI.create(value);
            if (!"file".equals(uri.getScheme()) || uri.isOpaque()
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            Path path = Path.of(uri);
            URI restored = path.toUri();
            if (!path.isAbsolute() || !sameBytes(uri.getRawAuthority(), restored.getRawAuthority(), false)
                || !sameBytes(uri.getRawPath(), restored.getRawPath(), true)) {
                throw new IllegalArgumentException();
            }
            return path;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("--file-uri requires a reversible absolute ASCII file URI "
                + "with percent-encoded filename bytes", invalid);
        }
    }

    private static boolean sameBytes(String first, String second, boolean path) {
        if (first == null || second == null) return first == second;
        byte[] a = bytes(first);
        byte[] b = bytes(second);
        // Path.toUri appends a slash for an existing directory.
        int aLength = path && a.length > 1 && a[a.length - 1] == '/' ? a.length - 1 : a.length;
        int bLength = path && b.length > 1 && b[b.length - 1] == '/' ? b.length - 1 : b.length;
        return Arrays.equals(a, 0, aLength, b, 0, bLength);
    }

    private static byte[] bytes(String raw) {
        byte[] result = new byte[raw.length()];
        int size = 0;
        for (int index = 0; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            if (ch == '%') {
                result[size++] = (byte) ((Character.digit(raw.charAt(index + 1), 16) << 4)
                    | Character.digit(raw.charAt(index + 2), 16));
                index += 2;
            } else {
                result[size++] = (byte) ch;
            }
        }
        return Arrays.copyOf(result, size);
    }
}
