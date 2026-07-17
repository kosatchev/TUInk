package dev.tuink.tui;

import dev.tuink.tui.style.UiStyles;
import java.nio.file.Path;

/**
 * Parsed command-line options for the TUInk terminal frontend.
 *
 * <p>This is application configuration rather than part of the reusable
 * terminal runtime. Keeping the parser separate makes the frontend entrypoint
 * deterministic and testable without opening a terminal.</p>
 */
record TuiCliOptions(
    Path file,
    Boolean mouseEnabledOverride,
    boolean altScreenEnabled,
    boolean colorEnabled,
    Boolean readModeOverride,
    boolean showHelp,
    UiStyles.Theme themeOverride,
    boolean profileEnabled,
    Path profileFile
) {
    static TuiCliOptions parse(String[] args) {
        if (args == null) {
            throw new IllegalArgumentException("args must not be null");
        }

        Boolean mouseEnabledOverride = null;
        boolean altScreenEnabled = true;
        boolean colorEnabled = true;
        Boolean readModeOverride = null;
        boolean showHelp = false;
        boolean profileEnabled = false;
        Path profileFile = null;
        UiStyles.Theme themeOverride = null;
        Path file = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                throw new IllegalArgumentException("Command-line arguments must not be null");
            }
            switch (arg) {
                case "--no-mouse" -> mouseEnabledOverride = false;
                case "--no-alt-screen" -> altScreenEnabled = false;
                case "--no-color" -> colorEnabled = false;
                case "--read" -> readModeOverride = true;
                case "--profile" -> profileEnabled = true;
                case "--help", "-h" -> showHelp = true;
                default -> {
                    if ("--profile-file".equals(arg)) {
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("Missing value after --profile-file");
                        }
                        profileFile = Path.of(args[++i]);
                        profileEnabled = true;
                        continue;
                    }
                    if (arg.startsWith("--theme=")) {
                        themeOverride = UiStyles.Theme.parse(arg.substring("--theme=".length()));
                        continue;
                    }
                    if (arg.startsWith("--profile-file=")) {
                        profileFile = Path.of(arg.substring("--profile-file=".length()));
                        profileEnabled = true;
                        continue;
                    }
                    if (arg.startsWith("--")) {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                    if (file != null) {
                        throw new IllegalArgumentException("Only one file path is supported.");
                    }
                    file = Path.of(arg);
                }
            }
        }

        return new TuiCliOptions(
            file,
            mouseEnabledOverride,
            altScreenEnabled,
            colorEnabled,
            readModeOverride,
            showHelp,
            themeOverride,
            profileEnabled,
            profileFile
        );
    }
}
