package dev.tuink.tui.flow;

import dev.tuink.core.BomKind;
import dev.tuink.core.DocumentFileMetadata;
import dev.tuink.core.EditorState;
import dev.tuink.core.FileBaseline;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.SaveDisposition;
import dev.tuink.core.ViewportState;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * Filesystem store for recovery snapshots.
 */
public final class RecoveryStore implements AutoCloseable {
    private static final String RECOVERY_DIR = "recovery";
    private static final String META_SUFFIX = ".meta.properties";
    private static final String DELETE_SUFFIX = ".delete.properties";
    private static final String TEXT_SUFFIX = ".txt";
    private static final String LOCK_SUFFIX = ".lock";
    private static final String FORMAT = "5";
    // Some systems release process-wide locks when any descriptor for the same
    // file closes. Avoid opening a second channel for a local owner's lock.
    private static final Map<Path, RecoveryStore> LOCAL_OWNERS = new HashMap<>();
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
    private static final String KEY_TEXT_GENERATION = "textGeneration";
    private static final String KEY_DIRTY = "dirty";
    private static final String KEY_CURRENT_FILE_PRESENT = "currentFile.present";
    private static final String KEY_CURRENT_FILE = "currentFile";
    private static final String KEY_SAVE_DISPOSITION = "saveDisposition";
    private static final String KEY_FILE_ENCODING = "fileEncoding";
    private static final String KEY_BOM_KIND = "bomKind";
    private static final String KEY_DEFAULT_LINE_SEPARATOR = "defaultLineSeparator";
    private static final String KEY_LINE_SEPARATORS = "lineSeparators";
    private static final String KEY_FILE_BASELINE_PRESENT = "fileBaseline.present";
    private static final String KEY_FILE_BASELINE_TARGET = "fileBaseline.target";
    private static final String KEY_FILE_BASELINE_SHA256 = "fileBaseline.sha256";
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
        KEY_TEXT_GENERATION,
        KEY_DIRTY,
        KEY_CURRENT_FILE_PRESENT,
        KEY_CURRENT_FILE,
        KEY_SAVE_DISPOSITION,
        KEY_FILE_ENCODING,
        KEY_BOM_KIND,
        KEY_DEFAULT_LINE_SEPARATOR,
        KEY_LINE_SEPARATORS,
        KEY_FILE_BASELINE_PRESENT,
        KEY_FILE_BASELINE_TARGET,
        KEY_FILE_BASELINE_SHA256,
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
    private final Map<String, EntryLock> entryLocks = new LinkedHashMap<>();
    private boolean closed;

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

    public synchronized void save(String entryId, RecoverySnapshot snapshot) throws IOException {
        ensureOpen();
        if (entryId == null || entryId.isBlank() || snapshot == null) {
            return;
        }
        Path dir = directory();
        ensurePrivateDirectory(dir);
        String id = sanitize(entryId);
        requireClaim(id);
        Path meta = dir.resolve(id + META_SUFFIX);
        Path deletionMarker = dir.resolve(id + DELETE_SUFFIX);
        if (Files.exists(deletionMarker, LinkOption.NOFOLLOW_LINKS)) {
            // A previous explicit cleanup committed but did not finish. Complete
            // it before reusing the stable runtime id for a newly dirty document.
            delete(id);
            requireClaim(id);
        }
        Path previousText = null;
        if (Files.exists(meta, LinkOption.NOFOLLOW_LINKS)) {
            Entry existing = readEntry(id, meta);
            if (existing == null) {
                throw invalid(meta, "existing snapshot is incomplete or incompatible and was left unchanged");
            }
            previousText = textPath(id, readTextGeneration(meta));
        }

        String generation = UUID.randomUUID().toString().replace("-", "");
        Path nextText = textPath(id, generation);
        boolean committed = false;
        try {
            writeSnapshotText(nextText, snapshot.lines());
            writeSnapshotMeta(meta, snapshot, generation);
            committed = true;
        } finally {
            if (!committed) {
                Files.deleteIfExists(nextText);
            }
        }
        if (previousText != null && !previousText.equals(nextText)) {
            try {
                Files.deleteIfExists(previousText);
            } catch (IOException ignored) {
                // The committed generation is authoritative; an orphan is harmless.
            }
        }
    }

