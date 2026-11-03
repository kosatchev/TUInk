package dev.tuink.tui.flow;

import dev.tuink.core.GutterDiffMode;
import dev.tuink.storage.jvm.FileSaveGuard;
import dev.tuink.storage.jvm.JvmFilePaths;
import dev.tuink.core.TabInsertMode;
import dev.tuink.tui.style.UiStyles;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

public final class AppSettingsStore {
    private static final String FILE_NAME = "tuink.properties";
    private static final int MAX_SETTINGS_BYTES = 64 * 1024;
    private static final String FORMAT = "2";
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
    private static final String KEY_MARKDOWN_OPEN_BOTH_TABS = "ui.markdownOpenBothTabs";
    private static final String KEY_MARKDOWN_OPEN_IN_PREVIEW = "ui.markdownOpenInPreview";
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
        KEY_MARKDOWN_OPEN_BOTH_TABS,
        KEY_MARKDOWN_OPEN_IN_PREVIEW,
        KEY_GUTTER_DIFF_MODE,
        KEY_TAB_WIDTH,
        KEY_TAB_INSERT_MODE,
        KEY_THEME
    );

    private AppSettingsStore() {
    }

    public static Path path() throws IOException {
        return UserDirectories.configDirectory().resolve(FILE_NAME);
    }

    public static AppSettings load() throws IOException {
        return load(path());
    }

    static AppSettings load(Path path) throws IOException {
        return loadSnapshot(path).settings();
    }

    /** Loads settings and an opaque baseline for an automatic session save. */
    public static LoadedSettings loadSnapshot() throws IOException {
        return loadSnapshot(path());
    }

    static LoadedSettings loadSnapshot(Path path) throws IOException {
        Path requested = path.toAbsolutePath();
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(requested, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return new LoadedSettings(AppSettings.defaults(), requested, resolveMissingTarget(requested), null);
        }
        Path target = requireRegularTarget(requested, attributes).toRealPath();
        byte[] bytes = readSettingsBytes(target);
        return new LoadedSettings(parseSettings(bytes, requested), requested, target, fingerprint(bytes));
    }

    private static byte[] readSettingsBytes(Path path) throws IOException {
        requireSettingsSize(path, Files.size(path));
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_SETTINGS_BYTES + 1);
            requireSettingsSize(path, bytes.length);
            return bytes;
        }
    }

    private static void requireSettingsSize(Path path, long size) throws IOException {
        if (size > MAX_SETTINGS_BYTES) {
            throw new IOException("Settings file is too large: " + path
                + ". Maximum size is 64 KiB; the file was left unchanged.");
        }
    }

    private static AppSettings parseSettings(byte[] bytes, Path path) throws IOException {
        Properties p = new Properties();
        try (var input = new ByteArrayInputStream(bytes)) {
            p.load(input);
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
            parseBoolean(p, KEY_MARKDOWN_OPEN_BOTH_TABS, path),
            parseBoolean(p, KEY_MARKDOWN_OPEN_IN_PREVIEW, path),
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
        save(path, settings, null);
    }

    /** Preserves a settings file changed since this session loaded it. */
    public static void saveIfUnchanged(LoadedSettings loaded, AppSettings settings) throws IOException {
        Objects.requireNonNull(loaded, "loaded");
        save(loaded.requested, settings, loaded);
    }

    private static void save(Path path, AppSettings settings, LoadedSettings loaded) throws IOException {
        Objects.requireNonNull(settings, "settings");
        Path target = loaded == null ? path.toAbsolutePath() : requireUnchanged(loaded);
        if (target.getFileName() == null || target.getFileName().toString().equals(".")
            || target.getFileName().toString().equals("..")) {
            throw new IOException("Settings target must be a file: " + path);
        }
        Files.createDirectories(target.getParent());
        target = JvmFilePaths.resolveEntry(target);
        BasicFileAttributes attributes = null;
        try {
            attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            // A new regular settings file may be created at this leaf.
        }
        if (attributes != null) {
            target = requireRegularTarget(target, attributes).toRealPath();
        }
        Path dir = target.getParent();
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
        p.setProperty(KEY_MARKDOWN_OPEN_BOTH_TABS, Boolean.toString(settings.markdownOpenBothTabs()));
        p.setProperty(KEY_MARKDOWN_OPEN_IN_PREVIEW, Boolean.toString(settings.markdownOpenInPreview()));
        p.setProperty(KEY_GUTTER_DIFF_MODE, canonical(settings.gutterDiffMode()));
        p.setProperty(KEY_TAB_WIDTH, Integer.toString(settings.tabWidth()));
        p.setProperty(KEY_TAB_INSERT_MODE, canonical(settings.tabInsertMode()));
        p.setProperty(KEY_THEME, canonical(settings.theme()));

        Path tmp = Files.createTempFile(dir, ".tuink-settings.", ".tmp");
        FileSaveGuard guard = null;
        Throwable failure = null;
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "TUInk user settings");
            }
            try {
                guard = FileSaveGuard.acquire(target);
            } catch (IOException unavailable) {
                if (loaded != null) throw new SettingsConflictException(loaded.requested, unavailable);
                throw unavailable;
            }
            publishSettings(tmp, path.toAbsolutePath(), target, loaded);
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            if (guard != null) {
                try {
                    guard.close();
                } catch (IOException cleanupFailure) {
                    if (failure != null) failure.addSuppressed(cleanupFailure);
                    // Publication already completed when there is no primary
                    // failure. Releasing the guard must not turn it into a failed save.
                }
            }
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanupFailure) {
                if (failure != null) {
                    failure.addSuppressed(cleanupFailure);
                }
                // A committed settings file must not be reported as a failed save.
            }
        }
    }

    private static void publishSettings(Path staged, Path requested, Path target, LoadedSettings loaded) throws IOException {
        requireSameTarget(requested, target, loaded);
        if (loaded != null && loaded.fingerprint == null) {
            try {
                publishNewSettings(staged, target);
            } catch (FileAlreadyExistsException appeared) {
                throw new SettingsConflictException(loaded.requested, appeared);
            }
        } else {
            try {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                requireSameTarget(requested, target, loaded);
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void requireSameTarget(Path requested, Path target, LoadedSettings loaded) throws IOException {
        if (loaded != null) {
            // The guard's inode survives settings replacement. Revalidate while
            // holding it, and keep it until publication has finished.
            if (!target.equals(requireUnchanged(loaded))) {
                throw new SettingsConflictException(loaded.requested, null);
            }
        } else {
            Path current = JvmFilePaths.resolveEntry(requested);
            try {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
                current = requireRegularTarget(current, attributes).toRealPath();
            } catch (NoSuchFileException missing) {
                if (Files.isSymbolicLink(current)) throw missing;
            }
            if (!target.equals(current)) {
                throw new IOException("Settings target changed while saving: " + requested);
            }
        }
    }

    private static Path requireUnchanged(LoadedSettings loaded) throws SettingsConflictException {
        try {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(loaded.requested, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                if (loaded.fingerprint == null && loaded.target.equals(resolveMissingTarget(loaded.requested))) {
                    return loaded.target;
                }
                throw missing;
            }
            if (loaded.fingerprint != null) {
                Path target = requireRegularTarget(loaded.requested, attributes).toRealPath();
                if (loaded.target.equals(target)
                    && Arrays.equals(loaded.fingerprint, fingerprint(target))) {
                    return target;
                }
            }
        } catch (IOException error) {
            throw new SettingsConflictException(loaded.requested, error);
        }
        throw new SettingsConflictException(loaded.requested, null);
    }

    /** Resolves existing ancestors without creating missing configuration directories. */
    private static Path resolveMissingTarget(Path path) throws IOException {
        try {
            return path.toRealPath();
        } catch (NoSuchFileException missing) {
            if (Files.isSymbolicLink(path) || path.getParent() == null) {
                throw missing;
            }
            return resolveMissingTarget(path.getParent()).resolve(path.getFileName());
        }
    }

    private static byte[] fingerprint(byte[] bytes) {
        return sha256().digest(bytes);
    }

    private static byte[] fingerprint(Path path) throws IOException {
        requireSettingsSize(path, Files.size(path));
        MessageDigest digest = sha256();
        byte[] buffer = new byte[8192];
        int total = 0;
        try (var input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer, 0, Math.min(buffer.length, MAX_SETTINGS_BYTES - total + 1))) >= 0) {
                total += count;
                requireSettingsSize(path, total);
                digest.update(buffer, 0, count);
            }
        }
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("The Java runtime must provide SHA-256", impossible);
        }
    }

    private static void publishNewSettings(Path staged, Path target) throws IOException {
        Throwable linkFailure;
        try {
            Files.createLink(target, staged);
            return;
        } catch (FileAlreadyExistsException appeared) {
            throw appeared;
        } catch (IOException | UnsupportedOperationException failure) {
            linkFailure = failure;
        }

        SeekableByteChannel channel;
        try {
            channel = createNewSettingsChannel(target);
        } catch (IOException failure) {
            failure.addSuppressed(linkFailure);
            throw failure;
        }
        try (var output = Channels.newOutputStream(channel)) {
            Files.copy(staged, output);
        } catch (IOException failure) {
            // Another writer may have replaced the name after the exclusive open.
            // Keep that path rather than risk deleting another writer's file.
            IOException incomplete = new IOException(
                "Saving new settings failed: " + target
                    + ". The file remains at this path and may contain incomplete data.", failure
            );
            incomplete.addSuppressed(linkFailure);
            throw incomplete;
        }
    }

    private static SeekableByteChannel createNewSettingsChannel(Path target) throws IOException {
        Set<StandardOpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            return Files.newByteChannel(target, options, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")
            ));
        } catch (UnsupportedOperationException unsupportedAttributes) {
            return Files.newByteChannel(target, options);
        }
    }

    /** Immutable startup settings and private filesystem baseline. */
    public static final class LoadedSettings {
        private final AppSettings settings;
        private final Path requested;
        private final Path target;
        private final byte[] fingerprint;

        private LoadedSettings(AppSettings settings, Path requested, Path target, byte[] fingerprint) {
            this.settings = settings;
            this.requested = requested;
            this.target = target;
            this.fingerprint = fingerprint;
        }

        public AppSettings settings() {
            return settings;
        }
    }

    public static final class SettingsConflictException extends IOException {
        private static final long serialVersionUID = 1L;

        private SettingsConflictException(Path path, IOException cause) {
            super("Settings file changed, is being saved by another session or is no longer accessible: " + path
                + ". The file was not overwritten; menu preferences from this session were not saved.", cause);
        }
    }

    private static Path requireRegularTarget(Path path, BasicFileAttributes attributes) throws IOException {
        Path target = path;
        if (attributes.isSymbolicLink()) {
            try {
                target = path.toRealPath();
            } catch (NoSuchFileException missing) {
                throw new IOException("Settings symlink target does not exist: " + path, missing);
            }
            attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        if (!attributes.isRegularFile()) {
            throw new IOException("Settings target must be a regular file: " + path);
        }
        return target;
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
