package dev.tuink.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

record AppArguments(UiTarget ui, List<String> forwarded) {
    AppArguments {
        forwarded = List.copyOf(forwarded);
    }

    static AppArguments parse(String[] args) {
        if (args == null) {
            throw new IllegalArgumentException("args must not be null");
        }
        for (String arg : args) {
            if (arg == null) {
                throw new IllegalArgumentException("Command-line arguments must not be null");
            }
        }
        UiTarget ui = UiTarget.TUI;
        List<String> forwarded = new ArrayList<>(args.length);
        boolean endOfOptions = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (endOfOptions) {
                forwarded.add(arg);
                continue;
            }
            if ("--".equals(arg)) {
                endOfOptions = true;
                forwarded.add(arg);
                continue;
            }
            if ("--profile-file".equals(arg) || "--file-uri".equals(arg) || "--open-as".equals(arg)) {
                forwarded.add(arg);
                if (i + 1 < args.length) {
                    forwarded.add(args[++i]);
                }
                continue;
            }
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
        return new AppArguments(ui, forwarded);
    }

    enum UiTarget {
        TUI;

        private static UiTarget parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return TUI;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "tui" -> TUI;
                default -> throw new IllegalArgumentException(
                    "Unknown UI target: " + raw + ". Expected: tui"
                );
            };
        }
    }
}
