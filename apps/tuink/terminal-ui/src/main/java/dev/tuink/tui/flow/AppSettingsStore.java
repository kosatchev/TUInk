package dev.tuink.tui.flow;

import dev.tuink.core.GutterDiffMode;
import dev.tuink.storage.jvm.FileSaveGuard;
import dev.tuink.storage.jvm.JvmFilePaths;
import dev.tuink.core.TabInsertMode;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.project.search.SearchPreferences;
import dev.tuink.editor.storage.DocumentOpenMode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
    private static final String KEY_DOCUMENT_PATH = "ui.showDocumentPath";
    private static final String KEY_WRAP_LINES = "ui.wrapLines";
    private static final String KEY_WRAP_WORDS = "ui.wrapWords";
    private static final String KEY_READING_MODE = "ui.readingMode";
    private static final String KEY_CONFIRM_ON_CLOSE = "ui.confirmOnClose";
    private static final String KEY_MOUSE_MODE = "ui.mouseMode";
    private static final String KEY_RIGHT_CLICK_CLIPBOARD = "ui.rightClickClipboard";
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
    private static final String KEY_MARKDOWN_CODE_HIGHLIGHTING = "ui.markdownCodeHighlighting";
    private static final String KEY_MARKDOWN_VISUAL_COPY = "ui.markdownVisualCopy";
    private static final String KEY_AUTO_RELOAD_UNMODIFIED = "ui.autoReloadUnmodifiedFiles";
    private static final String KEY_DRAG_SELECTED_TEXT = "ui.dragSelectedText";
    private static final String KEY_FILE_HISTORY_NOTIFICATIONS = "ui.fileHistoryNotifications";
    private static final String KEY_GUTTER_DIFF_MODE = "ui.gutterDiffMode";
    private static final String KEY_TAB_WIDTH = "ui.tabWidth";
    private static final String KEY_TAB_INSERT_MODE = "ui.tabInsertMode";
    private static final String KEY_THEME = "ui.theme";
    private static final String SEARCH = "search.";
    private static final String PALETTES = "palettes.";
    private static final Set<String> PALETTE_KEYS = Set.of("width", "filesHeight", "searchHeight", "filesExpanded",
        "searchExpanded", "searchFirst", "maximized", "visible", "filesHints", "searchHints");
    private static final Set<String> SEARCH_KEYS = Set.of("automatic", "respectIgnore", "hidden", "exclusions",
        "encoding", "maximumFileMiB", "maximumMatches");
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
        byte[] originalFingerprint = fingerprint(bytes);
        Properties properties = null;
        try {
            properties = parseProperties(bytes, requested);
            return new LoadedSettings(parseSettings(properties, requested, false), requested, target, originalFingerprint);
        } catch (IOException unsupported) {
            AppSettings repaired = properties != null && FORMAT.equals(properties.getProperty(KEY_FORMAT))
                ? parseSettings(properties, requested, true) : AppSettings.defaults();
            throw new UnsupportedSettingsException(unsupported,
                new LoadedSettings(repaired, requested, target, originalFingerprint));
        }
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

    private static Properties parseProperties(byte[] bytes, Path path) throws IOException {
        Properties p = new Properties();
        try (var input = new ByteArrayInputStream(bytes)) {
            p.load(input);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed settings file " + path + ": " + malformed.getMessage(), malformed);
        }
        return p;
    }

    /** Strict loads and explicit repair share the same field readers and validation rules. */
    private static AppSettings parseSettings(Properties p, Path path, boolean repair) throws IOException {
        if (!repair) requireExactSchema(p, path);
        AppSettings defaults = AppSettings.defaults();
        return new AppSettings(
            setting(repair, defaults.showTabBar(), () -> parseBoolean(p, KEY_SHOW_TAB_BAR, path)),
            setting(repair, defaults.wrapLines(), () -> parseBoolean(p, KEY_WRAP_LINES, path)),
            setting(repair, defaults.wrapWords(), () -> parseBoolean(p, KEY_WRAP_WORDS, path)),
            setting(repair, defaults.readingMode(), () -> parseBoolean(p, KEY_READING_MODE, path)),
            setting(repair, defaults.confirmOnClose(), () -> parseBoolean(p, KEY_CONFIRM_ON_CLOSE, path)),
            setting(repair, defaults.mouseMode(), () -> parseBoolean(p, KEY_MOUSE_MODE, path)),
            setting(repair, defaults.highlightActiveGutter(), () -> parseBoolean(p, KEY_HIGHLIGHT_ACTIVE_GUTTER, path)),
            setting(repair, defaults.smartHomeEnd(), () -> parseBoolean(p, KEY_SMART_HOME_END, path)),
            setting(repair, defaults.autoIndent(), () -> parseBoolean(p, KEY_AUTO_INDENT, path)),
            setting(repair, defaults.autoPairs(), () -> parseBoolean(p, KEY_AUTO_PAIRS, path)),
            setting(repair, defaults.autoList(), () -> parseBoolean(p, KEY_AUTO_LIST, path)),
            setting(repair, defaults.visibleWhitespace(), () -> parseBoolean(p, KEY_VISIBLE_WHITESPACE, path)),
            setting(repair, defaults.syntaxHighlighting(), () -> parseBoolean(p, KEY_SYNTAX_HIGHLIGHTING, path)),
            setting(repair, defaults.rainbowBrackets(), () -> parseBoolean(p, KEY_RAINBOW_BRACKETS, path)),
            setting(repair, defaults.autoSelectionHighlight(), () -> parseBoolean(p, KEY_AUTO_SELECTION_HIGHLIGHT, path)),
            setting(repair, defaults.markdownOpenBothTabs(), () -> parseBoolean(p, KEY_MARKDOWN_OPEN_BOTH_TABS, path)),
            setting(repair, defaults.markdownOpenInPreview(), () -> parseBoolean(p, KEY_MARKDOWN_OPEN_IN_PREVIEW, path)),
            setting(repair, defaults.markdownCodeHighlighting(), () -> parseOptionalBoolean(p, KEY_MARKDOWN_CODE_HIGHLIGHTING, false, path)),
            setting(repair, defaults.gutterDiffMode(), () -> parseEnum(p, KEY_GUTTER_DIFF_MODE, GutterDiffMode.class, path)),
            setting(repair, defaults.tabWidth(), () -> parseTabWidth(p, path)),
            setting(repair, defaults.tabInsertMode(), () -> parseEnum(p, KEY_TAB_INSERT_MODE, TabInsertMode.class, path)),
            setting(repair, defaults.theme(), () -> parseEnum(p, KEY_THEME, UiStyles.Theme.class, path)),
            setting(repair, defaults.rightClickClipboard(), () -> parseOptionalBoolean(p, KEY_RIGHT_CLICK_CLIPBOARD, false, path)),
            setting(repair, defaults.markdownVisualCopy(), () -> parseOptionalBoolean(p, KEY_MARKDOWN_VISUAL_COPY, false, path)),
            setting(repair, defaults.showFileHistoryNotifications(), () -> parseOptionalBoolean(p, KEY_FILE_HISTORY_NOTIFICATIONS, true, path)),
            setting(repair, defaults.showDocumentPath(), () -> parseOptionalBoolean(p, KEY_DOCUMENT_PATH, true, path)),
            setting(repair, defaults.projectSearch(), () -> parseSearch(p, path)),
            setting(repair, defaults.palettes(), () -> parsePalettes(p, path)),
            setting(repair, defaults.autoReloadUnmodifiedFiles(), () -> parseOptionalBoolean(p, KEY_AUTO_RELOAD_UNMODIFIED, true, path)),
            setting(repair, defaults.dragSelectedText(), () -> parseOptionalBoolean(p, KEY_DRAG_SELECTED_TEXT, false, path))
        );
    }

    @FunctionalInterface
    private interface SettingReader<T> {
        T read() throws IOException;
    }

    private static <T> T setting(boolean repair, T fallback, SettingReader<T> reader) throws IOException {
        try {
            return reader.read();
        } catch (IOException invalid) {
            if (!repair) throw invalid;
            return fallback;
        }
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

    /** Repairs only the exact unsupported bytes and target previously offered to the user. */
    public static LoadedSettings repairIfUnchanged(UnsupportedSettingsException unsupported) throws IOException {
        LoadedSettings original = Objects.requireNonNull(unsupported, "unsupported").original;
        return save(original.requested, original.settings(), original);
    }

    /** Deletes the requested leaf; a final symbolic link is removed without deleting its target. */
    public static LoadedSettings deleteIfUnchanged(UnsupportedSettingsException unsupported) throws IOException {
        LoadedSettings original = Objects.requireNonNull(unsupported, "unsupported").original;
        Path target = requireUnchanged(original);
        FileSaveGuard guard;
        try {
            guard = FileSaveGuard.acquire(target);
        } catch (IOException unavailable) {
            throw new SettingsConflictException(original.requested, unavailable);
        }
        Throwable failure = null;
        try {
            Path entry = JvmFilePaths.resolveEntry(original.requested);
            requireSameTarget(original.requested, target, original);
            Files.delete(entry);
            return new LoadedSettings(AppSettings.defaults(), original.requested, entry, null);
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            releaseGuard(guard, failure);
        }
    }

    private static LoadedSettings save(Path path, AppSettings settings, LoadedSettings loaded) throws IOException {
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
        p.setProperty(KEY_DOCUMENT_PATH, Boolean.toString(settings.showDocumentPath()));
        p.setProperty(KEY_WRAP_LINES, Boolean.toString(settings.wrapLines()));
        p.setProperty(KEY_WRAP_WORDS, Boolean.toString(settings.wrapWords()));
        p.setProperty(KEY_READING_MODE, Boolean.toString(settings.readingMode()));
        p.setProperty(KEY_CONFIRM_ON_CLOSE, Boolean.toString(settings.confirmOnClose()));
        p.setProperty(KEY_MOUSE_MODE, Boolean.toString(settings.mouseMode()));
        if (settings.rightClickClipboard()) p.setProperty(KEY_RIGHT_CLICK_CLIPBOARD, "true");
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
        if (settings.markdownCodeHighlighting()) {
            p.setProperty(KEY_MARKDOWN_CODE_HIGHLIGHTING, "true");
        }
        if (settings.markdownVisualCopy()) p.setProperty(KEY_MARKDOWN_VISUAL_COPY, "true");
        p.setProperty(KEY_AUTO_RELOAD_UNMODIFIED, Boolean.toString(settings.autoReloadUnmodifiedFiles()));
        p.setProperty(KEY_DRAG_SELECTED_TEXT, Boolean.toString(settings.dragSelectedText()));
        p.setProperty(KEY_FILE_HISTORY_NOTIFICATIONS, Boolean.toString(settings.showFileHistoryNotifications()));
        p.setProperty(KEY_GUTTER_DIFF_MODE, canonical(settings.gutterDiffMode()));
        p.setProperty(KEY_TAB_WIDTH, Integer.toString(settings.tabWidth()));
        p.setProperty(KEY_TAB_INSERT_MODE, canonical(settings.tabInsertMode()));
        p.setProperty(KEY_THEME, canonical(settings.theme()));
        SearchPreferences search = settings.projectSearch();
        p.setProperty(SEARCH + "automatic", Boolean.toString(search.automatic()));
        p.setProperty(SEARCH + "respectIgnore", Boolean.toString(search.respectIgnore()));
        p.setProperty(SEARCH + "hidden", Boolean.toString(search.hidden()));
        p.setProperty(SEARCH + "exclusions", search.exclusions());
        p.setProperty(SEARCH + "encoding", canonical(search.encoding()));
        p.setProperty(SEARCH + "maximumFileMiB", Integer.toString(search.maximumFileMiB()));
        p.setProperty(SEARCH + "maximumMatches", Integer.toString(search.maximumMatches()));
        PalettePreferences palettes = settings.palettes();
        p.setProperty(PALETTES + "width", Integer.toString(palettes.width()));
        p.setProperty(PALETTES + "filesHeight", Integer.toString(palettes.filesHeight()));
        p.setProperty(PALETTES + "searchHeight", Integer.toString(palettes.searchHeight()));
        p.setProperty(PALETTES + "filesExpanded", Boolean.toString(palettes.filesExpanded()));
        p.setProperty(PALETTES + "searchExpanded", Boolean.toString(palettes.searchExpanded()));
        p.setProperty(PALETTES + "searchFirst", Boolean.toString(palettes.searchFirst()));
        p.setProperty(PALETTES + "maximized", canonical(palettes.maximized()));
        p.setProperty(PALETTES + "visible", Boolean.toString(palettes.visible()));
        p.setProperty(PALETTES + "filesHints", Boolean.toString(palettes.filesHints()));
        p.setProperty(PALETTES + "searchHints", Boolean.toString(palettes.searchHints()));

        byte[] encoded;
        try (var output = new ByteArrayOutputStream()) {
            p.store(output, "TUInk user settings");
            encoded = output.toByteArray();
        }
        Path tmp = Files.createTempFile(dir, ".tuink-settings.", ".tmp");
        FileSaveGuard guard = null;
        Throwable failure = null;
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                out.write(encoded);
            }
            try {
                guard = FileSaveGuard.acquire(target);
            } catch (IOException unavailable) {
                if (loaded != null) throw new SettingsConflictException(loaded.requested, unavailable);
                throw unavailable;
            }
            publishSettings(tmp, path.toAbsolutePath(), target, loaded);
            return new LoadedSettings(settings, path.toAbsolutePath(), target, fingerprint(encoded));
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            releaseGuard(guard, failure);
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

    private static void releaseGuard(FileSaveGuard guard, Throwable failure) {
        if (guard == null) return;
        try {
            guard.close();
        } catch (IOException cleanupFailure) {
            if (failure != null) failure.addSuppressed(cleanupFailure);
            // A committed write or deletion must not become a failure while releasing its guard.
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

    /** A readable bounded file whose current schema or values require an explicit user decision. */
    public static final class UnsupportedSettingsException extends IOException {
        private static final long serialVersionUID = 1L;
        private final LoadedSettings original;

        private UnsupportedSettingsException(IOException cause, LoadedSettings original) {
            super(cause.getMessage(), cause.getCause());
            this.original = original;
        }

        public AppSettings repairedSettings() { return original.settings(); }
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
        Set<String> missing = new java.util.HashSet<>(KEYS);
        missing.removeAll(p.stringPropertyNames());
        Set<String> unknown = new java.util.HashSet<>(p.stringPropertyNames());
        unknown.removeAll(KEYS);
        unknown.remove(KEY_MARKDOWN_CODE_HIGHLIGHTING);
        unknown.remove(KEY_RIGHT_CLICK_CLIPBOARD);
        unknown.remove(KEY_MARKDOWN_VISUAL_COPY);
        unknown.remove(KEY_FILE_HISTORY_NOTIFICATIONS);
        unknown.remove(KEY_AUTO_RELOAD_UNMODIFIED);
        unknown.remove(KEY_DRAG_SELECTED_TEXT);
        unknown.remove(KEY_DOCUMENT_PATH);
        for (String key : SEARCH_KEYS) unknown.remove(SEARCH + key);
        for (String key : PALETTE_KEYS) unknown.remove(PALETTES + key);
        if (!missing.isEmpty() || !unknown.isEmpty()) {
            throw invalid(path, "schema mismatch; missing=" + missing + ", unknown=" + unknown);
        }
    }

    /** Optional current-schema preferences preserve their documented default when omitted. */
    private static boolean parseOptionalBoolean(Properties p, String key, boolean defaultValue, Path path) throws IOException {
        return p.containsKey(key) ? parseBoolean(p, key, path) : defaultValue;
    }

    private static PalettePreferences parsePalettes(Properties p, Path path) throws IOException {
        var defaults = PalettePreferences.defaults();
        try {
            return new PalettePreferences(optionalPaletteInt(p, "width", defaults.width()),
                optionalPaletteInt(p, "filesHeight", defaults.filesHeight()), optionalPaletteInt(p, "searchHeight", defaults.searchHeight()),
                parseOptionalBoolean(p, PALETTES + "filesExpanded", defaults.filesExpanded(), path),
                parseOptionalBoolean(p, PALETTES + "searchExpanded", defaults.searchExpanded(), path),
                parseOptionalBoolean(p, PALETTES + "searchFirst", defaults.searchFirst(), path),
                p.containsKey(PALETTES + "maximized") ? parseEnum(p, PALETTES + "maximized", PalettePreferences.Maximized.class, path) : defaults.maximized(),
                parseOptionalBoolean(p, PALETTES + "visible", defaults.visible(), path),
                parseOptionalBoolean(p, PALETTES + "filesHints", defaults.filesHints(), path),
                parseOptionalBoolean(p, PALETTES + "searchHints", defaults.searchHints(), path));
        } catch (IllegalArgumentException invalid) { throw invalid(path, "invalid palette preferences: " + invalid.getMessage()); }
    }
    private static int optionalPaletteInt(Properties p, String key, int fallback) {
        String value = p.getProperty(PALETTES + key);
        if (value == null) return fallback;
        int parsed = Integer.parseInt(value);
        if (!Integer.toString(parsed).equals(value)) throw new IllegalArgumentException("non-canonical " + key);
        return parsed;
    }
    private static SearchPreferences parseSearch(Properties p, Path path) throws IOException {
        SearchPreferences defaults = SearchPreferences.defaults();
        try {
            return new SearchPreferences(
                parseOptionalBoolean(p, SEARCH + "automatic", defaults.automatic(), path),
                parseOptionalBoolean(p, SEARCH + "respectIgnore", defaults.respectIgnore(), path),
                parseOptionalBoolean(p, SEARCH + "hidden", defaults.hidden(), path),
                p.getProperty(SEARCH + "exclusions", defaults.exclusions()),
                p.containsKey(SEARCH + "encoding") ? parseEnum(p, SEARCH + "encoding", DocumentOpenMode.class, path) : defaults.encoding(),
                optionalSearchInt(p, "maximumFileMiB", defaults.maximumFileMiB()),
                optionalSearchInt(p, "maximumMatches", defaults.maximumMatches()));
        } catch (IllegalArgumentException invalid) { throw invalid(path, "invalid project search preferences: " + invalid.getMessage()); }
    }
    private static int optionalSearchInt(Properties p, String key, int fallback) {
        String value = p.getProperty(SEARCH + key);
        if (value == null) return fallback;
        int parsed = Integer.parseInt(value);
        if (!Integer.toString(parsed).equals(value)) throw new IllegalArgumentException("non-canonical " + key);
        return parsed;
    }

    private static boolean parseBoolean(Properties p, String key, Path path) throws IOException {
        String value = p.getProperty(key);
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw invalid(path, key + " must be true or false");
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
