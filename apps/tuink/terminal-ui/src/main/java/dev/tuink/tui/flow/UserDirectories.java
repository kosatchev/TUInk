package dev.tuink.tui.flow;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Resolves TUInk's settings and recovery directory without consulting another profile. */
final class UserDirectories {
    private UserDirectories() {}

    static Path configDirectory() throws IOException {
        return configDirectory(System.getenv("XDG_CONFIG_HOME"), System.getProperty("user.home", "."));
    }

    static Path configDirectory(String configuredHome, String userHome) throws IOException {
        if (configuredHome != null && !configuredHome.isEmpty()) {
            try {
                Path configured = Path.of(configuredHome);
                // XDG paths must be absolute; preserve symlink/.. resolution by the filesystem.
                if (configured.isAbsolute()) {
                    requireUnambiguous(configuredHome, "XDG_CONFIG_HOME");
                    return configured.resolve("tuink");
                }
            } catch (InvalidPathException ignored) {
                // Invalid environment values use the same default as an unset variable.
            }
        }
        requireUnambiguous(userHome, "user.home");
        try {
            return Path.of(userHome, ".config", "tuink");
        } catch (InvalidPathException invalid) {
            throw new IOException("Cannot use user.home for settings and recovery. "
                + "Set XDG_CONFIG_HOME to a valid absolute directory before starting TUInk.", invalid);
        }
    }

    private static void requireUnambiguous(String value, String source) throws IOException {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\uFFFD') {
                throw ambiguous(source, "contains U+FFFD and may have lost filename bytes");
            }
            if (Character.isHighSurrogate(current)) {
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                    i++;
                } else {
                    throw ambiguous(source, "contains an unpaired Unicode surrogate");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw ambiguous(source, "contains an unpaired Unicode surrogate");
            }
        }
    }

    private static IOException ambiguous(String source, String reason) {
        return new IOException(source + " " + reason + ". "
            + "Set XDG_CONFIG_HOME to an unambiguous absolute directory before starting TUInk. "
            + "No settings or recovery profile was selected.");
    }
}
