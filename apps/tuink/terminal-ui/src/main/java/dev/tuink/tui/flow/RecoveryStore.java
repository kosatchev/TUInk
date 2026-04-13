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
    private static final String FORMAT = "1";
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    );
    private static final String KEY_FORMAT = "format";
    private static final String KEY_CREATED_AT = "createdAtEpochMillis";
    private static final String KEY_DIRTY = "dirty";
    private static final String KEY_CURRENT_FILE_PRESENT = "currentFile.present";
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
    private static final Set<String> REQUIRED_KEYS = Set.of(
        KEY_FORMAT,
        KEY_CREATED_AT,
        KEY_DIRTY,
        KEY_CURRENT_FILE_PRESENT,
        KEY_CURRENT_FILE,
        KEY_FILE_ENCODING,
        KEY_LINE_SEPARATOR,
        KEY_CURSOR_LINE,
        KEY_CURSOR_COL,
        KEY_TOP_LINE,
        KEY_TOP_WRAP_SEGMENT,
        KEY_LEFT_COLUMN,
        KEY_LEFT_VISUAL_COLUMN,
        KEY_SELECTION_PRESENT,
        KEY_SELECTION_LINE,
        KEY_SELECTION_COL
    );
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
        String id = sanitize(entryId);
        Path text = dir.resolve(id + TEXT_SUFFIX);
        Path meta = dir.resolve(id + META_SUFFIX);
        boolean textExists = Files.exists(text, LinkOption.NOFOLLOW_LINKS);
        boolean metaExists = Files.exists(meta, LinkOption.NOFOLLOW_LINKS);
        if (textExists || metaExists) {
            if (!textExists || !metaExists || readEntry(id, meta, text) == null) {
                throw invalid(meta, "existing snapshot is incomplete or incompatible and was left unchanged");
            }
        }
        writeSnapshotText(text, snapshot.lines());
        writeSnapshotMeta(meta, snapshot);
    }

    public LoadResult loadAll() throws IOException {
        Path dir = directory();
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return new LoadResult(List.of(), List.of());
        }
        ensurePrivateDirectory(dir);
        List<Entry> out = new ArrayList<>();
        List<SkippedSnapshot> skipped = new ArrayList<>();
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
                        } else {
                            skipped.add(new SkippedSnapshot(id, "metadata or text file is missing or not a regular file"));
                        }
                    } catch (Exception e) {
                        skipped.add(new SkippedSnapshot(id, messageOf(e)));
                    }
                });
        }
        return new LoadResult(List.copyOf(out), List.copyOf(skipped));
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
        requireExactSchema(p, metaPath);
        String text = Files.readString(textPath, StandardCharsets.UTF_8);
        List<String> lines = Arrays.asList(text.split("\n", -1));
        RecoverySnapshot snapshot = new RecoverySnapshot(
            lines,
            new Position(parseCoordinate(p, KEY_CURSOR_LINE, metaPath), parseCoordinate(p, KEY_CURSOR_COL, metaPath)),
            new ViewportState(
                parseCoordinate(p, KEY_TOP_LINE, metaPath),
                parseCoordinate(p, KEY_TOP_WRAP_SEGMENT, metaPath),
                parseCoordinate(p, KEY_LEFT_COLUMN, metaPath),
                parseCoordinate(p, KEY_LEFT_VISUAL_COLUMN, metaPath)
            ),
            parseSelection(p, metaPath),
            parseBoolean(p, KEY_DIRTY, metaPath),
            parseCurrentFile(p, metaPath),
            parseCharset(p, metaPath),
            parseLineSeparator(p, metaPath),
            parseInstant(p, metaPath)
        );
        return new Entry(id, snapshot);
    }

    private void writeSnapshotText(Path target, List<String> lines) throws IOException {
        String body = String.join("\n", lines == null || lines.isEmpty() ? List.of("") : lines);
        writeAtomically(target, out -> out.write(body.getBytes(StandardCharsets.UTF_8)));
    }

    private void writeSnapshotMeta(Path target, RecoverySnapshot snapshot) throws IOException {
        Properties p = new Properties();
        p.setProperty(KEY_FORMAT, FORMAT);
        p.setProperty(KEY_CREATED_AT, Long.toString(snapshot.createdAt().toEpochMilli()));
        p.setProperty(KEY_DIRTY, Boolean.toString(snapshot.dirty()));
        p.setProperty(KEY_CURRENT_FILE_PRESENT, Boolean.toString(snapshot.currentFile() != null));
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

    private static void requireExactSchema(Properties p, Path path) throws IOException {
        if (!FORMAT.equals(p.getProperty(KEY_FORMAT))) {
            throw invalid(path, "unsupported or missing format marker");
        }
        if (!p.stringPropertyNames().equals(REQUIRED_KEYS)) {
            Set<String> missing = new java.util.HashSet<>(REQUIRED_KEYS);
            missing.removeAll(p.stringPropertyNames());
            Set<String> unknown = new java.util.HashSet<>(p.stringPropertyNames());
            unknown.removeAll(REQUIRED_KEYS);
            throw invalid(path, "schema mismatch; missing=" + missing + ", unknown=" + unknown);
        }
    }

    private static int parseCoordinate(Properties p, String key, Path path) throws IOException {
        try {
            int value = Integer.parseInt(p.getProperty(key));
            if (value < 0) {
                throw invalid(path, key + " must not be negative");
            }
            return value;
        } catch (NumberFormatException e) {
            throw invalid(path, key + " must be an integer");
        }
    }

    private static boolean parseBoolean(Properties p, String key, Path path) throws IOException {
        return switch (p.getProperty(key)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid(path, key + " must be true or false");
        };
    }

    private static Instant parseInstant(Properties p, Path path) throws IOException {
        try {
            return Instant.ofEpochMilli(Long.parseLong(p.getProperty(KEY_CREATED_AT)));
        } catch (RuntimeException e) {
            throw invalid(path, KEY_CREATED_AT + " must be a valid epoch millisecond value");
        }
    }

    private static Charset parseCharset(Properties p, Path path) throws IOException {
        try {
            return Charset.forName(p.getProperty(KEY_FILE_ENCODING));
        } catch (RuntimeException e) {
            throw invalid(path, "unknown charset: " + p.getProperty(KEY_FILE_ENCODING));
        }
    }

    private static Path parseCurrentFile(Properties p, Path path) throws IOException {
        boolean present = parseBoolean(p, KEY_CURRENT_FILE_PRESENT, path);
        String raw = p.getProperty(KEY_CURRENT_FILE);
        if (!present) {
            if (!raw.isEmpty()) {
                throw invalid(path, KEY_CURRENT_FILE + " must be empty when currentFile.present=false");
            }
            return null;
        }
        if (raw.isBlank()) {
            throw invalid(path, KEY_CURRENT_FILE + " is required when currentFile.present=true");
        }
        try {
            return Path.of(raw).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw invalid(path, "invalid currentFile path");
        }
    }

    private static Position parseSelection(Properties p, Path path) throws IOException {
        boolean present = parseBoolean(p, KEY_SELECTION_PRESENT, path);
        int line = parseCoordinate(p, KEY_SELECTION_LINE, path);
        int col = parseCoordinate(p, KEY_SELECTION_COL, path);
        if (!present) {
            if (line != 0 || col != 0) {
                throw invalid(path, "selection coordinates must be zero when selection.present=false");
            }
            return null;
        }
        return new Position(line, col);
    }

    private static String parseLineSeparator(Properties p, Path path) throws IOException {
        return switch (p.getProperty(KEY_LINE_SEPARATOR)) {
            case EditorState.LINE_SEPARATOR_LF -> EditorState.LINE_SEPARATOR_LF;
            case EditorState.LINE_SEPARATOR_CRLF -> EditorState.LINE_SEPARATOR_CRLF;
            case EditorState.LINE_SEPARATOR_CR -> EditorState.LINE_SEPARATOR_CR;
            default -> throw invalid(path, "unknown canonical line separator");
        };
    }

    private static IOException invalid(Path path, String reason) {
        return new IOException("Unsupported recovery snapshot " + path + ": " + reason);
    }

    private static String messageOf(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    public record Entry(String id, RecoverySnapshot snapshot) {
    }

    public record SkippedSnapshot(String id, String reason) {
    }

    public record LoadResult(List<Entry> entries, List<SkippedSnapshot> skipped) {
        public LoadResult {
            entries = List.copyOf(entries);
            skipped = List.copyOf(skipped);
        }
    }
}
