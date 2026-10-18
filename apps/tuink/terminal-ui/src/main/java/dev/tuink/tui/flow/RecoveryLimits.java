package dev.tuink.tui.flow;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Hard storage limits shared by snapshot producers and consumers, separate from heap admission. */
final class RecoveryLimits {
    static final int MAX_CODE_UNITS = 64 * 1024 * 1024;
    // One well-formed UTF-16 code unit needs at most three UTF-8 bytes.
    static final int MAX_TEXT_BYTES = 3 * MAX_CODE_UNITS;
    static final int MAX_METADATA_BYTES = 8 * 1024 * 1024;
    // Old writers could exceed their reader's 8 MiB limit with one CSV token per separator.
    static final int MAX_LEGACY_METADATA_BYTES = 24 * 1024 * 1024;

    private RecoveryLimits() {}

    static void requireDocument(List<String> lines) throws IOException {
        long units = lines.size() - 1L;
        for (String line : lines) {
            units += line.length();
            requireSize(units, MAX_CODE_UNITS, "Recovery document exceeds 64 Mi UTF-16 code units");
        }
    }

    static void requireSize(long size, long limit, String reason) throws IOException {
        if (size > limit) throw new IOException(reason + "; snapshot left unchanged");
    }

    static InputStream openText(Path path) throws IOException {
        String reason = "Recovery text exceeds its storage limit: " + path;
        requireSize(Files.size(path), MAX_TEXT_BYTES, reason);
        return new FilterInputStream(Files.newInputStream(path)) {
            private long count;

            @Override public int read() throws IOException {
                int value = in.read();
                if (value >= 0) requireSize(++count, MAX_TEXT_BYTES, reason);
                return value;
            }

            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int read = in.read(bytes, offset, (int) Math.min(length, MAX_TEXT_BYTES - count + 1));
                if (read > 0) {
                    count += read;
                    requireSize(count, MAX_TEXT_BYTES, reason);
                }
                return read;
            }
        };
    }

    static OutputStream boundedOutput(OutputStream output, long limit, String reason) {
        return new FilterOutputStream(output) {
            private long count;

            @Override public void write(int value) throws IOException {
                requireSize(count + 1, limit, reason);
                out.write(value);
                count++;
            }

            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                requireSize(count + length, limit, reason);
                out.write(bytes, offset, length);
                count += length;
            }
        };
    }
}
