package dev.tuink.tui.flow;

import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.ViewportState;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Filesystem store for recovery snapshots.
 */
public final class RecoveryStore {
    private static final String RECOVERY_DIR = "recovery";
    private static final String META_SUFFIX = ".meta.properties";
    private static final String TEXT_SUFFIX = ".txt";
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    );
    private static final String KEY_CREATED_AT = "createdAtEpochMillis";
    private static final String KEY_DIRTY = "dirty";
    private static final String KEY_CURRENT_FILE = "currentFile";
    private static final String KEY_FILE_ENCODING = "fileEncoding";
    private static final String KEY_LINE_SEPARATOR = "lineSeparator";
    private static final String KEY_CURSOR_LINE = "cursor.line";
    private static final String KEY_CURSOR_COL = "cursor.col";
    private static final String KEY_TOP_LINE = "viewport.topLine";
    private static final String KEY_TOP_WRAP_SEGMENT = "viewport.topWrapSegment";
    private static final String KEY_LEFT_COLUMN = "viewport.leftColumn";
    private static final String KEY_LEFT_VISUAL_COLUMN = "viewport.leftVisualColumn";
    private static final String KEY_SELECTION_PRESENT = "selection.present";
    private static final String KEY_SELECTION_LINE = "selection.line";
    private static final String KEY_SELECTION_COL = "selection.col";
    private final Path recoveryDirectory;

    public RecoveryStore() {
        this(defaultDirectory());
    }

    RecoveryStore(Path recoveryDirectory) {
        this.recoveryDirectory = Objects.requireNonNull(recoveryDirectory, "recoveryDirectory")
            .toAbsolutePath()
            .normalize();
    }

    public Path directory() {
        return recoveryDirectory;
    }

    public void save(String entryId, RecoverySnapshot snapshot) throws IOException {
        if (entryId == null || entryId.isBlank() || snapshot == null) {
            return;
        }
        Path dir = directory();
        ensurePrivateDirectory(dir);
        hardenExistingSnapshotFiles(dir);
        String id = sanitize(entryId);
        Path text = dir.resolve(id + TEXT_SUFFIX);
        Path meta = dir.resolve(id + META_SUFFIX);
        writeSnapshotText(text, snapshot.lines());
        writeSnapshotMeta(meta, snapshot);
    }

    public List<Entry> loadAll() throws IOException {
        Path dir = directory();
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        ensurePrivateDirectory(dir);
        hardenExistingSnapshotFiles(dir);
        List<Entry> out = new ArrayList<>();
        try (var files = Files.list(dir)) {
            files
                .filter(path ->
                    path.getFileName() != null
                        && path.getFileName().toString().endsWith(META_SUFFIX)
                        && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                )
                .sorted()
                .forEach(meta -> {
                    String name = meta.getFileName().toString();
                    String id = name.substring(0, name.length() - META_SUFFIX.length());
                    Path text = dir.resolve(id + TEXT_SUFFIX);
                    try {
                        Entry entry = readEntry(id, meta, text);
                        if (entry != null) {
                            out.add(entry);
                        }
                    } catch (IOException ignored) {
                        // Ignore broken snapshots; user can discard them on next cleanup.
                    }
                });
        }
        return out;
    }

    public void delete(String entryId) throws IOException {
        if (entryId == null || entryId.isBlank()) {
            return;
        }
        String id = sanitize(entryId);
        Path dir = directory();
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        ensurePrivateDirectory(dir);
        hardenExistingSnapshotFiles(dir);
        Files.deleteIfExists(dir.resolve(id + META_SUFFIX));
        Files.deleteIfExists(dir.resolve(id + TEXT_SUFFIX));
    }

    public void deleteAll(List<Entry> entries) throws IOException {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (Entry entry : entries) {
            delete(entry.id());
        }
    }

    private Entry readEntry(String id, Path metaPath, Path textPath) throws IOException {
        if (!Files.isRegularFile(metaPath, LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(textPath, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(metaPath)) {
            p.load(in);
        }
        String text = Files.readString(textPath, StandardCharsets.UTF_8);
        List<String> lines = Arrays.asList(text.split("\n", -1));
        RecoverySnapshot snapshot = new RecoverySnapshot(
            lines,
            new Position(parseInt(p.getProperty(KEY_CURSOR_LINE), 0), parseInt(p.getProperty(KEY_CURSOR_COL), 0)),
            new ViewportState(
                parseInt(p.getProperty(KEY_TOP_LINE), 0),
                parseInt(p.getProperty(KEY_TOP_WRAP_SEGMENT), 0),
                parseInt(p.getProperty(KEY_LEFT_COLUMN), 0),
                parseInt(p.getProperty(KEY_LEFT_VISUAL_COLUMN), 0)
            ),
            parseSelection(p),
            parseBoolean(p.getProperty(KEY_DIRTY), true),
            parsePath(p.getProperty(KEY_CURRENT_FILE)),
            parseCharset(p.getProperty(KEY_FILE_ENCODING), StandardCharsets.UTF_8),
            parseLineSeparator(p.getProperty(KEY_LINE_SEPARATOR)),
            parseInstant(p.getProperty(KEY_CREATED_AT), Instant.now())
        );
        return new Entry(id, snapshot);
    }

    private void writeSnapshotText(Path target, List<String> lines) throws IOException {
        String body = String.join("\n", lines == null || lines.isEmpty() ? List.of("") : lines);
        writeAtomically(target, out -> out.write(body.getBytes(StandardCharsets.UTF_8)));
    }

    private void writeSnapshotMeta(Path target, RecoverySnapshot snapshot) throws IOException {
        Properties p = new Properties();
        p.setProperty(KEY_CREATED_AT, Long.toString(snapshot.createdAt().toEpochMilli()));
        p.setProperty(KEY_DIRTY, Boolean.toString(snapshot.dirty()));
        p.setProperty(KEY_CURRENT_FILE, snapshot.currentFile() == null ? "" : snapshot.currentFile().toString());
        p.setProperty(KEY_FILE_ENCODING, snapshot.fileEncoding().name());
        p.setProperty(KEY_LINE_SEPARATOR, snapshot.lineSeparator());
        p.setProperty(KEY_CURSOR_LINE, Integer.toString(snapshot.cursor().line()));
        p.setProperty(KEY_CURSOR_COL, Integer.toString(snapshot.cursor().col()));
        p.setProperty(KEY_TOP_LINE, Integer.toString(snapshot.viewport().topLine()));
        p.setProperty(KEY_TOP_WRAP_SEGMENT, Integer.toString(snapshot.viewport().topWrapSegment()));
        p.setProperty(KEY_LEFT_COLUMN, Integer.toString(snapshot.viewport().leftColumn()));
        p.setProperty(KEY_LEFT_VISUAL_COLUMN, Integer.toString(snapshot.viewport().leftVisualColumn()));
        Position selection = snapshot.selectionAnchor();
        p.setProperty(KEY_SELECTION_PRESENT, Boolean.toString(selection != null));
        p.setProperty(KEY_SELECTION_LINE, Integer.toString(selection == null ? 0 : selection.line()));
        p.setProperty(KEY_SELECTION_COL, Integer.toString(selection == null ? 0 : selection.col()));
        writeAtomically(target, out -> p.store(out, "TUInk recovery snapshot"));
    }

    private interface Writer {
        void write(OutputStream out) throws IOException;
    }

    private static void writeAtomically(Path target, Writer writer) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            ensurePrivateDirectory(parent);
        }
        Path tmp = createPrivateTempFile(parent, target.getFileName().toString());
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                writer.write(out);
            }
            setPrivateFilePermissions(tmp);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Path defaultDirectory() {
        String home = System.getProperty("user.home", ".");
        return Path.of(home, ".config", "tuink", RECOVERY_DIR);
    }

    private static void ensurePrivateDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Recovery directory must not be a symbolic link: " + directory);
        }
        try {
            Files.createDirectories(
                directory,
                PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS)
            );
        } catch (UnsupportedOperationException ignored) {
            Files.createDirectories(directory);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Recovery path is not a directory: " + directory);
        }
        setPosixPermissions(directory, DIRECTORY_PERMISSIONS);
    }

    private static void hardenExistingSnapshotFiles(Path directory) throws IOException {
        try (var paths = Files.newDirectoryStream(directory)) {
            for (Path path : paths) {
                String name = path.getFileName() == null ? "" : path.getFileName().toString();
                if ((name.endsWith(META_SUFFIX) || name.endsWith(TEXT_SUFFIX))
                    && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    setPrivateFilePermissions(path);
                }
            }
        }
    }

    private static Path createPrivateTempFile(Path parent, String fileName) throws IOException {
        try {
            return Files.createTempFile(
                parent,
                "." + fileName + ".",
                ".tmp",
                PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS)
            );
        } catch (UnsupportedOperationException ignored) {
            return Files.createTempFile(parent, "." + fileName + ".", ".tmp");
        }
    }

    private static void setPrivateFilePermissions(Path file) throws IOException {
        setPosixPermissions(file, FILE_PERMISSIONS);
    }

    private static void setPosixPermissions(Path path, Set<PosixFilePermission> permissions)
        throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Rely on the platform's default ACLs when POSIX permissions are unavailable.
        }
    }

    private static String sanitize(String id) {
        String trimmed = id.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') {
                out.append(ch);
            }
        }
        if (out.isEmpty()) {
            return "snapshot";
        }
        return out.toString();
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private static Instant parseInstant(String raw, Instant fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Charset parseCharset(String raw, Charset fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Charset.forName(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Path parsePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Path.of(raw.trim()).toAbsolutePath().normalize();
    }

    private static Position parseSelection(Properties p) {
        if (!parseBoolean(p.getProperty(KEY_SELECTION_PRESENT), false)) {
            return null;
        }
        return new Position(parseInt(p.getProperty(KEY_SELECTION_LINE), 0), parseInt(p.getProperty(KEY_SELECTION_COL), 0));
    }

    private static String parseLineSeparator(String raw) {
        if (EditorState.LINE_SEPARATOR_CRLF.equals(raw)) {
            return EditorState.LINE_SEPARATOR_CRLF;
        }
        if (EditorState.LINE_SEPARATOR_CR.equals(raw)) {
            return EditorState.LINE_SEPARATOR_CR;
        }
        return EditorState.LINE_SEPARATOR_LF;
    }

    public record Entry(String id, RecoverySnapshot snapshot) {
    }
}
