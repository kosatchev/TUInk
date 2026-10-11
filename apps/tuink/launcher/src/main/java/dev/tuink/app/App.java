package dev.tuink.app;

import dev.tuink.tui.TerminalEditorApp;

public final class App {
    private App() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) throws Exception {
        AppArguments options;
        try {
            options = AppArguments.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Use tuink --help for usage.");
            return 2;
        }
        return TerminalEditorApp.launch(options.forwarded().toArray(String[]::new));
    }
}
