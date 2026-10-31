package dev.tuiframe.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DiagnosticOutputTest {
    @Test
    void lineEmitsEveryC0DelAndC1ControlAsVisibleTextInOnePhysicalRecord() {
        StringBuilder raw = new StringBuilder("message: ");
        StringBuilder expected = new StringBuilder("message: ");
        for (int codePoint = 0; codePoint <= 0x9F; codePoint++) {
            if (codePoint > 0x1F && codePoint < 0x7F) continue;
            raw.append((char) codePoint);
            expected.append(switch (codePoint) {
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                default -> String.format(Locale.ROOT, "\\u%04X", codePoint);
            });
        }

        String output = capture(stream -> DiagnosticOutput.line(stream, raw.toString()));

        assertEquals(expected + System.lineSeparator(), output);
        assertEquals(1, physicalRecords(output).length);
        assertSafeRecords(output);
    }

    @Test
    void repeatedOutputPreservesUnicodeLiteralBackslashesAndEscapedPayloads() {
        String unicode = "Привет 中 😀 👩‍💻 é العربية עברית";
        String message = unicode + " \\u001B \\n C:\\notes\\file.txt \u001B]0;title\u0007\r\n";
        String expected = unicode + " \\u001B \\n C:\\notes\\file.txt \\u001B]0;title\\u0007\\r\\n";
        String output = capture(stream -> {
            DiagnosticOutput.line(stream, message);
            DiagnosticOutput.line(stream, expected);
            DiagnosticOutput.line(stream, null);
        });

        assertEquals(expected + System.lineSeparator() + expected + System.lineSeparator()
            + System.lineSeparator(), output);
        assertEquals(3, physicalRecords(output).length);
        assertSafeRecords(output);
    }

    @Test
    void failurePreservesCauseSuppressedFramesAndCircularReferenceStructure() {
        Exception failure = new Exception("root\u001B]0;title\u0007\r\nforged");
        Exception cause = new Exception("cause\u009B2J\u202Etext");
        Exception suppressed = new Exception("suppressed\u009D52;c;text\u009C\u2028row");
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("example.Root", "run", "Root\u001B.java", 12)
        });
        cause.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("example.Cause", "load", "Cause\r\nFile.java", 34)
        });
        suppressed.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("example.Suppressed", "close", "Suppressed\u009B.java", 56)
        });
        failure.initCause(cause);
        cause.initCause(failure);
        failure.addSuppressed(suppressed);

        String output = capture(stream -> DiagnosticOutput.failure(stream, failure));

        String[] records = physicalRecords(output);
        assertEquals(7, records.length, output);
        assertEquals("java.lang.Exception: root\\u001B]0;title\\u0007\\r\\nforged", records[0]);
        assertTrue(output.contains("\\tat example.Root.run(Root\\u001B.java:12)"), output);
        assertTrue(output.contains("\\tSuppressed: java.lang.Exception: suppressed\\u009D52;c;text\\u009C\\u2028row"), output);
        assertTrue(output.contains("\\t\\tat example.Suppressed.close(Suppressed\\u009B.java:56)"), output);
        assertTrue(output.contains("Caused by: java.lang.Exception: cause\\u009B2J\\u202Etext"), output);
        assertTrue(output.contains("\\tat example.Cause.load(Cause\\r\\nFile.java:34)"), output);
        assertTrue(output.contains("[CIRCULAR REFERENCE: java.lang.Exception: root\\u001B]0;title\\u0007\\r\\nforged]"), output);
        assertSafeRecords(output);
    }

    @Test
    void customStackTraceCannotBypassEscapingWithStringArrayOrCharacterWrites() {
        Throwable failure = new Throwable() {
            @Override public void printStackTrace(PrintWriter output) {
                String payload = "\u001B[31m界\u001B[0m";
                output.write("[" + payload + "]", 1, payload.length());
                output.write(new char[] {'!', '\u009D', '0', ';', '中', '\u009C', '!'}, 1, 5);
                output.write('\u001B');
                output.write('\r');
                output.write('\n');
                output.print(" literal \\u001B 👩‍💻é");
                output.println();
            }
        };

        String output = capture(stream -> DiagnosticOutput.failure(stream, failure));

        assertEquals("\\u001B[31m界\\u001B[0m\\u009D0;中\\u009C\\u001B\\r\\n literal \\u001B 👩‍💻é"
            + System.lineSeparator(), output);
        assertEquals(1, physicalRecords(output).length);
        assertSafeRecords(output);
    }

    @Test
    void supplementaryUnicodeSurvivesCustomWritesSplitAcrossSurrogates() {
        Throwable failure = new Throwable() {
            @Override public void printStackTrace(PrintWriter output) {
                output.write('\uD83D');
                output.write(new char[] {'\uDE00', '-'}, 0, 2);
                output.write("[x\uD83D]", 1, 2);
                output.write("\uDE00y");
                output.println();
            }
        };

        assertEquals("😀-x😀y" + System.lineSeparator(),
            capture(stream -> DiagnosticOutput.failure(stream, failure)));
    }

    @Test
    void structuralLineEndingsAndFlushFinishUnpairedSurrogates() {
        Throwable failure = new Throwable() {
            @Override public void printStackTrace(PrintWriter output) {
                output.write('\uD83D');
                output.println();
                output.write('\uDE00');
                output.println();
                output.write('\uD800');
            }
        };

        assertEquals("\\uD83D" + System.lineSeparator() + "\\uDE00" + System.lineSeparator() + "\\uD800",
            capture(stream -> DiagnosticOutput.failure(stream, failure)));
    }

    @Test
    void diagnosticsFlushButNeverCloseTheBorrowedStream() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TrackedPrintStream output = new TrackedPrintStream(bytes)) {
            Exception failure = new Exception("failure\u001B");
            failure.setStackTrace(new StackTraceElement[0]);

            DiagnosticOutput.line(output, "first\r\nrecord");
            DiagnosticOutput.failure(output, failure);

            assertFalse(output.closed);
            assertEquals("first\\r\\nrecord" + System.lineSeparator()
                + "java.lang.Exception: failure\\u001B" + System.lineSeparator(), bytes.toString(StandardCharsets.UTF_8));
            output.println("still usable");
            assertTrue(bytes.toString(StandardCharsets.UTF_8).endsWith("still usable" + System.lineSeparator()));
        }
    }

    @Test
    void customStackTraceCannotCloseTheBorrowedStreamThroughItsWriter() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TrackedPrintStream output = new TrackedPrintStream(bytes)) {
            Throwable failure = new Throwable() {
                @Override public void printStackTrace(PrintWriter writer) {
                    writer.println("custom\u001B");
                    writer.close();
                }
            };

            DiagnosticOutput.failure(output, failure);

            assertFalse(output.closed);
            DiagnosticOutput.line(output, "next diagnostic");
            assertEquals("custom\\u001B" + System.lineSeparator() + "next diagnostic"
                + System.lineSeparator(), bytes.toString(StandardCharsets.UTF_8));
        }
    }

    private static String capture(Consumer<PrintStream> write) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            write.accept(output);
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static String[] physicalRecords(String output) {
        assertTrue(output.endsWith(System.lineSeparator()), output);
        String body = output.substring(0, output.length() - System.lineSeparator().length());
        return body.split(Pattern.quote(System.lineSeparator()), -1);
    }

    private static void assertSafeRecords(String output) {
        for (String record : physicalRecords(output)) {
            assertTrue(record.codePoints().noneMatch(Character::isISOControl), record);
            assertFalse(record.contains("\u2028"));
            assertFalse(record.contains("\u2029"));
            assertFalse(record.contains("\u202E"));
        }
    }

    private static final class TrackedPrintStream extends PrintStream {
        private boolean closed;

        private TrackedPrintStream(ByteArrayOutputStream output) {
            super(output, true, StandardCharsets.UTF_8);
        }

        @Override public void close() {
            closed = true;
            super.close();
        }
    }
}