    public synchronized LoadResult loadAll() throws IOException {
        ensureOpen();
        Path dir = directory();
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return new LoadResult(List.of(), List.of());
        }
        ensurePrivateDirectory(dir);
        List<Entry> out = new ArrayList<>();
        List<SkippedSnapshot> skipped = new ArrayList<>();
        retryPendingDeletions(dir, skipped);
        List<Path> metadataFiles;
        try (var files = Files.list(dir)) {
            metadataFiles = files
                .filter(path ->
                    path.getFileName() != null
                        && path.getFileName().toString().endsWith(META_SUFFIX)
                        && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                )
                .sorted()
                .toList();
        }
        for (Path meta : metadataFiles) {
            String name = meta.getFileName().toString();
            String id = name.substring(0, name.length() - META_SUFFIX.length());
            if (!isCanonicalId(id)) {
                skipped.add(new SkippedSnapshot(id, "snapshot filename has a noncanonical entry id"));
                continue;
            }
            boolean previouslyOwned = entryLocks.containsKey(id);
            boolean loaded = false;
            try {
                if (!tryClaim(id)) {
                    // This is unfinished work in a live editor, not a snapshot
                    // that this instance should offer to restore or discard.
                    continue;
                }
                Entry entry = readEntry(id, meta);
                if (entry != null) {
                    out.add(entry);
                    loaded = true;
                } else {
                    skipped.add(new SkippedSnapshot(id, "metadata or text file is missing or not a regular file"));
                }
            } catch (Exception e) {
                skipped.add(new SkippedSnapshot(id, messageOf(e)));
            } finally {
                if (!previouslyOwned && !loaded) {
                    releaseClaim(id);
                }
            }
        }
        return new LoadResult(List.copyOf(out), List.copyOf(skipped));
    }

    public synchronized void delete(String entryId) throws IOException {
        ensureOpen();
        if (entryId == null || entryId.isBlank()) {
            return;
        }
        String id = sanitize(entryId);
        Path dir = directory();
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        ensurePrivateDirectory(dir);
        requireClaim(id);
        Path meta = dir.resolve(id + META_SUFFIX);
        Path deletionMarker = dir.resolve(id + DELETE_SUFFIX);
        if (Files.exists(meta, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(deletionMarker, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid(
                    meta,
                    "live metadata and a pending deletion marker both exist"
                );
            }
            moveCommitMarker(meta, deletionMarker);
        }
        List<Path> generationTexts;
        try (var files = Files.list(dir)) {
            generationTexts = files
                .filter(path -> isGenerationText(id, path.getFileName().toString()))
                .toList();
        }
        for (Path generationText : generationTexts) {
            // deleteIfExists removes a link itself; it never follows it to external data.
            Files.deleteIfExists(generationText);
        }
        // The deletion marker is removed last. Any partial cleanup is therefore
        // visible on the next startup and retried without exposing a live snapshot
        // whose metadata points at already deleted text.
        Files.deleteIfExists(deletionMarker);
        releaseClaim(id);
    }

    public synchronized void deleteAll(List<Entry> entries) throws IOException {
        ensureOpen();
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (Entry entry : entries) {
            delete(entry.id());
        }
    }

    private void retryPendingDeletions(Path dir, List<SkippedSnapshot> skipped)
        throws IOException {
        List<Path> markers;
        try (var files = Files.list(dir)) {
            markers = files
                .filter(path -> {
                    Path namePath = path.getFileName();
                    if (namePath == null) {
                        return false;
                    }
                    String name = namePath.toString();
                    if (!name.endsWith(DELETE_SUFFIX)) {
                        return false;
                    }
                    String id = name.substring(0, name.length() - DELETE_SUFFIX.length());
                    return isCanonicalId(id);
                })
                .sorted()
                .toList();
        }
        for (Path marker : markers) {
            String name = marker.getFileName().toString();
            String id = name.substring(0, name.length() - DELETE_SUFFIX.length());
            try {
                if (!tryClaim(id)) {
                    continue;
                }
                delete(id);
            } catch (IOException e) {
                skipped.add(new SkippedSnapshot(
                    id,
                    "pending deletion cleanup failed: " + messageOf(e)
                ));
            }
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Recovery store is closed");
        }
    }

    private void requireClaim(String id) throws IOException {
        if (!tryClaim(id)) {
            throw new IOException("Recovery snapshot is owned by another editor: " + id);
        }
    }

    private boolean tryClaim(String id) throws IOException {
        if (entryLocks.containsKey(id)) {
            return true;
        }
        Path lockPath = directory().toRealPath().resolve(id + LOCK_SUFFIX);
        synchronized (LOCAL_OWNERS) {
            if (LOCAL_OWNERS.containsKey(lockPath)) {
                return false;
            }
            FileChannel channel = openLockChannel(lockPath);
            boolean claimed = false;
            try {
                // Changing permissions may open and close another descriptor.
                // Do it before locking: that close can release POSIX locks held
                // by this process, even when our channel remains open.
                try {
                    Files.setAttribute(lockPath, "posix:permissions", FILE_PERMISSIONS, LinkOption.NOFOLLOW_LINKS);
                } catch (UnsupportedOperationException ignored) {
                    // Platforms without POSIX permissions use their default ACLs.
                }
                FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException busy) {
                    return false;
                }
                if (lock == null) {
                    return false;
                }
                entryLocks.put(id, new EntryLock(lockPath, channel, lock));
                LOCAL_OWNERS.put(lockPath, this);
                claimed = true;
                return true;
            } finally {
                if (!claimed) {
                    channel.close();
                }
            }
        }
    }

    private static FileChannel openLockChannel(Path lockPath) throws IOException {
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
            && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Recovery lock is not a regular file: " + lockPath);
        }
        Set<OpenOption> options = Set.of(
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        );
        try {
            return FileChannel.open(lockPath, options, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        } catch (UnsupportedOperationException ignored) {
            return FileChannel.open(lockPath, options);
        }
    }

    private void releaseClaim(String id) throws IOException {
        EntryLock claimed = entryLocks.remove(id);
        if (claimed == null) {
            return;
        }
        synchronized (LOCAL_OWNERS) {
            try (FileChannel channel = claimed.channel()) {
                claimed.lock().release();
            } finally {
                LOCAL_OWNERS.remove(claimed.path(), this);
            }
        }
        // Never unlink a lock sidecar: another process may already have its inode
        // open, and replacing it would allow two owners to lock different files.
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        for (String id : List.copyOf(entryLocks.keySet())) {
            try {
                releaseClaim(id);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private record EntryLock(Path path, FileChannel channel, FileLock lock) {
    }

    private static void moveCommitMarker(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private Entry readEntry(String id, Path metaPath) throws IOException {
        if (!Files.isRegularFile(metaPath, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(metaPath)) {
            p.load(in);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed recovery metadata " + metaPath + ": " + malformed.getMessage(), malformed);
        }
        requireExactSchema(p, metaPath);
        Path textPath = textPath(id, parseTextGeneration(p, metaPath));
        if (!Files.isRegularFile(textPath, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        String text = decodeUtf8Strict(Files.readAllBytes(textPath));
        List<String> lines = Arrays.asList(text.split("\n", -1));
        try {
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
                parseSaveDisposition(p, metaPath),
                parseFileMetadata(p, metaPath, lines.size()),
                parseFileBaseline(p, metaPath),
                parseInstant(p, metaPath)
            );
            return new Entry(id, snapshot);
        } catch (IllegalArgumentException e) {
            throw invalid(metaPath, "invalid recovery snapshot: " + e.getMessage());
        }
    }

    private static boolean isGenerationText(String id, String fileName) {
        String prefix = id + ".";
        if (fileName.length() != prefix.length() + 32 + TEXT_SUFFIX.length()
            || !fileName.startsWith(prefix) || !fileName.endsWith(TEXT_SUFFIX)) {
            return false;
        }
        String generation = fileName.substring(
            prefix.length(),
            fileName.length() - TEXT_SUFFIX.length()
        );
        for (int i = 0; i < generation.length(); i++) {
            char c = generation.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private void writeSnapshotText(Path target, List<String> lines) throws IOException {
        String body = String.join("\n", lines == null || lines.isEmpty() ? List.of("") : lines);
        byte[] encoded = encodeUtf8Strict(body);
        writeAtomically(target, out -> out.write(encoded));
    }

    private static byte[] encodeUtf8Strict(String text) throws CharacterCodingException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private static String decodeUtf8Strict(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    }

    private void writeSnapshotMeta(
        Path target,
        RecoverySnapshot snapshot,
        String textGeneration
    ) throws IOException {
        Properties p = new Properties();
        p.setProperty(KEY_FORMAT, FORMAT);
        p.setProperty(KEY_CREATED_AT, Long.toString(snapshot.createdAt().toEpochMilli()));
        p.setProperty(KEY_TEXT_GENERATION, textGeneration);
        p.setProperty(KEY_DIRTY, Boolean.toString(snapshot.dirty()));
        p.setProperty(KEY_CURRENT_FILE_PRESENT, Boolean.toString(snapshot.currentFile() != null));
        p.setProperty(KEY_CURRENT_FILE, snapshot.currentFile() == null ? "" : snapshot.currentFile().toString());
        p.setProperty(KEY_SAVE_DISPOSITION, snapshot.saveDisposition().name());
        DocumentFileMetadata fileMetadata = snapshot.fileMetadata();
        p.setProperty(KEY_FILE_ENCODING, fileMetadata.charset().name());
        p.setProperty(KEY_BOM_KIND, fileMetadata.bomKind().name());
        p.setProperty(KEY_DEFAULT_LINE_SEPARATOR, encodeLineSeparator(fileMetadata.defaultLineSeparator()));
        p.setProperty(
            KEY_LINE_SEPARATORS,
            fileMetadata.lineSeparators().stream()
                .map(RecoveryStore::encodeLineSeparator)
                .collect(java.util.stream.Collectors.joining(","))
        );
        FileBaseline fileBaseline = snapshot.fileBaseline();
        p.setProperty(KEY_FILE_BASELINE_PRESENT, Boolean.toString(fileBaseline != null));
        p.setProperty(KEY_FILE_BASELINE_TARGET, fileBaseline == null ? "" : fileBaseline.target().toString());
        p.setProperty(KEY_FILE_BASELINE_SHA256, fileBaseline == null ? "" : fileBaseline.sha256());
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
        boolean committed = false;
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
            committed = true;
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanupFailure) {
                if (!committed) {
                    throw cleanupFailure;
                }
                // Metadata already names the committed generation. Do not turn a
                // harmless private temp orphan into a false failed-save signal.
            }
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

    private static boolean isCanonicalId(String id) {
        return !id.isBlank() && sanitize(id).equals(id);
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

    private String readTextGeneration(Path metaPath) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(metaPath)) {
            properties.load(in);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed recovery metadata " + metaPath + ": " + malformed.getMessage(), malformed);
        }
        requireExactSchema(properties, metaPath);
        return parseTextGeneration(properties, metaPath);
    }

    private Path textPath(String id, String generation) throws IOException {
        if (generation == null || !generation.matches("[0-9a-f]{32}")) {
            throw invalid(directory().resolve(id + META_SUFFIX), "invalid text generation");
        }
        return directory().resolve(id + "." + generation + TEXT_SUFFIX);
    }

    private static String parseTextGeneration(Properties p, Path path) throws IOException {
        String generation = p.getProperty(KEY_TEXT_GENERATION);
        if (generation == null || !generation.matches("[0-9a-f]{32}")) {
            throw invalid(path, KEY_TEXT_GENERATION + " must be 32 lowercase hexadecimal characters");
        }
        return generation;
    }

    private static DocumentFileMetadata parseFileMetadata(Properties p, Path path, int lineCount)
        throws IOException {
        Charset charset = parseCharset(p, path);
        BomKind bomKind;
        try {
            bomKind = BomKind.valueOf(p.getProperty(KEY_BOM_KIND));
        } catch (RuntimeException e) {
            throw invalid(path, "unknown BOM kind: " + p.getProperty(KEY_BOM_KIND));
        }
        String defaultLineSeparator = parseLineSeparator(
            p.getProperty(KEY_DEFAULT_LINE_SEPARATOR),
            KEY_DEFAULT_LINE_SEPARATOR,
            path
        );
        String encodedSeparators = p.getProperty(KEY_LINE_SEPARATORS);
        List<String> lineSeparators = new ArrayList<>();
        if (!encodedSeparators.isEmpty()) {
            String[] tokens = encodedSeparators.split(",", -1);
            for (String token : tokens) {
                lineSeparators.add(parseLineSeparator(token, KEY_LINE_SEPARATORS, path));
            }
        }
        try {
            DocumentFileMetadata metadata = new DocumentFileMetadata(
                charset,
                bomKind,
                defaultLineSeparator,
                lineSeparators
            );
            if (!metadata.isCompatibleWithLineCount(lineCount)) {
                throw invalid(
                    path,
                    KEY_LINE_SEPARATORS + " count does not match recovery text line count"
                );
            }
            return metadata;
        } catch (IllegalArgumentException e) {
            throw invalid(path, "invalid file metadata: " + e.getMessage());
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

    private static FileBaseline parseFileBaseline(Properties p, Path path) throws IOException {
        boolean present = parseBoolean(p, KEY_FILE_BASELINE_PRESENT, path);
        String rawTarget = p.getProperty(KEY_FILE_BASELINE_TARGET);
        String sha256 = p.getProperty(KEY_FILE_BASELINE_SHA256);
        if (!present) {
            if (!rawTarget.isEmpty() || !sha256.isEmpty()) {
                throw invalid(path, "fileBaseline fields must be empty when fileBaseline.present=false");
            }
            return null;
        }
        if (rawTarget.isBlank()) {
            throw invalid(path, KEY_FILE_BASELINE_TARGET + " is required when fileBaseline.present=true");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw invalid(path, KEY_FILE_BASELINE_SHA256 + " must be 64 lowercase hexadecimal characters");
        }
        try {
            Path target = Path.of(rawTarget);
            if (!target.isAbsolute() || !target.equals(target.normalize())) {
                throw invalid(path, KEY_FILE_BASELINE_TARGET + " must be an absolute normalized path");
            }
            return new FileBaseline(target, sha256);
        } catch (RuntimeException e) {
            throw invalid(path, "invalid fileBaseline: " + e.getMessage());
        }
    }

    private static SaveDisposition parseSaveDisposition(Properties p, Path path) throws IOException {
        try {
            return SaveDisposition.valueOf(p.getProperty(KEY_SAVE_DISPOSITION));
        } catch (RuntimeException e) {
            throw invalid(path, "unknown save disposition: " + p.getProperty(KEY_SAVE_DISPOSITION));
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

    private static String parseLineSeparator(String token, String key, Path path) throws IOException {
        return switch (token) {
            case "LF" -> EditorState.LINE_SEPARATOR_LF;
            case "CRLF" -> EditorState.LINE_SEPARATOR_CRLF;
            case "CR" -> EditorState.LINE_SEPARATOR_CR;
            default -> throw invalid(path, key + " contains an unknown line separator");
        };
    }

    private static String encodeLineSeparator(String separator) {
        return switch (separator) {
            case EditorState.LINE_SEPARATOR_LF -> "LF";
            case EditorState.LINE_SEPARATOR_CRLF -> "CRLF";
            case EditorState.LINE_SEPARATOR_CR -> "CR";
            default -> throw new IllegalArgumentException("Unknown line separator");
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
