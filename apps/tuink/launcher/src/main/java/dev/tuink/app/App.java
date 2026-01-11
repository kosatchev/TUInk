package dev.tuink.app;

import dev.tuink.tui.TerminalEditorApp;
import java.util.ArrayList;
import java.util.List;

public final class App {
    private App() {
    }

    private enum UiTarget {
        TUI,
        GUI;

        private static UiTarget parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return TUI;
            }
            return switch (raw.trim().toLowerCase()) {
                case "tui" -> TUI;
                case "gui" -> GUI;
                default -> throw new IllegalArgumentException(
                    "Unknown UI target: " + raw + ". Expected: tui, gui"
                );
            };
        }
    }

    public static void main(String[] args) throws Exception {
        UiTarget ui = UiTarget.TUI;
        List<String> forwarded = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--ui=")) {
                ui = UiTarget.parse(arg.substring("--ui=".length()));
                continue;
            }
            if ("--ui".equals(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value after --ui");
                }
                ui = UiTarget.parse(args[++i]);
                continue;
            }
            forwarded.add(arg);
        }

        switch (ui) {
            case TUI -> TerminalEditorApp.main(forwarded.toArray(String[]::new));
            case GUI -> System.out.println(
                "UI 'gui' is not implemented yet. Use --ui=tui for now."
            );
        }
    }
}
