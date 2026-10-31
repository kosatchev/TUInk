package dev.tuink.tui;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Application version generated from the shared Gradle project version. */
public final class AppVersion {
    private AppVersion() {}

    public static String current() {
        try (InputStream input = AppVersion.class.getResourceAsStream("/tuink-version.properties")) {
            if (input == null) return "dev";
            Properties properties = new Properties();
            properties.load(input);
            String version = properties.getProperty("version");
            if (version == null || version.isBlank()) {
                throw new IllegalStateException("Generated TUInk version resource has no version");
            }
            return version.trim();
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to read generated TUInk version resource", failure);
        }
    }
}
