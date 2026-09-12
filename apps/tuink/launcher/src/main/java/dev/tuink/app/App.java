package dev.tuink.app;

import dev.tuink.tui.TerminalEditorApp;

public final class App {
    private App() {
    }

    public static void main(String[] args) throws Exception {
        AppArguments options = AppArguments.parse(args);

        TerminalEditorApp.main(options.forwarded().toArray(String[]::new));
    }
}
