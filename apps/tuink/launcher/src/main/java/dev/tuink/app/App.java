package dev.tuink.app;

import dev.tuink.tui.TerminalEditorApp;
import dev.tuink.tui.TerminalDiagnostics;

public final class App {
    private App() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = run(args);
        } catch (Throwable failure) {
            TerminalDiagnostics.failure(failure);
            exitCode = 1;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) throws Exception {
        AppArguments options;
        try {
            options = AppArguments.parse(args);
        } catch (IllegalArgumentException e) {
            TerminalDiagnostics.error(e.getMessage());
            TerminalDiagnostics.error("Use tuink --help for usage.");
            return 2;
        }
        return TerminalEditorApp.launch(options.forwarded().toArray(String[]::new));
    }
}
