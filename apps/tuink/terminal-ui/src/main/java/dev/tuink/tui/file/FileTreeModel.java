package dev.tuink.tui.file;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TUInk-owned terminal/widget-independent filesystem model for the open-file tree.
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

    FileTreeModel(Path root, boolean showSizeColumn, boolean showDateColumn, DirectoryScanner directoryScanner) {
        this.directoryScanner = Objects.requireNonNull(directoryScanner, "directoryScanner");
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
        rebuild();
    }

    public void setShowHiddenFiles(boolean enabled) {
        ensureOpen();
        if (showHiddenFiles == enabled) {
            return;
        }
        showHiddenFiles = enabled;
        rebuild();
    }

    public void setShowSizeColumn(boolean enabled) {
        ensureOpen();
        if (showSizeColumn == enabled) {
            return;
        }
        showSizeColumn = enabled;
        rebuild();
    }

    public void setShowDateColumn(boolean enabled) {
        ensureOpen();
        if (showDateColumn == enabled) {
            return;
        }
        showDateColumn = enabled;
        rebuild();
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

    private void rebuild() {
        Thread previous;
        Thread worker;
        synchronized (loaderLock) {
            long generation = ++loadGeneration;
            LoadRequest request = new LoadRequest(
                root, Set.copyOf(expandedDirectories), showHiddenFiles, showSizeColumn, showDateColumn, generation
            );
            previous = loadWorker;
            snapshot = new Snapshot(root, ++revision, loadingEntries(request), true);
            snapshotSignal.incrementAndGet();
            worker = new Thread(() -> load(request), "tuink-tree-loader");
            worker.setDaemon(true);
            loadWorker = worker;
        }
        if (previous != null) {
            previous.interrupt();
        }
        worker.start();
    }

    private List<Entry> loadingEntries(LoadRequest request) {
        if (!snapshot.root().equals(request.root()) || snapshot.entries().isEmpty()) {
            return parentEntries(request.root());
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

    private static List<Entry> parentEntries(Path root) {
        List<Entry> entries = new ArrayList<>();
        Path parent = root.getParent();
        if (parent != null) {
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
            List<Entry> entries = parentEntries(request.root());
            Map<EntryId, BasicFileAttributes> attributes = new HashMap<>();
            List<DirectoryFailure> failures = new ArrayList<>();
            appendTreeEntries(request.root(), 0, entries, attributes, failures, request);
            checkCurrentGeneration(request.generation());
            publishSnapshot(entries, failures, request.generation());
            enrichMetadata(entries, attributes, failures, request);
        } catch (CancellationException ignored) {
            // A newer root/options/expansion request owns all future publications.
        } finally {
            synchronized (loaderLock) {
                if (loadGeneration == request.generation() && loadWorker == Thread.currentThread()) {
                    loadWorker = null;
                }
            }
        }
    }

    private void appendTreeEntries(
        Path directory,
        int depth,
        List<Entry> entries,
        Map<EntryId, BasicFileAttributes> attributes,
        List<DirectoryFailure> failures,
        LoadRequest request
    ) {
        checkCurrentGeneration(request.generation());
        List<ScannedEntry> children;
        try {
            children = new ArrayList<>(directoryScanner.scan(directory, request.showHiddenFiles()));
            checkCurrentGeneration(request.generation());
            Comparator<ScannedEntry> order = Comparator.comparing((ScannedEntry child) -> !child.directory())
                .thenComparing(ScannedEntry::sortName)
                .thenComparing(ScannedEntry::name);
            children.sort((left, right) -> {
                checkInterrupted();
                return order.compare(left, right);
            });
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (IOException | RuntimeException e) {
            String message = e instanceof AccessDeniedException ? "permission denied"
                : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            failures.add(new DirectoryFailure(directory, message));
            entries.add(new Entry(
                new EntryId(directory, Kind.ERROR),
                "<cannot read directory: " + message + ">",
                Kind.ERROR,
                false,
                depth,
                "",
                ""
            ));
            return;
        }

        for (ScannedEntry child : children) {
            checkCurrentGeneration(request.generation());
            boolean directoryEntry = child.directory();
            Path normalized = child.path().toAbsolutePath().normalize();
            boolean expanded = directoryEntry && request.expanded().contains(normalized);
            Kind kind = directoryEntry ? Kind.DIRECTORY
                : child.attributes() != null && child.attributes().isRegularFile() ? Kind.FILE : Kind.ERROR;
            String name = child.name();
            if (kind == Kind.ERROR) {
                name += " <not a regular file>";
            }
            EntryId id = new EntryId(normalized, kind);
            entries.add(new Entry(
                id,
                name,
                kind,
                expanded,
                depth,
                kind == Kind.ERROR || !request.showSizeColumn() ? "" : PENDING_META,
                kind == Kind.ERROR || !request.showDateColumn() ? "" : PENDING_META
            ));
            if (child.attributes() != null) {
                attributes.put(id, child.attributes());
            }
            if (directoryEntry && expanded) {
                appendTreeEntries(child.path(), depth + 1, entries, attributes, failures, request);
            }
        }
    }

    static List<ScannedEntry> scanDirectory(Path directory, boolean showHiddenFiles) throws IOException {
        List<ScannedEntry> children = new ArrayList<>();
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
                children.add(new ScannedEntry(path, attributes));
            }
        }
        return children;
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

    private void enrichMetadata(
        List<Entry> entries,
        Map<EntryId, BasicFileAttributes> attributes,
        List<DirectoryFailure> failures,
        LoadRequest request
    ) {
        if (!request.showSizeColumn() && !request.showDateColumn()) {
            return;
        }
        List<Entry> updatedEntries = new ArrayList<>(entries);
        // Preserve progressive updates without making full-list publication quadratic.
        int publishBatchSize = Math.max(
            MIN_METADATA_BATCH_SIZE,
            (int) (((long) entries.size() + MAX_METADATA_PUBLICATIONS - 1L) / MAX_METADATA_PUBLICATIONS)
        );
        int changedBatch = 0;
        for (int index = 0; index < entries.size(); index++) {
            checkCurrentGeneration(request.generation());
            Entry entry = entries.get(index);
            if (entry.kind() == Kind.PARENT || entry.kind() == Kind.ERROR) {
                continue;
            }
            BasicFileAttributes entryAttributes = attributes.get(entry.id());
            String size = !request.showSizeColumn() ? ""
                : entry.directory() ? formatDirectoryChildCount(entry.path())
                    : humanReadableSize(entryAttributes.size());
            String date = buildDateValue(entryAttributes, request.showDateColumn());
            Entry updated = entry.withMetadata(size, date);
            if (updated != entry) {
                updatedEntries.set(index, updated);
                changedBatch++;
                if (changedBatch >= publishBatchSize) {
                    publishSnapshot(updatedEntries, failures, request.generation());
                    changedBatch = 0;
                }
            }
        }
        if (changedBatch > 0) {
            publishSnapshot(updatedEntries, failures, request.generation());
        }
    }

    private void publishSnapshot(List<Entry> entries, List<DirectoryFailure> failures, long generation) {
        List<Entry> publishedEntries = List.copyOf(entries);
        synchronized (loaderLock) {
            if (closed || loadGeneration != generation) {
                return;
            }
            snapshot = new Snapshot(snapshot.root(), ++revision, publishedEntries, false, failures);
            snapshotSignal.incrementAndGet();
        }
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

    private static String formatDirectoryChildCount(Path path) {
        try (var stream = Files.list(path)) {
            long count = 0;
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                checkInterrupted();
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
        List<ScannedEntry> scan(Path directory, boolean showHiddenFiles) throws IOException;
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

    private record LoadRequest(
        Path root, Set<Path> expanded, boolean showHiddenFiles,
        boolean showSizeColumn, boolean showDateColumn, long generation
    ) {
    }

    public record DirectoryFailure(Path directory, String reason) {}

    public record Snapshot(Path root, long revision, List<Entry> entries, boolean loading,
                           List<DirectoryFailure> failures) {
        public Snapshot(Path root, long revision, List<Entry> entries) {
            this(root, revision, entries, false, List.of());
        }

        public Snapshot(Path root, long revision, List<Entry> entries, boolean loading) {
            this(root, revision, entries, loading, List.of());
        }

        public Snapshot {
            root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must be non-negative");
            }
            entries = List.copyOf(entries);
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

        private Entry(
            EntryId id,
            String name,
            Kind kind,
            boolean expanded,
            int depth,
            String sizeValue,
            String dateValue
        ) {
            this.id = Objects.requireNonNull(id, "id");
            this.name = Objects.requireNonNull(name, "name");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.expanded = expanded;
            this.depth = Math.max(0, depth);
            this.sizeValue = Objects.requireNonNull(sizeValue, "sizeValue");
            this.dateValue = Objects.requireNonNull(dateValue, "dateValue");
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

        private Entry withMetadata(String sizeValue, String dateValue) {
            String nextSize = sizeValue == null ? "" : sizeValue;
            String nextDate = dateValue == null ? "" : dateValue;
            if (this.sizeValue.equals(nextSize) && this.dateValue.equals(nextDate)) {
                return this;
            }
            return new Entry(id, name, kind, expanded, depth, nextSize, nextDate);
        }

        private Entry withExpanded(boolean expanded) {
            return this.expanded == expanded ? this
                : new Entry(id, name, kind, expanded, depth, sizeValue, dateValue);
        }
    }
}
