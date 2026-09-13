package dev.tuink.tui.flow;

import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.tui.style.UiStyles;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class AppSettingsStore {
    private static final String FILE_NAME = "tuink.properties";
    private static final String FORMAT = "1";
    private static final String KEY_FORMAT = "format";
    private static final String KEY_SHOW_TAB_BAR = "ui.showTabBar";
    private static final String KEY_WRAP_LINES = "ui.wrapLines";
    private static final String KEY_WRAP_WORDS = "ui.wrapWords";
    private static final String KEY_READING_MODE = "ui.readingMode";
    private static final String KEY_CONFIRM_ON_CLOSE = "ui.confirmOnClose";
    private static final String KEY_MOUSE_MODE = "ui.mouseMode";
    private static final String KEY_HIGHLIGHT_ACTIVE_GUTTER = "ui.highlightActiveLineNumber";
    private static final String KEY_SMART_HOME_END = "ui.smartHomeEnd";
    private static final String KEY_AUTO_INDENT = "ui.autoIndent";
    private static final String KEY_AUTO_PAIRS = "ui.autoPairs";
    private static final String KEY_AUTO_LIST = "ui.autoList";
    private static final String KEY_VISIBLE_WHITESPACE = "ui.visibleWhitespace";
    private static final String KEY_SYNTAX_HIGHLIGHTING = "ui.syntaxHighlighting";
    private static final String KEY_RAINBOW_BRACKETS = "ui.rainbowBrackets";
    private static final String KEY_AUTO_SELECTION_HIGHLIGHT = "ui.autoSelectionHighlight";
    private static final String KEY_GUTTER_DIFF_MODE = "ui.gutterDiffMode";
    private static final String KEY_TAB_WIDTH = "ui.tabWidth";
    private static final String KEY_TAB_INSERT_MODE = "ui.tabInsertMode";
    private static final String KEY_THEME = "ui.theme";
    private static final Set<String> KEYS = Set.of(
        KEY_FORMAT,
        KEY_SHOW_TAB_BAR,
        KEY_WRAP_LINES,
        KEY_WRAP_WORDS,
        KEY_READING_MODE,
        KEY_CONFIRM_ON_CLOSE,
        KEY_MOUSE_MODE,
        KEY_HIGHLIGHT_ACTIVE_GUTTER,
        KEY_SMART_HOME_END,
        KEY_AUTO_INDENT,
        KEY_AUTO_PAIRS,
        KEY_AUTO_LIST,
        KEY_VISIBLE_WHITESPACE,
        KEY_SYNTAX_HIGHLIGHTING,
        KEY_RAINBOW_BRACKETS,
        KEY_AUTO_SELECTION_HIGHLIGHT,
        KEY_GUTTER_DIFF_MODE,
        KEY_TAB_WIDTH,
        KEY_TAB_INSERT_MODE,
        KEY_THEME
    );

    private AppSettingsStore() {
    }

    public static Path path() {
        String home = System.getProperty("user.home", ".");
        return Path.of(home, ".config", "tuink", FILE_NAME);
    }

    public static AppSettings load() throws IOException {
        return load(path());
    }

    static AppSettings load(Path path) throws IOException {
        if (!Files.exists(path)) {
            return AppSettings.defaults();
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed settings file " + path + ": " + malformed.getMessage(), malformed);
        }
        requireExactSchema(p, path);
        return new AppSettings(
            parseBoolean(p, KEY_SHOW_TAB_BAR, path),
            parseBoolean(p, KEY_WRAP_LINES, path),
            parseBoolean(p, KEY_WRAP_WORDS, path),
            parseBoolean(p, KEY_READING_MODE, path),
            parseBoolean(p, KEY_CONFIRM_ON_CLOSE, path),
            parseBoolean(p, KEY_MOUSE_MODE, path),
            parseBoolean(p, KEY_HIGHLIGHT_ACTIVE_GUTTER, path),
            parseBoolean(p, KEY_SMART_HOME_END, path),
            parseBoolean(p, KEY_AUTO_INDENT, path),
            parseBoolean(p, KEY_AUTO_PAIRS, path),
            parseBoolean(p, KEY_AUTO_LIST, path),
            parseBoolean(p, KEY_VISIBLE_WHITESPACE, path),
            parseBoolean(p, KEY_SYNTAX_HIGHLIGHTING, path),
            parseBoolean(p, KEY_RAINBOW_BRACKETS, path),
            parseBoolean(p, KEY_AUTO_SELECTION_HIGHLIGHT, path),
            parseEnum(p, KEY_GUTTER_DIFF_MODE, GutterDiffMode.class, path),
            parseTabWidth(p, path),
            parseEnum(p, KEY_TAB_INSERT_MODE, TabInsertMode.class, path),
            parseEnum(p, KEY_THEME, UiStyles.Theme.class, path)
        );
    }

    public static void save(AppSettings settings) throws IOException {
        save(path(), settings);
    }

    static void save(Path path, AppSettings settings) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        if (target.getFileName() == null) {
            throw new IOException("Settings target must be a file: " + path);
        }
        Path dir = target.getParent();
        Files.createDirectories(dir);
        Properties p = new Properties();
        p.setProperty(KEY_FORMAT, FORMAT);
        p.setProperty(KEY_SHOW_TAB_BAR, Boolean.toString(settings.showTabBar()));
        p.setProperty(KEY_WRAP_LINES, Boolean.toString(settings.wrapLines()));
        p.setProperty(KEY_WRAP_WORDS, Boolean.toString(settings.wrapWords()));
        p.setProperty(KEY_READING_MODE, Boolean.toString(settings.readingMode()));
        p.setProperty(KEY_CONFIRM_ON_CLOSE, Boolean.toString(settings.confirmOnClose()));
        p.setProperty(KEY_MOUSE_MODE, Boolean.toString(settings.mouseMode()));
        p.setProperty(KEY_HIGHLIGHT_ACTIVE_GUTTER, Boolean.toString(settings.highlightActiveGutter()));
        p.setProperty(KEY_SMART_HOME_END, Boolean.toString(settings.smartHomeEnd()));
        p.setProperty(KEY_AUTO_INDENT, Boolean.toString(settings.autoIndent()));
        p.setProperty(KEY_AUTO_PAIRS, Boolean.toString(settings.autoPairs()));
        p.setProperty(KEY_AUTO_LIST, Boolean.toString(settings.autoList()));
        p.setProperty(KEY_VISIBLE_WHITESPACE, Boolean.toString(settings.visibleWhitespace()));
        p.setProperty(KEY_SYNTAX_HIGHLIGHTING, Boolean.toString(settings.syntaxHighlighting()));
        p.setProperty(KEY_RAINBOW_BRACKETS, Boolean.toString(settings.rainbowBrackets()));
        p.setProperty(KEY_AUTO_SELECTION_HIGHLIGHT, Boolean.toString(settings.autoSelectionHighlight()));
        p.setProperty(KEY_GUTTER_DIFF_MODE, canonical(settings.gutterDiffMode()));
        p.setProperty(KEY_TAB_WIDTH, Integer.toString(settings.tabWidth()));
        p.setProperty(KEY_TAB_INSERT_MODE, canonical(settings.tabInsertMode()));
        p.setProperty(KEY_THEME, canonical(settings.theme()));

        Path tmp = Files.createTempFile(dir, "." + target.getFileName() + ".", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "TUInk user settings");
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void requireExactSchema(Properties p, Path path) throws IOException {
        String format = p.getProperty(KEY_FORMAT);
        if (!FORMAT.equals(format)) {
            throw invalid(path, "unsupported or missing format marker");
        }
        if (!p.stringPropertyNames().equals(KEYS)) {
            Set<String> missing = new java.util.HashSet<>(KEYS);
            missing.removeAll(p.stringPropertyNames());
            Set<String> unknown = new java.util.HashSet<>(p.stringPropertyNames());
            unknown.removeAll(KEYS);
            throw invalid(path, "schema mismatch; missing=" + missing + ", unknown=" + unknown);
        }
    }

    private static boolean parseBoolean(Properties p, String key, Path path) throws IOException {
        return switch (p.getProperty(key)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid(path, key + " must be true or false");
        };
    }

    private static int parseTabWidth(Properties p, Path path) throws IOException {
        String raw = p.getProperty(KEY_TAB_WIDTH);
        if ("2".equals(raw)) {
            return 2;
        }
        if ("4".equals(raw)) {
            return 4;
        }
        throw invalid(path, KEY_TAB_WIDTH + " must be 2 or 4");
    }

    private static <E extends Enum<E>> E parseEnum(
        Properties p,
        String key,
        Class<E> enumType,
        Path path
    ) throws IOException {
        String raw = p.getProperty(key);
        for (E value : enumType.getEnumConstants()) {
            if (canonical(value).equals(raw)) {
                return value;
            }
        }
        throw invalid(path, "unknown canonical value for " + key + ": " + raw);
    }

    private static String canonical(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static IOException invalid(Path path, String reason) {
        return new IOException("Unsupported settings file " + path + ": " + reason);
    }
}
