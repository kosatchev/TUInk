package dev.tuink.tui;

import dev.tuiframe.io.DiagnosticOutput;
import java.io.PrintStream;

/** Application diagnostic boundary shared by both command-line entry points. */
public final class TerminalDiagnostics {
    private TerminalDiagnostics() {}

    public static void line(PrintStream output, String message) {
        DiagnosticOutput.line(output, message);
    }

    public static void error(String message) {
        line(System.err, message);
    }

    public static void failure(Throwable failure) {
        DiagnosticOutput.failure(System.err, failure);
    }
}
