package dev.tuink.tui.flow;

import dev.tuink.tui.UiStyles;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public final class AppSettingsStore {
    private static final String FILE_NAME = "tuink.properties";
    private static final String KEY_SHOW_TAB_BAR = "ui.showTabBar";
    private static final String KEY_WRAP_LINES = "ui.wrapLines";
    private static final String KEY_WRAP_WORDS = "ui.wrapWords";
    private static final String KEY_READING_MODE = "ui.readingMode";
    private static final String KEY_CONFIRM_ON_CLOSE = "ui.confirmOnClose";
    private static final String KEY_MOUSE_MODE = "ui.mouseMode";
    private static final String KEY_HIGHLIGHT_ACTIVE_GUTTER = "ui.highlightActiveLineNumber";
    private static final String KEY_SMART_HOME_END = "ui.smartHomeEnd";
    private static final String KEY_VISIBLE_WHITESPACE = "ui.visibleWhitespace";
    private static final String KEY_SYNTAX_HIGHLIGHTING = "ui.syntaxHighlighting";
    private static final String KEY_TAB_WIDTH = "ui.tabWidth";
    private static final String KEY_THEME = "ui.theme";

    private AppSettingsStore() {
    }

    public static Path path() {
        String home = System.getProperty("user.home", ".");
        return Path.of(home, ".config", FILE_NAME);
    }

    public static AppSettings load() throws IOException {
        Path path = path();
        if (!Files.exists(path)) {
            return AppSettings.defaults();
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        }
        AppSettings defaults = AppSettings.defaults();
        return new AppSettings(
            parseBoolean(p.getProperty(KEY_SHOW_TAB_BAR), defaults.showTabBar()),
            parseBoolean(p.getProperty(KEY_WRAP_LINES), defaults.wrapLines()),
            parseBoolean(p.getProperty(KEY_WRAP_WORDS), defaults.wrapWords()),
            parseBoolean(p.getProperty(KEY_READING_MODE), defaults.readingMode()),
            parseBoolean(p.getProperty(KEY_CONFIRM_ON_CLOSE), defaults.confirmOnClose()),
            parseBoolean(p.getProperty(KEY_MOUSE_MODE), defaults.mouseMode()),
            parseBoolean(p.getProperty(KEY_HIGHLIGHT_ACTIVE_GUTTER), defaults.highlightActiveGutter()),
            parseBoolean(p.getProperty(KEY_SMART_HOME_END), defaults.smartHomeEnd()),
            parseBoolean(p.getProperty(KEY_VISIBLE_WHITESPACE), defaults.visibleWhitespace()),
            parseBoolean(p.getProperty(KEY_SYNTAX_HIGHLIGHTING), defaults.syntaxHighlighting()),
            parseTabWidth(p.getProperty(KEY_TAB_WIDTH), defaults.tabWidth()),
            parseTheme(p.getProperty(KEY_THEME), defaults.theme())
        );
    }

    public static void save(AppSettings settings) throws IOException {
        Path path = path();
        Path dir = path.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Properties p = new Properties();
        p.setProperty(KEY_SHOW_TAB_BAR, Boolean.toString(settings.showTabBar()));
        p.setProperty(KEY_WRAP_LINES, Boolean.toString(settings.wrapLines()));
        p.setProperty(KEY_WRAP_WORDS, Boolean.toString(settings.wrapWords()));
        p.setProperty(KEY_READING_MODE, Boolean.toString(settings.readingMode()));
        p.setProperty(KEY_CONFIRM_ON_CLOSE, Boolean.toString(settings.confirmOnClose()));
        p.setProperty(KEY_MOUSE_MODE, Boolean.toString(settings.mouseMode()));
        p.setProperty(KEY_HIGHLIGHT_ACTIVE_GUTTER, Boolean.toString(settings.highlightActiveGutter()));
        p.setProperty(KEY_SMART_HOME_END, Boolean.toString(settings.smartHomeEnd()));
        p.setProperty(KEY_VISIBLE_WHITESPACE, Boolean.toString(settings.visibleWhitespace()));
        p.setProperty(KEY_SYNTAX_HIGHLIGHTING, Boolean.toString(settings.syntaxHighlighting()));
        p.setProperty(KEY_TAB_WIDTH, Integer.toString(settings.tabWidth()));
        p.setProperty(KEY_THEME, settings.theme().name().toLowerCase());

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "TUInk user settings");
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private static UiStyles.Theme parseTheme(String raw, UiStyles.Theme fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase()) {
            case "blue" -> UiStyles.Theme.BLUE;
            case "cyan" -> UiStyles.Theme.CYAN;
            case "light" -> UiStyles.Theme.LIGHT;
            default -> fallback;
        };
    }

    private static int parseTabWidth(String raw, int fallback) {
        int safeFallback = fallback <= 2 ? 2 : 4;
        if (raw == null || raw.isBlank()) {
            return safeFallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value <= 2 ? 2 : 4;
        } catch (NumberFormatException e) {
            return safeFallback;
        }
    }
}
