package dev.tuiframe.explorer.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reusable terminal/widget-independent filesystem model for the open-file tree.
 *
 * <p>The model deliberately contains no terminal geometry, styles, selection,
 * scrolling, pointer state or tree glyphs. Structural entries are published as
 * a flat snapshot; directory scans, sorting and optional metadata run
 * asynchronously and announce a monotonic revision to the UI thread. Instance calls other than
 * {@link #snapshot()} are confined to the owning UI thread; returned snapshots
 * and the values reachable from them are immutable.</p>
 */
public final class FileTreeModel implements AutoCloseable {
    private static final String UNKNOWN_DATE = "--.--.-- --:--";
    private static final String PENDING_META = "...";
    private static final int MIN_METADATA_BATCH_SIZE = 16;
    private static final int MAX_METADATA_PUBLICATIONS = 8;
    private static final DateTimeFormatter DATE_FORMATTER =
        DateTimeFormatter.ofPattern("dd.MM.yy HH:mm", Locale.ROOT);

    private final Set<Path> expandedDirectories = new HashSet<>();
    private final Object loaderLock = new Object();
    private final AtomicLong snapshotSignal = new AtomicLong();
    private final DirectoryScanner directoryScanner;
    private final FileTreeLoadBudget.Limits loadLimits;
    private final ThreadFactory workerFactory;
    private final boolean includeParentEntry;
    private final boolean includeRootEntry;
    private Map<Path, CachedDirectory> directoryCache = Map.of();

    private Path root;
    private boolean showSizeColumn;
    private boolean showDateColumn;
    private boolean showHiddenFiles;
    private volatile Snapshot snapshot;
    private long consumedSnapshotSignal;
    private long revision;
    private long loadGeneration;
    private Thread loadWorker;
    private boolean closed;

    public FileTreeModel(Path root, boolean showSizeColumn, boolean showDateColumn) {
        this(root, showSizeColumn, showDateColumn, FileTreeModel::scanDirectory);
    }

    /** Creates a tree with optional navigation above its root. */
    public FileTreeModel(Path root, boolean showSizeColumn, boolean showDateColumn, boolean includeParentEntry) {
        this(root, showSizeColumn, showDateColumn, FileTreeModel::scanDirectory,
            FileTreeLoadBudget.DEFAULT_LIMITS, operation -> new Thread(operation, "tuink-tree-loader"),
            includeParentEntry);
    }

    /** Creates an explorer with a selectable root or a picker with optional parent navigation. */
    public FileTreeModel(Path root, boolean showSizeColumn, boolean showDateColumn,
                         boolean includeParentEntry, boolean includeRootEntry) {
        this(root, showSizeColumn, showDateColumn, FileTreeModel::scanDirectory,
            FileTreeLoadBudget.DEFAULT_LIMITS, operation -> new Thread(operation, "tuink-tree-loader"),
            includeParentEntry, includeRootEntry);
    }

    FileTreeModel(Path root, boolean showSizeColumn, boolean showDateColumn, DirectoryScanner directoryScanner) {
        this(root, showSizeColumn, showDateColumn, directoryScanner, FileTreeLoadBudget.DEFAULT_LIMITS);
    }

    FileTreeModel(Path root, boolean showSizeColumn, boolean showDateColumn, DirectoryScanner directoryScanner,
                  FileTreeLoadBudget.Limits loadLimits) {
        this(root, showSizeColumn, showDateColumn, directoryScanner, loadLimits,
            operation -> new Thread(operation, "tuink-tree-loader"));
    }

    FileTreeModel(Path root, boolean showSizeColumn, boolean showDateColumn, DirectoryScanner directoryScanner,
                  FileTreeLoadBudget.Limits loadLimits, ThreadFactory workerFactory) {
        this(root, showSizeColumn, showDateColumn, directoryScanner, loadLimits, workerFactory, true);
    }

    FileTreeModel(Path root, boolean showSizeColumn, boolean showDateColumn, DirectoryScanner directoryScanner,
                  FileTreeLoadBudget.Limits loadLimits, ThreadFactory workerFactory, boolean includeParentEntry) {
        this(root, showSizeColumn, showDateColumn, directoryScanner, loadLimits, workerFactory, includeParentEntry, false);
    }

    FileTreeModel(Path root, boolean showSizeColumn, boolean showDateColumn, DirectoryScanner directoryScanner,
                  FileTreeLoadBudget.Limits loadLimits, ThreadFactory workerFactory,
                  boolean includeParentEntry, boolean includeRootEntry) {
        this.directoryScanner = Objects.requireNonNull(directoryScanner, "directoryScanner");
        this.loadLimits = Objects.requireNonNull(loadLimits, "loadLimits");
        this.workerFactory = Objects.requireNonNull(workerFactory, "workerFactory");
        this.includeParentEntry = includeParentEntry;
        this.includeRootEntry = includeRootEntry;
        this.showSizeColumn = showSizeColumn;
        this.showDateColumn = showDateColumn;
        this.snapshot = new Snapshot(
            Objects.requireNonNull(root, "root").toAbsolutePath().normalize(),
            0L,
            List.of()
        );
        setRoot(root);
    }

    public Path root() {
        return root;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public boolean showHiddenFiles() {
        return showHiddenFiles;
    }

    public boolean showSizeColumn() {
        return showSizeColumn;
    }

    public boolean showDateColumn() {
        return showDateColumn;
    }

    public void setRoot(Path root) {
        ensureOpen();
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        expandedDirectories.clear();
        if (includeRootEntry) expandedDirectories.add(this.root);
        rebuildFresh();
    }

    /** Reloads the root and expanded directories without changing their expansion state. */
    public void refresh() {
        ensureOpen();
        rebuildFresh();
    }

    /** Invalidates one directory listing; unrelated expanded directories reuse their last scan. */
    public void refreshDirectory(Path directory) {
        ensureOpen();
        Path target = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (!target.startsWith(root)) return;
        synchronized (loaderLock) {
            var next = new HashMap<>(directoryCache);
            next.remove(target);
            directoryCache = Map.copyOf(next);
            boolean visible = target.equals(root) || snapshot.entries().stream().anyMatch(entry ->
                entry.kind() == Kind.DIRECTORY && entry.path().equals(target) && entry.expanded());
            if (visible) rebuild();
        }
    }

    /** Expands the ancestor chain in one load, for programmatic reveal after a file operation. */
    public boolean reveal(Path path) {
        ensureOpen();
        Path target = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (!target.startsWith(root)) return false;
        if (includeRootEntry) expandedDirectories.add(root);
        Path parent = target.getParent();
        while (parent != null && parent.startsWith(root)) {
            expandedDirectories.add(parent);
            if (parent.equals(root)) break;
            parent = parent.getParent();
        }
        rebuild();
        return true;
    }

    public void setShowHiddenFiles(boolean enabled) {
        ensureOpen();
        if (showHiddenFiles == enabled) {
            return;
        }
        showHiddenFiles = enabled;
        rebuildFresh();
    }

    public void setShowSizeColumn(boolean enabled) {
        ensureOpen();
        if (showSizeColumn == enabled) {
            return;
        }
        showSizeColumn = enabled;
        rebuildFresh();
    }

    public void setShowDateColumn(boolean enabled) {
        ensureOpen();
        if (showDateColumn == enabled) {
            return;
        }
        showDateColumn = enabled;
        rebuildFresh();
    }

    public boolean toggleDirectory(EntryId id) {
        Objects.requireNonNull(id, "id");
        return setDirectoryExpanded(id, !expandedDirectories.contains(id.path()));
    }

    public boolean setDirectoryExpanded(EntryId id, boolean expanded) {
        ensureOpen();
        Objects.requireNonNull(id, "id");
        Entry entry = findEntry(id);
        if (entry == null || entry.kind() != Kind.DIRECTORY) {
            return false;
        }
        Path key = entry.path().toAbsolutePath().normalize();
        boolean changed = expanded ? expandedDirectories.add(key) : expandedDirectories.remove(key);
        if (changed) {
            rebuild();
        }
        return changed;
    }

    /** Returns true when loading, structural or metadata snapshots changed since the previous call. */
    public boolean consumeMetadataChanged() {
        long current = snapshotSignal.get();
        if (current == consumedSnapshotSignal) {
            return false;
        }
        consumedSnapshotSignal = current;
        return true;
    }

    @Override
    public void close() {
        Thread previous;
        synchronized (loaderLock) {
            if (closed) {
                return;
            }
            closed = true;
            loadGeneration++;
            previous = loadWorker;
            loadWorker = null;
        }
        if (previous != null) {
            previous.interrupt();
        }
    }

    private void rebuildFresh() {
        synchronized (loaderLock) {
            directoryCache = Map.of();
            rebuild();
        }
    }

    private void rebuild() {
        Thread previous;
        Thread worker;
        LoadRequest request;
        synchronized (loaderLock) {
            long generation = ++loadGeneration;
            long loadingRevision = ++revision;
            // Reserve a revision after every possible progressive publication. The worker can
            // then publish a preallocated terminal snapshot even if it cannot allocate anymore.
            revision += MAX_METADATA_PUBLICATIONS + 2L;
            request = new LoadRequest(
                root, Set.copyOf(expandedDirectories), showHiddenFiles, showSizeColumn, showDateColumn, generation,
                Map.copyOf(directoryCache),
                failureSnapshot(root, revision, "not enough memory to load directory", FailureKind.MEMORY),
                failureSnapshot(root, revision, "directory listing failed", FailureKind.READ),
                failureSnapshot(root, revision, "directory loading cancelled", FailureKind.CANCELLED)
            );
            previous = loadWorker;
            Snapshot loading = new Snapshot(root, loadingRevision, loadingEntries(request), true);
            try {
                worker = workerFactory.newThread(() -> load(request));
                if (worker != null) {
                    worker.setDaemon(true);
                }
            } catch (OutOfMemoryError exhausted) {
                worker = null;
            }
            snapshot = worker == null ? request.memoryFailure() : loading;
            loadWorker = worker;
            snapshotSignal.incrementAndGet();
        }
        if (previous != null) {
            previous.interrupt();
        }
        if (worker == null) {
            return;
        }
        try {
            worker.start();
        } catch (OutOfMemoryError exhausted) {
            publishFailureSnapshot(request.memoryFailure(), request.generation());
            synchronized (loaderLock) {
                if (loadWorker == worker) {
                    loadWorker = null;
                }
            }
        }
    }

    private List<Entry> loadingEntries(LoadRequest request) {
        if (!snapshot.root().equals(request.root()) || snapshot.entries().isEmpty()) {
            return initialEntries(request.root(), request.expanded());
        }
        List<Entry> retained = new ArrayList<>(snapshot.entries().size());
        int collapsedDepth = Integer.MAX_VALUE;
        for (Entry entry : snapshot.entries()) {
            if (entry.depth() > collapsedDepth) {
                continue;
            }
            collapsedDepth = Integer.MAX_VALUE;
            boolean expanded = entry.kind() == Kind.DIRECTORY && request.expanded().contains(entry.path());
            retained.add(entry.withExpanded(expanded));
            if (entry.kind() == Kind.DIRECTORY && !expanded) {
                collapsedDepth = entry.depth();
            }
        }
        return retained;
    }

    private List<Entry> initialEntries(Path root, Set<Path> expanded) {
        List<Entry> entries = parentEntries(root, includeParentEntry);
        if (includeRootEntry) {
            String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
            entries.add(new Entry(new EntryId(root, Kind.DIRECTORY), name, Kind.DIRECTORY,
                expanded.contains(root), 0, "", ""));
        }
        return entries;
    }

    private static List<Entry> parentEntries(Path root, boolean includeParentEntry) {
        List<Entry> entries = new ArrayList<>();
        Path parent = root.getParent();
        if (includeParentEntry && parent != null) {
            entries.add(new Entry(
                new EntryId(parent, Kind.PARENT),
                "..",
                Kind.PARENT,
                false,
                0,
                "",
                ""
            ));
        }
        return entries;
    }

    private void load(LoadRequest request) {
        try {
            LoadState state = new LoadState(initialEntries(request.root(), request.expanded()), loadLimits);
            appendTreeEntries(state, request);
            checkCurrentGeneration(request.generation());
            synchronized (loaderLock) {
                if (!closed && loadGeneration == request.generation()) directoryCache = Map.copyOf(state.directories);
            }
            publishSnapshot(state.entries, state.failures, request.generation());
            enrichMetadata(state, request);
        } catch (CancellationException cancelled) {
            // Superseded requests cannot publish; cancellation of the current request is terminal.
            publishFailureSnapshot(request.cancelled(), request.generation());
        } catch (OutOfMemoryError exhausted) {
            publishFailureSnapshot(request.memoryFailure(), request.generation());
        } catch (RuntimeException unexpected) {
            publishFailureSnapshot(request.readFailure(), request.generation());
        } finally {
            synchronized (loaderLock) {
                if (loadGeneration == request.generation() && loadWorker == Thread.currentThread()) {
                    loadWorker = null;
                }
            }
        }
    }

    private void appendTreeEntries(LoadState state, LoadRequest request) {
        ArrayDeque<DirectoryFrame> pending = new ArrayDeque<>();
        Path scanningDirectory = request.root();
        int scanningDepth = includeRootEntry ? 1 : 0;
        if (includeRootEntry && !request.expanded().contains(request.root())) return;
        try {
            pending.push(scanFrame(scanningDirectory, scanningDepth, null, state, request));
            while (!pending.isEmpty()) {
                checkCurrentGeneration(request.generation());
                DirectoryFrame frame = pending.peek();
                if (frame.nextChild == frame.children.size()) {
                    pending.pop();
                    continue;
                }
                ScannedEntry child = frame.children.set(frame.nextChild++, null);
                boolean directoryEntry = child.directory();
                Path normalized = child.path().toAbsolutePath().normalize();
                boolean expanded = directoryEntry && request.expanded().contains(normalized);
                Kind kind = directoryEntry ? Kind.DIRECTORY
                    : child.attributes() != null && child.attributes().isRegularFile() ? Kind.FILE : Kind.ERROR;
                String name = kind == Kind.ERROR ? child.name() + " <not a regular file>" : child.name();
                state.add(new Entry(
                    new EntryId(normalized, kind), name, kind, expanded, frame.depth,
                    kind == Kind.ERROR || !request.showSizeColumn() ? "" : PENDING_META,
                    kind == Kind.ERROR || !request.showDateColumn() ? "" : PENDING_META
                ).withIdentity(child.attributes()), child.attributes());
                if (expanded) {
                    scanningDirectory = normalized;
                    scanningDepth = frame.depth + 1;
                    pending.push(scanFrame(scanningDirectory, scanningDepth, DirectoryIdentity.of(child.attributes()), state, request));
                }
            }
        } catch (FileTreeLoadBudget.LimitReachedException limited) {
            addFailure(state, scanningDirectory, scanningDepth, limited.getMessage(), FailureKind.LIMIT);
        }
    }

    private DirectoryFrame scanFrame(Path directory, int depth, DirectoryIdentity identity,
                                     LoadState state, LoadRequest request) {
        List<ScannedEntry> children = new ArrayList<>();
        try {
            CachedDirectory cached = request.directories().get(directory);
            if (cached != null && Objects.equals(cached.identity(), identity)) {
                for (ScannedEntry child : cached.children()) {
                    checkCurrentGeneration(request.generation());
                    state.budget.reserveEntry(child.path());
                    children.add(child);
                }
            } else {
                directoryScanner.scan(directory, request.showHiddenFiles(), (path, attributes) -> {
                    checkCurrentGeneration(request.generation());
                    state.budget.reserveEntry(path);
                    children.add(new ScannedEntry(path, attributes));
                });
                checkCurrentGeneration(request.generation());
                Comparator<ScannedEntry> order = Comparator.comparing((ScannedEntry child) -> !child.directory())
                    .thenComparing(ScannedEntry::sortName)
                    .thenComparing(ScannedEntry::name);
                children.sort((left, right) -> {
                    checkInterrupted();
                    return order.compare(left, right);
                });
            }
            state.directories.put(directory, new CachedDirectory(identity, List.copyOf(children)));
        } catch (CancellationException | FileTreeLoadBudget.LimitReachedException stopped) {
            throw stopped;
        } catch (IOException | RuntimeException failed) {
            state.budget.reserveEntry(directory);
            FileFailureReason.Description described = FileFailureReason.describe(failed);
            String reason = described.detail() == null ? failed.getClass().getSimpleName() : described.detail();
            if (reason.length() > 240) reason = reason.substring(0, 240) + "…";
            addFailure(state, directory, depth, reason, FailureKind.READ);
            children.clear();
        }
        return new DirectoryFrame(children, depth);
    }

    private static void addFailure(LoadState state, Path directory, int depth, String reason, FailureKind kind) {
        state.failures.add(new DirectoryFailure(directory, reason, kind));
        // One terminal limit row is allowed beyond the admission budget so the reason remains visible.
        state.add(new Entry(new EntryId(directory, Kind.ERROR), "<cannot read directory: " + reason + ">",
            Kind.ERROR, false, depth, "", ""), null);
    }

    static void scanDirectory(Path directory, boolean showHiddenFiles, ScannedEntryConsumer consumer) throws IOException {
        try (var stream = Files.list(directory)) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                checkInterrupted();
                Path path = iterator.next();
                if (!isVisiblePath(path, showHiddenFiles)) {
                    continue;
                }
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(path, BasicFileAttributes.class);
                } catch (IOException ignored) {
                    attributes = null;
                }
                consumer.accept(path, attributes);
            }
        }
    }

    private static boolean isVisiblePath(Path path, boolean showHiddenFiles) {
        if (showHiddenFiles) {
            return true;
        }
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        if (name.startsWith(".")) {
            return false;
        }
        try {
            return !Files.isHidden(path);
        } catch (IOException ignored) {
            return true;
        }
    }

    private void enrichMetadata(LoadState state, LoadRequest request) {
        if (!request.showSizeColumn() && !request.showDateColumn()) {
            return;
        }
        List<Entry> entries = state.entries;
        // Preserve progressive updates without making full-list publication quadratic.
        int publishBatchSize = Math.max(
            MIN_METADATA_BATCH_SIZE,
            (int) (((long) entries.size() + MAX_METADATA_PUBLICATIONS - 1L) / MAX_METADATA_PUBLICATIONS)
        );
        int changedBatch = 0;
        for (int index = 0; index < entries.size(); index++) {
            checkCurrentGeneration(request.generation());
            Entry entry = entries.get(index);
            if (entry.kind() == Kind.PARENT || entry.kind() == Kind.ERROR || state.attributes.get(index) == null) {
                continue;
            }
            BasicFileAttributes entryAttributes = state.attributes.get(index);
            String size = !request.showSizeColumn() ? ""
                : entry.directory() ? formatDirectoryChildCount(entry.path(), state.budget)
                    : humanReadableSize(entryAttributes.size());
            String date = buildDateValue(entryAttributes, request.showDateColumn());
            Entry updated = entry.withMetadata(size, date);
            if (updated != entry) {
                entries.set(index, updated);
                changedBatch++;
                if (changedBatch >= publishBatchSize) {
                    publishSnapshot(entries, state.failures, request.generation());
                    changedBatch = 0;
                }
            }
        }
        if (changedBatch > 0) {
            publishSnapshot(entries, state.failures, request.generation());
        }
    }

    private void publishSnapshot(List<Entry> entries, List<DirectoryFailure> failures, long generation) {
        synchronized (loaderLock) {
            if (closed || loadGeneration != generation) {
                return;
            }
            snapshot = new Snapshot(snapshot.root(), snapshot.revision() + 1L, entries, false, failures);
            snapshotSignal.incrementAndGet();
        }
    }

    private void publishFailureSnapshot(Snapshot failure, long generation) {
        synchronized (loaderLock) {
            if (!closed && loadGeneration == generation) {
                snapshot = failure;
                snapshotSignal.incrementAndGet();
            }
        }
    }

    private Snapshot failureSnapshot(Path root, long revision, String reason, FailureKind kind) {
        List<Entry> entries = initialEntries(root, expandedDirectories);
        entries.add(new Entry(new EntryId(root, Kind.ERROR),
            "<cannot read directory: " + reason + ">", Kind.ERROR, false, 0, "", ""));
        return new Snapshot(root, revision, entries, false,
            List.of(new DirectoryFailure(root, reason, kind)));
    }

    private void checkCurrentGeneration(long generation) {
        checkInterrupted();
        synchronized (loaderLock) {
            if (closed || loadGeneration != generation) {
                throw new CancellationException();
            }
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    private Entry findEntry(EntryId id) {
        for (Entry entry : snapshot.entries()) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    private static String buildDateValue(BasicFileAttributes attributes, boolean includeDate) {
        if (!includeDate) {
            return "";
        }
        try {
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                attributes.lastModifiedTime().toInstant(),
                ZoneId.systemDefault()
            );
            return DATE_FORMATTER.format(dateTime);
        } catch (RuntimeException ignored) {
            return UNKNOWN_DATE;
        }
    }

    private static String formatDirectoryChildCount(Path path, FileTreeLoadBudget budget) {
        try (var stream = Files.list(path)) {
            long count = 0;
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                checkInterrupted();
                if (!budget.inspectMetadataEntry()) {
                    return count == 0L ? "? items" : count + "+ items";
                }
                iterator.next();
                count++;
            }
            return count + " items";
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (IOException | RuntimeException ignored) {
            return "? items";
        }
    }

    private static String humanReadableSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + "B";
        }
        final String[] units = {"K", "M", "G", "T", "P"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        String format = value >= 10.0 ? "%.0f%s" : "%.1f%s";
        return String.format(Locale.ROOT, format, value, units[unit]);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("FileTreeModel is closed");
        }
    }

    public enum Kind {
        PARENT,
        DIRECTORY,
        FILE,
        ERROR
    }

    public record EntryId(Path path, Kind kind) {
        public EntryId {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            Objects.requireNonNull(kind, "kind");
        }
    }

    @FunctionalInterface
    interface DirectoryScanner {
        void scan(Path directory, boolean showHiddenFiles, ScannedEntryConsumer consumer) throws IOException;
    }

    @FunctionalInterface
    interface ScannedEntryConsumer {
        void accept(Path path, BasicFileAttributes attributes);
    }

    record ScannedEntry(Path path, BasicFileAttributes attributes, String name, String sortName) {
        ScannedEntry(Path path, BasicFileAttributes attributes) {
            this(path, attributes,
                path.getFileName() == null ? path.toString() : path.getFileName().toString(),
                (path.getFileName() == null ? path.toString() : path.getFileName().toString()).toLowerCase(Locale.ROOT));
        }

        boolean directory() {
            return attributes != null && attributes.isDirectory();
        }
    }

    private record DirectoryIdentity(Object fileKey, FileTime creationTime) {
        static DirectoryIdentity of(BasicFileAttributes attributes) {
            if (attributes == null) return null;
            Object key = attributes.fileKey();
            // Without a stable key, only the exact retained parent scan may reuse this branch.
            return new DirectoryIdentity(key == null ? attributes : key, attributes.creationTime());
        }
    }

    private record CachedDirectory(DirectoryIdentity identity, List<ScannedEntry> children) {}

    private record LoadRequest(
        Path root, Set<Path> expanded, boolean showHiddenFiles,
        boolean showSizeColumn, boolean showDateColumn, long generation,
        Map<Path, CachedDirectory> directories,
        Snapshot memoryFailure, Snapshot readFailure, Snapshot cancelled
    ) {
    }

    private static final class LoadState {
        private final List<Entry> entries;
        private final List<BasicFileAttributes> attributes = new ArrayList<>();
        private final List<DirectoryFailure> failures = new ArrayList<>();
        private final Map<Path, CachedDirectory> directories = new HashMap<>();
        private final FileTreeLoadBudget budget;

        private LoadState(List<Entry> initialEntries, FileTreeLoadBudget.Limits limits) {
            entries = initialEntries;
            for (int index = 0; index < entries.size(); index++) {
                attributes.add(null);
            }
            budget = new FileTreeLoadBudget(limits);
        }

        private void add(Entry entry, BasicFileAttributes entryAttributes) {
            entries.add(entry);
            attributes.add(entryAttributes);
        }
    }

    private static final class DirectoryFrame {
        private final List<ScannedEntry> children;
        private final int depth;
        private int nextChild;

        private DirectoryFrame(List<ScannedEntry> children, int depth) {
            this.children = children;
            this.depth = depth;
        }
    }

    public enum FailureKind {
        READ,
        LIMIT,
        MEMORY,
        CANCELLED
    }

    public enum LoadStatus {
        LOADING,
        READY,
        FAILED,
        LIMITED,
        CANCELLED
    }

    public record DirectoryFailure(Path directory, String reason, FailureKind kind) {
        public DirectoryFailure(Path directory, String reason) {
            this(directory, reason, FailureKind.READ);
        }

        public DirectoryFailure {
            Objects.requireNonNull(directory, "directory");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(kind, "kind");
        }
    }

    public record Snapshot(Path root, long revision, List<Entry> entries, LoadStatus status,
                           List<DirectoryFailure> failures) {
        public Snapshot(Path root, long revision, List<Entry> entries) {
            this(root, revision, entries, false, List.of());
        }

        public Snapshot(Path root, long revision, List<Entry> entries, boolean loading) {
            this(root, revision, entries, loading, List.of());
        }

        public Snapshot(Path root, long revision, List<Entry> entries, boolean loading,
                        List<DirectoryFailure> failures) {
            this(root, revision, entries, loading ? LoadStatus.LOADING : terminalStatus(failures), failures);
        }

        public boolean loading() {
            return status == LoadStatus.LOADING;
        }

        private static LoadStatus terminalStatus(List<DirectoryFailure> failures) {
            if (failures.stream().anyMatch(failure -> failure.kind() == FailureKind.LIMIT)) {
                return LoadStatus.LIMITED;
            }
            if (failures.stream().anyMatch(failure -> failure.kind() == FailureKind.CANCELLED)) {
                return LoadStatus.CANCELLED;
            }
            return failures.isEmpty() ? LoadStatus.READY : LoadStatus.FAILED;
        }

        public Snapshot {
            root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must be non-negative");
            }
            entries = List.copyOf(entries);
            Objects.requireNonNull(status, "status");
            failures = List.copyOf(failures);
        }
    }

    /** Immutable filesystem entry published as part of one exact model snapshot. */
    public static final class Entry {
        private final EntryId id;
        private final String name;
        private final Kind kind;
        private final boolean expanded;
        private final int depth;
        private final String sizeValue;
        private final String dateValue;
        private final BasicFileAttributes identity;

        private Entry(
            EntryId id,
            String name,
            Kind kind,
            boolean expanded,
            int depth,
            String sizeValue,
            String dateValue
        ) {
            this(id, name, kind, expanded, depth, sizeValue, dateValue, null);
        }

        private Entry(EntryId id, String name, Kind kind, boolean expanded, int depth,
            String sizeValue, String dateValue, BasicFileAttributes identity) {
            this.id = Objects.requireNonNull(id, "id");
            this.name = Objects.requireNonNull(name, "name");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.expanded = expanded;
            this.depth = Math.max(0, depth);
            this.sizeValue = Objects.requireNonNull(sizeValue, "sizeValue");
            this.dateValue = Objects.requireNonNull(dateValue, "dateValue");
            this.identity = identity;
        }

        public EntryId id() {
            return id;
        }

        public Path path() {
            return id.path();
        }

        public String name() {
            return name;
        }

        public Kind kind() {
            return kind;
        }

        public boolean directory() {
            return kind == Kind.PARENT || kind == Kind.DIRECTORY;
        }

        public boolean expanded() {
            return expanded;
        }

        public int depth() {
            return depth;
        }

        public String sizeValue() {
            return sizeValue;
        }

        public String dateValue() {
            return dateValue;
        }

        /** Identity already observed by the scanner; no I/O is performed by this accessor. */
        public ExplorerFileService.Target target() {
            return identity == null ? null : new ExplorerFileService.Target(path(), identity.fileKey(),
                identity.isDirectory(), identity.isSymbolicLink(), identity.size(), identity.lastModifiedTime());
        }

        private Entry withIdentity(BasicFileAttributes attributes) {
            return new Entry(id, name, kind, expanded, depth, sizeValue, dateValue, attributes);
        }

        private Entry withMetadata(String sizeValue, String dateValue) {
            String nextSize = sizeValue == null ? "" : sizeValue;
            String nextDate = dateValue == null ? "" : dateValue;
            if (this.sizeValue.equals(nextSize) && this.dateValue.equals(nextDate)) {
                return this;
            }
            return new Entry(id, name, kind, expanded, depth, nextSize, nextDate).withIdentity(identity);
        }

        private Entry withExpanded(boolean expanded) {
            return this.expanded == expanded ? this
                : new Entry(id, name, kind, expanded, depth, sizeValue, dateValue).withIdentity(identity);
        }
    }
}
