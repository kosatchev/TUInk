package dev.tuink.tui.flow;

import dev.tuink.core.BomKind;
import dev.tuink.core.DocumentFileMetadata;
import dev.tuink.core.EditorState;
import dev.tuink.core.FileBaseline;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import dev.tuink.core.SaveDisposition;
import dev.tuink.core.ViewportState;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
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
    private static final String NAMESPACE_LOCK = ".namespace.lock";
    private static final String FORMAT = "8";
    private static final String PREVIOUS_FORMAT = "7";
    private static final String LEGACY_FORMAT = "6";
    private static final String DISPLAY_PATH_FORMAT = "5";
    // Some systems release process-wide locks when any descriptor for the same
    // file closes. Avoid opening a second channel for a local owner's lock.
    private static final Map<Path, RecoveryStore> LOCAL_OWNERS = new HashMap<>();
    private static final Map<Path, NamespaceGuard> LOCAL_NAMESPACES = new HashMap<>();
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
    private static final String KEY_PATH_PLATFORM = "pathPlatform";
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
    private static final String KEY_FILE_BASELINE_BYTE_SIZE = "fileBaseline.byteSize";
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
        KEY_PATH_PLATFORM,
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
    private final PublicationFiles publicationFiles;
    private Path physicalRecoveryDirectory;
    private final Map<String, EntryLock> entryLocks = new LinkedHashMap<>();
    private Path namespacePath;
    private boolean closed;

    public RecoveryStore() throws IOException {
        this(defaultDirectory());
    }

    RecoveryStore(Path recoveryDirectory) {
        this(recoveryDirectory, new PublicationFiles() {});
    }

    RecoveryStore(Path recoveryDirectory, PublicationFiles publicationFiles) {
        this.recoveryDirectory = Objects.requireNonNull(recoveryDirectory, "recoveryDirectory")
            .toAbsolutePath();
        this.publicationFiles = Objects.requireNonNull(publicationFiles, "publicationFiles");
    }

    /** Filesystem boundary for publication and best-effort removal of staged/orphan files. */
    interface PublicationFiles {
        /** An exception does not prove that the target was left unchanged. */
        default void publish(Path staged, Path target) throws IOException {
            try {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        default void deleteIfExists(Path path) throws IOException {
            Files.deleteIfExists(path);
        }
    }

    private enum PublicationState { NOT_ATTEMPTED, ATTEMPTED, COMMITTED }

    /** Shared with the generation transaction: metadata commit precedes any cleanup. */
    private static final class Publication {
        private PublicationState state = PublicationState.NOT_ATTEMPTED;
        boolean attempted() { return state != PublicationState.NOT_ATTEMPTED; }
        boolean committed() { return state == PublicationState.COMMITTED; }
        void attempt() { state = PublicationState.ATTEMPTED; }
        void commit() { state = PublicationState.COMMITTED; }
    }

    /** Returns the configured path until initialization, then the pinned physical directory. */
    public synchronized Path directory() {
        return physicalRecoveryDirectory == null ? recoveryDirectory : physicalRecoveryDirectory;
    }

    public synchronized void save(String entryId, RecoverySnapshot snapshot) throws IOException {
        ensureOpen();
        if (entryId == null || entryId.isBlank() || snapshot == null) {
            return;
        }
        RecoveryLimits.requireDocument(snapshot.lines());
        Path dir = initializeDirectory();
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
            previousText = validateStoredSnapshot(id, meta);
        }

        String generation = UUID.randomUUID().toString().replace("-", "");
        Path nextText = textPath(id, generation);
        Publication metadataPublication = new Publication();
        Throwable failure = null;
        try {
            writeSnapshotText(nextText, snapshot.lines());
            writeSnapshotMeta(meta, snapshot, generation, metadataPublication);
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            // Once metadata publication was attempted, an exception may have
            // followed its rename. Keep the payload even if the result is unknown.
            if (!metadataPublication.attempted()) {
                try {
                    publicationFiles.deleteIfExists(nextText);
                } catch (IOException | RuntimeException | OutOfMemoryError cleanupFailure) {
                    if (failure == null) throw cleanupFailure;
                    suppressCleanupFailure(failure, cleanupFailure);
                }
            }
        }
        if (previousText != null && !previousText.equals(nextText)) {
            try {
                publicationFiles.deleteIfExists(previousText);
            } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
                // The committed generation is authoritative; an orphan is harmless.
            }
        }
    }

    /** Claims compatible snapshots without reading their document text. */
    public synchronized DiscoveryResult discover() throws IOException {
        return scan(false);
    }

    /** Convenience for callers that need materialized snapshots, with the same memory admission checks. */
    public synchronized LoadResult loadAll() throws IOException {
        DiscoveryResult result = scan(true);
        return new LoadResult(result.entries().stream().map(Entry.class::cast).toList(), result.skipped());
    }

    private DiscoveryResult scan(boolean materialize) throws IOException {
        ensureOpen();
        Path dir = directory();
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return new DiscoveryResult(List.of(), List.of());
        }
        dir = initializeDirectory();
        synchronized (LOCAL_OWNERS) {
            acquireNamespaceGuard();
        }
        List<PendingSnapshot> out = new ArrayList<>();
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
                PendingSnapshot entry = materialize ? readEntry(id, meta) : discoverEntry(id, meta);
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
        return new DiscoveryResult(out, skipped);
    }

    /** Makes an owned snapshot available to another editor without changing its persisted data. */
    public synchronized void release(String entryId) throws IOException {
        ensureOpen();
        if (entryId != null && !entryId.isBlank()) {
            releaseClaim(sanitize(entryId));
        }
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
        dir = initializeDirectory();
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

    private Path initializeDirectory() throws IOException {
        Path dir = directory();
        // Keep initialization lazy and reject a symlink at the configured leaf
        // before resolving parent aliases. No entry may be claimed before pinning.
        ensurePrivateDirectory(dir);
        if (physicalRecoveryDirectory == null) {
            Path physical = dir.toRealPath();
            ensurePrivateDirectory(physical);
            physicalRecoveryDirectory = physical;
        }
        return physicalRecoveryDirectory;
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
        Path lockPath = directory().resolve(id + LOCK_SUFFIX);
        synchronized (LOCAL_OWNERS) {
            acquireNamespaceGuard();
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
        // Sidecars are reclaimed only under the exclusive namespace guard, after
        // all cooperating stores have closed every entry channel.
    }

    /** Called under LOCAL_OWNERS before opening any entry channel. */
    private void acquireNamespaceGuard() throws IOException {
        if (namespacePath != null) {
            return;
        }
        Path path = directory().resolve(NAMESPACE_LOCK);
        NamespaceGuard existing = LOCAL_NAMESPACES.get(path);
        if (existing != null) {
            existing.references++;
            namespacePath = path;
            return;
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Recovery namespace lock is not a regular file: " + path);
        }
        Set<OpenOption> options = Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileChannel channel;
        try {
            channel = FileChannel.open(path, options, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        } catch (UnsupportedOperationException unsupported) {
            channel = FileChannel.open(path, options);
        }
        boolean acquired = false;
        try {
            setPrivateFilePermissions(path);
            FileLock lock = channel.lock(0, Long.MAX_VALUE, true);
            LOCAL_NAMESPACES.put(path, new NamespaceGuard(channel, lock));
            namespacePath = path;
            acquired = true;
        } finally {
            if (!acquired) channel.close();
        }
    }

    private void releaseNamespaceGuard() throws IOException {
        synchronized (LOCAL_OWNERS) {
            if (namespacePath == null) return;
            Path path = namespacePath;
            namespacePath = null;
            NamespaceGuard guard = LOCAL_NAMESPACES.get(path);
            if (--guard.references != 0) return;
            LOCAL_NAMESPACES.remove(path);
            try (FileChannel channel = guard.channel) {
                guard.lock.release();
                // No local entry descriptors remain. If another process still
                // owns a store, leave its namespace intact and let its close GC.
                try (FileLock exclusive = channel.tryLock()) {
                    if (exclusive != null) collectUnusedEntryLocks(path.getParent());
                }
            }
        }
    }

    private static void collectUnusedEntryLocks(Path directory) {
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(LOCK_SUFFIX)
                    || !isCanonicalId(name.substring(0, name.length() - LOCK_SUFFIX.length()))) continue;
                // A pre-guard editor version can still hold an old sidecar. Do
                // not remove that active owner; mixed versions must not race
                // opening new entries during a protocol migration.
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                try (FileChannel entry = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                     FileLock unused = entry.tryLock()) {
                    if (unused != null) Files.deleteIfExists(path);
                } catch (IOException | OverlappingFileLockException ignored) {
                    // Cleanup is best effort; the next last-store close retries.
                }
            }
        } catch (IOException ignored) {
            // Recovery data and the stable namespace guard remain available.
        }
    }

    private static final class NamespaceGuard {
        private final FileChannel channel;
        private final FileLock lock;
        private int references = 1;

        private NamespaceGuard(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }
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
        try {
            releaseNamespaceGuard();
        } catch (IOException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
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
        Properties p = readMetadata(metaPath);
        SnapshotFields fields = parseSnapshotFields(p, metaPath);
        Path textPath = textPath(id, parseTextGeneration(p, metaPath));
        if (!Files.isRegularFile(textPath, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        requireRestoreFits(0, fields.fileMetadata().separators().count() + 1, textPath);
        TextShape shape = inspectStoredText(textPath, fields, metaPath);
        requireRestoreFits(shape.codeUnits(), shape.lines(), textPath);
        List<String> lines = materializeText(textPath, shape);
        try {
            RecoverySnapshot snapshot = new RecoverySnapshot(
                lines,
                fields.cursor(), fields.viewport(), fields.selectionAnchor(), fields.dirty(), fields.currentFile(),
                fields.saveDisposition(), fields.fileMetadata().materialize(), fields.fileBaseline(), fields.createdAt()
            );
            return new Entry(id, snapshot);
        } catch (IllegalArgumentException e) {
            throw invalid(metaPath, "invalid recovery snapshot: " + e.getMessage());
        }
    }

    private PendingSnapshot discoverEntry(String id, Path metaPath) throws IOException {
        Properties p = readMetadata(metaPath);
        String generation = parseTextGeneration(p, metaPath);
        if (!Files.isRegularFile(textPath(id, generation), LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        // Validate metadata while it is in hand, but retain only a small identity
        // descriptor. Text decoding and document validation happen after Restore.
        parseSnapshotFields(p, metaPath);
        return new StoredSnapshot(id, generation);
    }

    /** Validates the old generation without allocating a second document during autosave. */
    private Path validateStoredSnapshot(String id, Path metaPath) throws IOException {
        if (!Files.isRegularFile(metaPath, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid(metaPath, "existing snapshot metadata is not a regular file and was left unchanged");
        }
        Properties properties = readMetadata(metaPath);
        Path textPath = textPath(id, parseTextGeneration(properties, metaPath));
        if (!Files.isRegularFile(textPath, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid(metaPath, "existing snapshot text is missing or not a regular file and was left unchanged");
        }
        SnapshotFields fields = parseSnapshotFields(properties, metaPath);
        inspectStoredText(textPath, fields, metaPath);
        return textPath;
    }

    private record TextShape(long codeUnits, int lines) {}

    private static void requireRestoreFits(long codeUnits, int lines, Path path) throws IOException {
        if (8L * codeUnits + 128L * lines > availableHeap() / 2) {
            throw new IOException("Recovery text is too large or has too many lines for the available memory: " + path);
        }
    }

    /** Constant-space validation is shared by replacement of an old generation and restoration. */
    private static TextShape inspectStoredText(Path textPath, SnapshotFields fields, Path metaPath) throws IOException {
        int line = 0;
        int column = 0;
        long codeUnits = 0;
        char[] chunk = new char[8192];
        try (var reader = new InputStreamReader(RecoveryLimits.openText(textPath), StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT))) {
            int count;
            while ((count = reader.read(chunk)) >= 0) {
                codeUnits += count;
                RecoveryLimits.requireSize(codeUnits, RecoveryLimits.MAX_CODE_UNITS,
                    "Recovery document exceeds 64 Mi UTF-16 code units");
                for (int index = 0; index < count; index++) {
                    char character = chunk[index];
                    if (character == '\r') {
                        throw invalid(metaPath, "logical document lines must not contain carriage returns");
                    }
                    if (Character.isLowSurrogate(character)) {
                        requireSurrogateBoundary(fields.cursor(), "cursor", line, column, metaPath);
                        requireSurrogateBoundary(fields.selectionAnchor(), "selection anchor", line, column, metaPath);
                    }
                    if (character == '\n') {
                        requireLinePositions(fields, line, column, metaPath);
                        if (line == Integer.MAX_VALUE - 1) {
                            throw invalid(metaPath, "too many logical document lines");
                        }
                        line++;
                        column = 0;
                    } else {
                        if (column == Integer.MAX_VALUE) {
                            throw invalid(metaPath, "logical document line is too long");
                        }
                        column++;
                    }
                }
            }
        }
        requireLinePositions(fields, line, column, metaPath);
        if (fields.cursor().line() > line) {
            throw invalid(metaPath, "cursor is outside recovery document");
        }
        if (fields.selectionAnchor() != null && fields.selectionAnchor().line() > line) {
            throw invalid(metaPath, "selection anchor is outside recovery document");
        }
        if (fields.viewport().topLine() > line) {
            throw invalid(metaPath, "Viewport top line is outside recovery document");
        }
        requireLineCountMatches(fields, line + 1, metaPath);
        return new TextShape(codeUnits, line + 1);
    }

    /** A second bounded pass allocates document lines only after their full shape is admitted. */
    private static List<String> materializeText(Path textPath, TextShape expected) throws IOException {
        List<String> lines = new ArrayList<>(expected.lines());
        StringBuilder line = new StringBuilder();
        long codeUnits = 0;
        char[] chunk = new char[8192];
        try (var reader = new InputStreamReader(RecoveryLimits.openText(textPath), StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            int count;
            while ((count = reader.read(chunk)) >= 0) {
                codeUnits += count;
                if (codeUnits > expected.codeUnits()) throw changedText(textPath);
                int start = 0;
                for (int index = 0; index < count; index++) {
                    if (chunk[index] == '\r') throw changedText(textPath);
                    if (chunk[index] == '\n') {
                        if (lines.size() >= expected.lines() - 1) throw changedText(textPath);
                        line.append(chunk, start, index - start);
                        lines.add(line.toString());
                        line.setLength(0);
                        start = index + 1;
                    }
                }
                line.append(chunk, start, count - start);
            }
        }
        lines.add(line.toString());
        if (codeUnits != expected.codeUnits() || lines.size() != expected.lines()) throw changedText(textPath);
        return lines;
    }

    private static IOException changedText(Path path) {
        return new IOException("Recovery text changed during restoration: " + path + "; snapshot left unchanged");
    }

    private static void requireLinePositions(SnapshotFields fields, int line, int length, Path path)
        throws IOException {
        requirePositionOnLine(fields.cursor(), "cursor", line, length, path);
        requirePositionOnLine(fields.selectionAnchor(), "selection anchor", line, length, path);
    }

    private static void requirePositionOnLine(Position position, String name, int line, int length, Path path)
        throws IOException {
        if (position != null && position.line() == line && position.col() > length) {
            throw invalid(path, name + " is outside recovery document");
        }
    }

    private static void requireSurrogateBoundary(Position position, String name, int line, int column, Path path)
        throws IOException {
        if (position != null && position.line() == line && position.col() == column) {
            throw invalid(path, name + " splits a UTF-16 surrogate pair");
        }
    }

    private record SnapshotFileMetadata(DocumentFileMetadata format, RecoverySeparators separators) {
        DocumentFileMetadata materialize() throws IOException {
            return format.withLineSeparators(separators.materialize());
        }
    }

    private record SnapshotFields(Position cursor, ViewportState viewport, Position selectionAnchor, boolean dirty,
                                  Path currentFile, SaveDisposition saveDisposition, SnapshotFileMetadata fileMetadata,
                                  FileBaseline fileBaseline, Instant createdAt) {}

    private static SnapshotFields parseSnapshotFields(Properties p, Path path) throws IOException {
        Position cursor = new Position(parseCoordinate(p, KEY_CURSOR_LINE, path), parseCoordinate(p, KEY_CURSOR_COL, path));
        ViewportState viewport = new ViewportState(
            parseCoordinate(p, KEY_TOP_LINE, path), parseCoordinate(p, KEY_TOP_WRAP_SEGMENT, path),
            parseCoordinate(p, KEY_LEFT_COLUMN, path), parseCoordinate(p, KEY_LEFT_VISUAL_COLUMN, path)
        );
        Position selection = parseSelection(p, path);
        boolean dirty = parseBoolean(p, KEY_DIRTY, path);
        Path currentFile = parseCurrentFile(p, path);
        SaveDisposition disposition = parseSaveDisposition(p, path);
        FileBaseline baseline = parseFileBaseline(p, path);
        if (currentFile == null && disposition != SaveDisposition.CREATE_NEW) {
            throw invalid(path, "A recovery snapshot without a current path cannot replace an existing file");
        }
        if (baseline != null && (currentFile == null || disposition != SaveDisposition.REPLACE_EXISTING)) {
            throw invalid(path, "A file baseline requires an existing current file");
        }
        if (DISPLAY_PATH_FORMAT.equals(p.getProperty(KEY_FORMAT))
            && (hasAmbiguousDisplayPath(currentFile) || hasAmbiguousDisplayPath(baseline == null ? null : baseline.target()))) {
            // Format 5 saved Path.toString(), which replaces undecodable Unix
            // filename bytes with U+FFFD. Even a matching digest cannot prove
            // which of two identically populated files that name referred to.
            // Keep the text recoverable, but require an explicit new Save As.
            currentFile = null;
            baseline = null;
            disposition = SaveDisposition.CREATE_NEW;
            dirty = true;
        }
        return new SnapshotFields(cursor, viewport, selection, dirty, currentFile, disposition,
            parseFileMetadata(p, path), baseline, parseInstant(p, path));
    }

    private static void requireLineCountMatches(SnapshotFields fields, int lineCount, Path path) throws IOException {
        if (fields.fileMetadata().separators().count() != lineCount - 1) {
            throw invalid(path, KEY_LINE_SEPARATORS + " count does not match recovery text line count");
        }
    }

    private Properties readMetadata(Path path) throws IOException {
        int limit = (int) Math.min(RecoveryLimits.MAX_LEGACY_METADATA_BYTES, availableHeap() / 32);
        byte[] data = readBounded(path, limit, "Recovery metadata is too large for the available memory");
        Properties properties = new Properties();
        try (InputStream in = new java.io.ByteArrayInputStream(data)) {
            properties.load(in);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed recovery metadata " + path + ": " + malformed.getMessage(), malformed);
        }
        requireExactSchema(properties, path);
        if (!usesCsvSeparators(properties)) {
            RecoveryLimits.requireSize(data.length, RecoveryLimits.MAX_METADATA_BYTES,
                "Recovery metadata exceeds its storage limit: " + path);
        }
        return properties;
    }

    private static byte[] readBounded(Path path, int maxBytes, String reason) throws IOException {
        if (Files.size(path) > maxBytes) {
            throw new IOException(reason + ": " + path + "; snapshot left unchanged");
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] data = in.readNBytes(maxBytes + 1);
            if (data.length > maxBytes) {
                throw new IOException(reason + ": " + path + "; snapshot left unchanged");
            }
            return data;
        }
    }

    private static long availableHeap() {
        Runtime runtime = Runtime.getRuntime();
        return Math.max(0L, runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()));
    }

    private final class StoredSnapshot implements PendingSnapshot {
        private final String id;
        private final String generation;

        private StoredSnapshot(String id, String generation) {
            this.id = id;
            this.generation = generation;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public RecoverySnapshot snapshot() throws IOException {
            synchronized (RecoveryStore.this) {
                ensureOpen();
                if (!entryLocks.containsKey(id)) {
                    throw new IOException("Recovery snapshot is no longer claimed: " + id);
                }
                Path meta = directory().resolve(id + META_SUFFIX);
                if (!generation.equals(readTextGeneration(meta))) {
                    throw new IOException("Recovery snapshot changed since discovery: " + id);
                }
                Entry entry = readEntry(id, meta);
                if (entry == null) {
                    throw new IOException("Recovery snapshot is no longer available: " + id);
                }
                return entry.snapshot();
            }
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
        List<String> documentLines = lines == null || lines.isEmpty() ? List.of("") : lines;
        writeAtomically(target, out -> {
            try (var writer = new BufferedWriter(new OutputStreamWriter(
                RecoveryLimits.boundedOutput(out, RecoveryLimits.MAX_TEXT_BYTES, "Recovery text exceeds its storage limit"),
                StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
                for (int i = 0; i < documentLines.size(); i++) {
                    if (i > 0) {
                        writer.write('\n');
                    }
                    writer.write(documentLines.get(i));
                }
            }
        });
    }

    private void writeSnapshotMeta(
        Path target,
        RecoverySnapshot snapshot,
        String textGeneration,
        Publication publication
    ) throws IOException {
        Properties p = new Properties();
        p.setProperty(KEY_FORMAT, FORMAT);
        p.setProperty(KEY_PATH_PLATFORM, pathPlatform());
        p.setProperty(KEY_CREATED_AT, Long.toString(snapshot.createdAt().toEpochMilli()));
        p.setProperty(KEY_TEXT_GENERATION, textGeneration);
        p.setProperty(KEY_DIRTY, Boolean.toString(snapshot.dirty()));
        p.setProperty(KEY_CURRENT_FILE_PRESENT, Boolean.toString(snapshot.currentFile() != null));
        p.setProperty(KEY_CURRENT_FILE,
            snapshot.currentFile() == null ? "" : encodePath(snapshot.currentFile(), KEY_CURRENT_FILE, target));
        p.setProperty(KEY_SAVE_DISPOSITION, snapshot.saveDisposition().name());
        DocumentFileMetadata fileMetadata = snapshot.fileMetadata();
        p.setProperty(KEY_FILE_ENCODING, fileMetadata.charset().name());
        p.setProperty(KEY_BOM_KIND, fileMetadata.bomKind().name());
        p.setProperty(KEY_DEFAULT_LINE_SEPARATOR, encodeLineSeparator(fileMetadata.defaultLineSeparator()));
        p.setProperty(KEY_LINE_SEPARATORS, RecoverySeparators.encode(fileMetadata.lineSeparators()));
        FileBaseline fileBaseline = snapshot.fileBaseline();
        p.setProperty(KEY_FILE_BASELINE_PRESENT, Boolean.toString(fileBaseline != null));
        p.setProperty(KEY_FILE_BASELINE_TARGET,
            fileBaseline == null ? "" : encodePath(fileBaseline.target(), KEY_FILE_BASELINE_TARGET, target));
        p.setProperty(KEY_FILE_BASELINE_SHA256, fileBaseline == null ? "" : fileBaseline.sha256());
        p.setProperty(KEY_FILE_BASELINE_BYTE_SIZE, fileBaseline == null ? "" : Long.toString(fileBaseline.byteSize()));
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
        writeAtomically(target, out -> p.store(
            RecoveryLimits.boundedOutput(out, RecoveryLimits.MAX_METADATA_BYTES, "Recovery metadata exceeds its storage limit"),
            "TUInk recovery snapshot"), publication);
    }

    private interface Writer {
        void write(OutputStream out) throws IOException;
    }

    private void writeAtomically(Path target, Writer writer) throws IOException {
        writeAtomically(target, writer, new Publication());
    }

    private void writeAtomically(Path target, Writer writer, Publication publication) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            ensurePrivateDirectory(parent);
        }
        Path tmp = createPrivateTempFile(parent, target.getFileName().toString());
        Throwable failure = null;
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                writer.write(out);
            }
            setPrivateFilePermissions(tmp);
            publication.attempt();
            publicationFiles.publish(tmp, target);
            // The outer generation transaction must see this before cleanup can
            // fail. It must never delete a payload now referenced by metadata.
            publication.commit();
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            try {
                publicationFiles.deleteIfExists(tmp);
            } catch (IOException | RuntimeException | OutOfMemoryError cleanupFailure) {
                if (!publication.committed()) {
                    if (failure == null) throw cleanupFailure;
                    suppressCleanupFailure(failure, cleanupFailure);
                }
                // Metadata already names the committed generation. Do not turn a
                // harmless private temp orphan into a false failed-save signal.
            }
        }
    }

    private static void suppressCleanupFailure(Throwable failure, Throwable cleanupFailure) {
        if (failure == cleanupFailure) return;
        try {
            failure.addSuppressed(cleanupFailure);
        } catch (OutOfMemoryError exhausted) {
            // Recording cleanup diagnostics cannot replace the primary failure.
        }
    }

    private static Path defaultDirectory() throws IOException {
        return UserDirectories.configDirectory().resolve(RECOVERY_DIR);
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
        if (!FORMAT.equals(p.getProperty(KEY_FORMAT)) && !PREVIOUS_FORMAT.equals(p.getProperty(KEY_FORMAT))
            && !LEGACY_FORMAT.equals(p.getProperty(KEY_FORMAT)) && !DISPLAY_PATH_FORMAT.equals(p.getProperty(KEY_FORMAT))) {
            throw invalid(path, "unsupported or missing format marker");
        }
        Set<String> requiredKeys = new java.util.HashSet<>(REQUIRED_KEYS);
        if (FORMAT.equals(p.getProperty(KEY_FORMAT))) requiredKeys.add(KEY_FILE_BASELINE_BYTE_SIZE);
        if (DISPLAY_PATH_FORMAT.equals(p.getProperty(KEY_FORMAT))) requiredKeys.remove(KEY_PATH_PLATFORM);
        if (!p.stringPropertyNames().equals(requiredKeys)) {
            Set<String> missing = new java.util.HashSet<>(requiredKeys);
            missing.removeAll(p.stringPropertyNames());
            Set<String> unknown = new java.util.HashSet<>(p.stringPropertyNames());
            unknown.removeAll(requiredKeys);
            throw invalid(path, "schema mismatch; missing=" + missing + ", unknown=" + unknown);
        }
        if (!DISPLAY_PATH_FORMAT.equals(p.getProperty(KEY_FORMAT))
            && !pathPlatform().equals(p.getProperty(KEY_PATH_PLATFORM))) {
            throw invalid(path, KEY_PATH_PLATFORM + " does not match this platform");
        }
    }

    private static String pathPlatform() {
        return FileSystems.getDefault().getSeparator().equals("\\") ? "windows" : "unix";
    }

    private static String encodePath(Path value, String key, Path metadata) throws IOException {
        if (value.getFileSystem() != FileSystems.getDefault()) {
            throw invalid(metadata, key + " must use the native filesystem");
        }
        String encoded = filePathUri(value);
        if (!value.equals(parsePath(encoded, key, metadata))) {
            throw invalid(metadata, key + " cannot be represented by a lossless file URI");
        }
        return encoded;
    }

    private static Path parsePath(String encoded, String key, Path metadata) throws IOException {
        try {
            URI uri = URI.create(encoded);
            if (!encoded.equals(uri.toASCIIString()) || !"file".equals(uri.getScheme()) || uri.isOpaque()
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("not a canonical ASCII file URI");
            }
            Path value = Path.of(uri);
            if (value.getFileSystem() != FileSystems.getDefault() || !value.isAbsolute()
                || !value.equals(value.normalize()) || !encoded.equals(filePathUri(value))) {
                throw new IllegalArgumentException("not an absolute normalized native file URI");
            }
            return value;
        } catch (RuntimeException malformed) {
            throw invalid(metadata, key + " must be a canonical ASCII file URI for an absolute normalized native path");
        }
    }

    private static String filePathUri(Path value) {
        String encoded = value.toUri().toASCIIString();
        // Path.toUri may append '/' after inspecting the current target type.
        // A document's name must round-trip even if another process replaces
        // its file with a directory and later removes that directory.
        return value.getFileName() != null && encoded.endsWith("/")
            ? encoded.substring(0, encoded.length() - 1) : encoded;
    }

    private static boolean hasAmbiguousDisplayPath(Path value) {
        return value != null && value.toString().indexOf('\uFFFD') >= 0;
    }

    private static Path parseStoredPath(Properties p, String raw, String key, Path metadata) throws IOException {
        if (!DISPLAY_PATH_FORMAT.equals(p.getProperty(KEY_FORMAT))) {
            return parsePath(raw, key, metadata);
        }
        try {
            // The v0.13.0 writer stored absolute normalized native display
            // strings. Do not resolve relative paths against this process's
            // working directory or interpret percent escapes as a file URI.
            Path value = Path.of(raw);
            if (value.getFileSystem() != FileSystems.getDefault() || !value.isAbsolute()
                || !value.equals(value.normalize()) || !raw.equals(value.toString())
                || raw.codePoints().anyMatch(codePoint -> codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                throw new IllegalArgumentException("not an absolute normalized native display path");
            }
            return value;
        } catch (RuntimeException malformed) {
            throw invalid(metadata, key + " in format 5 must be an absolute normalized native display path; "
                + "restore with the old TUInk binary on the original platform and use Save As to keep the draft");
        }
    }

    private static boolean usesCsvSeparators(Properties properties) {
        String format = properties.getProperty(KEY_FORMAT);
        return DISPLAY_PATH_FORMAT.equals(format) || LEGACY_FORMAT.equals(format);
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
        return parseTextGeneration(readMetadata(metaPath), metaPath);
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

    private static SnapshotFileMetadata parseFileMetadata(Properties p, Path path)
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
        RecoverySeparators separators = RecoverySeparators.parse(p.getProperty(KEY_LINE_SEPARATORS),
            usesCsvSeparators(p));
        try {
            DocumentFileMetadata metadata = new DocumentFileMetadata(
                charset,
                bomKind,
                defaultLineSeparator,
                List.of()
            );
            return new SnapshotFileMetadata(metadata, separators);
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
        return parseStoredPath(p, raw, KEY_CURRENT_FILE, path);
    }

    private static FileBaseline parseFileBaseline(Properties p, Path path) throws IOException {
        boolean present = parseBoolean(p, KEY_FILE_BASELINE_PRESENT, path);
        String rawTarget = p.getProperty(KEY_FILE_BASELINE_TARGET);
        String sha256 = p.getProperty(KEY_FILE_BASELINE_SHA256);
        String rawSize = p.getProperty(KEY_FILE_BASELINE_BYTE_SIZE);
        if (!present) {
            if (!rawTarget.isEmpty() || !sha256.isEmpty() || rawSize != null && !rawSize.isEmpty()) {
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
        long byteSize = -1L;
        if (rawSize != null) {
            try {
                byteSize = Long.parseLong(rawSize);
                if (byteSize < -1L || !Long.toString(byteSize).equals(rawSize)) throw new NumberFormatException();
            } catch (NumberFormatException malformed) {
                throw invalid(path, KEY_FILE_BASELINE_BYTE_SIZE + " must be nonnegative or unknown (-1)");
            }
        }
        return new FileBaseline(parseStoredPath(p, rawTarget, KEY_FILE_BASELINE_TARGET, path), sha256, byteSize);
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

    public interface PendingSnapshot {
        String id();

        RecoverySnapshot snapshot() throws IOException;
    }

    public record Entry(String id, RecoverySnapshot snapshot) implements PendingSnapshot {
    }

    public record DiscoveryResult(List<PendingSnapshot> entries, List<SkippedSnapshot> skipped) {
        public DiscoveryResult {
            entries = List.copyOf(entries);
            skipped = List.copyOf(skipped);
        }
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
