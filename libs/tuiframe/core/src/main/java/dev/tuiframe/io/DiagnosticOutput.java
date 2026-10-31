package dev.tuiframe.io;

import dev.tuiframe.text.TerminalPlainText;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.Objects;

/** Plain terminal diagnostics; terminal rendering uses its own output channel. */
public final class DiagnosticOutput {
    private DiagnosticOutput() {}

    /** External text occupies one record; only this method supplies its line ending. */
    public static void line(PrintStream output, String message) {
        Objects.requireNonNull(output, "output").println(TerminalPlainText.singleLine(message));
    }

    /** Keeps exception structure while escaping messages, causes, suppressed errors and frames. */
    public static void failure(PrintStream output, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        PrintWriter writer = new DiagnosticWriter(Objects.requireNonNull(output, "output"));
        failure.printStackTrace(writer);
        writer.flush();
    }

    /** PrintWriter owns record separators; every text write remains plain diagnostic data. */
    private static final class DiagnosticWriter extends PrintWriter {
        private char pendingHighSurrogate;

        private DiagnosticWriter(PrintStream output) {
            super(output);
        }

        @Override public void write(String text, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, text.length());
            if (length == 0) return;
            synchronized (lock) {
                int end = offset + length;
                if (pendingHighSurrogate != 0) {
                    if (Character.isLowSurrogate(text.charAt(offset))) {
                        String pair = new String(new char[] {pendingHighSurrogate, text.charAt(offset++)});
                        pendingHighSurrogate = 0;
                        super.write(pair, 0, pair.length());
                    } else {
                        flushPendingSurrogate();
                    }
                }
                if (offset < end && Character.isHighSurrogate(text.charAt(end - 1))) {
                    pendingHighSurrogate = text.charAt(--end);
                }
                if (offset < end) {
                    String safe = TerminalPlainText.singleLine(
                        offset == 0 && end == text.length() ? text : text.substring(offset, end));
                    super.write(safe, 0, safe.length());
                }
            }
        }

        @Override public void write(char[] text, int offset, int length) {
            write(new String(text, offset, length));
        }

        @Override public void write(int character) {
            write(String.valueOf((char) character));
        }

        @Override public void println() {
            synchronized (lock) {
                flushPendingSurrogate();
                super.println();
            }
        }

        @Override public void flush() {
            synchronized (lock) {
                flushPendingSurrogate();
                super.flush();
            }
        }

        /** Exception formatters borrow the stream just as ordinary diagnostics do. */
        @Override public void close() {
            flush();
        }

        private void flushPendingSurrogate() {
            if (pendingHighSurrogate == 0) return;
            String safe = TerminalPlainText.singleLine(String.valueOf(pendingHighSurrogate));
            pendingHighSurrogate = 0;
            super.write(safe, 0, safe.length());
        }
    }
}
