package dev.tuink.app;

import dev.tuink.tui.TerminalEditorApp;

public final class App {
    private App() {
    }

    public static void main(String[] args) throws Exception {
        AppArguments options = AppArguments.parse(args);

        switch (options.ui()) {
            case TUI -> TerminalEditorApp.main(options.forwarded().toArray(String[]::new));
            case GUI -> System.out.println(
                "UI 'gui' is not implemented yet. Use --ui=tui for now."
            );
        }
    }
}
