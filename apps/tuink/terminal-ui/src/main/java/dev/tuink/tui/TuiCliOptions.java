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
    boolean showVersion,
    UiStyles.Theme themeOverride,
    boolean profileEnabled,
    Path profileFile,
    boolean openAsUtf16Le
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
        boolean showVersion = false;
        boolean profileEnabled = false;
        Path profileFile = null;
        UiStyles.Theme themeOverride = null;
        Path file = null;
        boolean endOfOptions = false;
        boolean openAsUtf16Le = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                throw new IllegalArgumentException("Command-line arguments must not be null");
            }
            if (endOfOptions) {
                if (file != null) {
                    throw new IllegalArgumentException("Only one file path is supported.");
                }
                file = CommandLinePaths.literal(arg, true);
                continue;
            }
            switch (arg) {
                case "--" -> endOfOptions = true;
                case "--no-mouse" -> mouseEnabledOverride = false;
                case "--no-alt-screen" -> altScreenEnabled = false;
                case "--no-color" -> colorEnabled = false;
                case "--read" -> readModeOverride = true;
                case "--open-as-utf16le" -> openAsUtf16Le = true;
                case "--profile" -> profileEnabled = true;
                case "--help", "-h" -> showHelp = true;
                case "--version" -> showVersion = true;
                default -> {
                    if ("--file-uri".equals(arg) || arg.startsWith("--file-uri=")) {
                        if (file != null) {
                            throw new IllegalArgumentException("Only one file path is supported.");
                        }
                        if ("--file-uri".equals(arg) && i + 1 >= args.length) {
                            throw new IllegalArgumentException("Missing value after --file-uri");
                        }
                        String value = "--file-uri".equals(arg) ? args[++i] : arg.substring("--file-uri=".length());
                        file = CommandLinePaths.fileUri(value);
                        continue;
                    }
                    if ("--profile-file".equals(arg)) {
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("Missing value after --profile-file");
                        }
                        if (args[i + 1] == null) {
                            throw new IllegalArgumentException("Command-line arguments must not be null");
                        }
                        profileFile = CommandLinePaths.literal(args[++i], false);
                        profileEnabled = true;
                        continue;
                    }
                    if (arg.startsWith("--theme=")) {
                        themeOverride = UiStyles.Theme.parse(arg.substring("--theme=".length()));
                        continue;
                    }
                    if (arg.startsWith("--profile-file=")) {
                        profileFile = CommandLinePaths.literal(arg.substring("--profile-file=".length()), false);
                        profileEnabled = true;
                        continue;
                    }
                    if (arg.startsWith("--")) {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                    if (file != null) {
                        throw new IllegalArgumentException("Only one file path is supported.");
                    }
                    file = CommandLinePaths.literal(arg, true);
                }
            }
        }

        if (openAsUtf16Le && file == null && !showHelp && !showVersion) {
            throw new IllegalArgumentException("--open-as-utf16le requires a file path");
        }
        return new TuiCliOptions(
            file,
            mouseEnabledOverride,
            altScreenEnabled,
            colorEnabled,
            readModeOverride,
            showHelp,
            showVersion,
            themeOverride,
            profileEnabled,
            profileFile,
            openAsUtf16Le
        );
    }
}
