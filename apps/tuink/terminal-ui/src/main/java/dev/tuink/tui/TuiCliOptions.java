package dev.tuink.tui;

import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.tui.style.UiStyles;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Parsed command-line options for the TUInk terminal frontend.
 *
 * <p>This is application configuration rather than part of the reusable
 * terminal runtime. Keeping the parser separate makes the frontend entrypoint
 * deterministic and testable without opening a terminal. The historical
 * {@code file} accessor holds a startup file or directory; path classification
 * happens during application bootstrap, outside this parser.</p>
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
    DocumentOpenMode openMode
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
        DocumentOpenMode openMode = DocumentOpenMode.DETECT;
        String explicitOpenOption = null;

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
                case "--open-as-utf16le" -> {
                    openMode = DocumentOpenMode.UTF_16LE;
                    explicitOpenOption = arg;
                }
                case "--open-as" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value after --open-as");
                    }
                    openMode = parseOpenMode(args[++i]);
                    explicitOpenOption = arg;
                }
                case "--profile" -> profileEnabled = true;
                case "--help", "-h" -> showHelp = true;
                case "--version" -> showVersion = true;
                default -> {
                    if (arg.startsWith("--open-as=")) {
                        openMode = parseOpenMode(arg.substring("--open-as=".length()));
                        explicitOpenOption = "--open-as";
                        continue;
                    }
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

        if (explicitOpenOption != null && file == null && !showHelp && !showVersion) {
            throw new IllegalArgumentException(explicitOpenOption + " requires a file path");
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
            openMode
        );
    }

    private static DocumentOpenMode parseOpenMode(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Command-line arguments must not be null");
        }
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "auto", "detect" -> DocumentOpenMode.DETECT;
            case "utf-8", "utf8" -> DocumentOpenMode.UTF_8;
            case "utf-16le", "utf16le" -> DocumentOpenMode.UTF_16LE;
            case "utf-16be", "utf16be" -> DocumentOpenMode.UTF_16BE;
            case "windows-1251", "cp1251" -> DocumentOpenMode.WINDOWS_1251;
            case "cp866", "ibm866" -> DocumentOpenMode.CP866;
            case "koi8-r", "koi8r" -> DocumentOpenMode.KOI8_R;
            default -> throw new IllegalArgumentException("Unknown opening encoding: " + name
                + ". Expected: auto, utf-8, utf-16le, utf-16be, windows-1251, cp866, koi8-r");
        };
    }
}
