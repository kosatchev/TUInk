package dev.tuink.tui.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TUInk-owned terminal/widget-independent filesystem model for the open-file tree.
 *
 * <p>The model deliberately contains no terminal geometry, styles, selection,
 * scrolling, pointer state or tree glyphs. Structural entries are published as
 * a flat snapshot; optional metadata is enriched asynchronously and announces
 * a monotonic revision to the UI thread. Instance calls other than
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
    private final Object metadataLock = new Object();
    private final AtomicLong metadataSignal = new AtomicLong();

    private Path root;
    private boolean showSizeColumn;
    private boolean showDateColumn;
    private boolean showHiddenFiles;
    private volatile Snapshot snapshot;
    private long consumedMetadataSignal;
    private long revision;
    private long metadataGeneration;
    private Thread metadataWorker;
    private boolean closed;

    public FileTreeModel(Path root, boolean showSizeColumn, boolean showDateColumn) {
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
        ensureOpen();
        Objects.requireNonNull(id, "id");
        Entry entry = findEntry(id);
        if (entry == null || entry.kind() != Kind.DIRECTORY) {
            return false;
        }
        Path key = entry.path().toAbsolutePath().normalize();
        if (!expandedDirectories.remove(key)) {
            expandedDirectories.add(key);
        }
        rebuild();
        return true;
    }

    /** Returns true when one or more metadata snapshots were published since the previous call. */
    public boolean consumeMetadataChanged() {
        long current = metadataSignal.get();
        if (current == consumedMetadataSignal) {
            return false;
        }
        consumedMetadataSignal = current;
        return true;
    }

    @Override
    public void close() {
        Thread previous;
        synchronized (metadataLock) {
            if (closed) {
                return;
            }
            closed = true;
            metadataGeneration++;
            previous = metadataWorker;
            metadataWorker = null;
        }
        if (previous != null) {
            previous.interrupt();
        }
    }

    private void rebuild() {
        long generation = beginMetadataGeneration();
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
        appendTreeEntries(root, 0, entries);
        List<Entry> publishedEntries = List.copyOf(entries);
        synchronized (metadataLock) {
            if (closed || metadataGeneration != generation) {
                return;
            }
            snapshot = new Snapshot(root, ++revision, publishedEntries);
        }
        startMetadataEnrichment(publishedEntries, generation, showSizeColumn, showDateColumn);
    }

    private long beginMetadataGeneration() {
        Thread previous;
        long generation;
        synchronized (metadataLock) {
            metadataGeneration++;
            generation = metadataGeneration;
            previous = metadataWorker;
            metadataWorker = null;
        }
        if (previous != null) {
            previous.interrupt();
        }
        return generation;
    }

    private void appendTreeEntries(Path directory, int depth, List<Entry> entries) {
        List<Path> children;
        try {
            children = listDirectorySorted(directory);
        } catch (IOException | RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
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

        for (Path child : children) {
            boolean directoryEntry = Files.isDirectory(child);
            Path normalized = child.toAbsolutePath().normalize();
            boolean expanded = directoryEntry && expandedDirectories.contains(normalized);
            Kind kind = directoryEntry ? Kind.DIRECTORY : Kind.FILE;
            String name = child.getFileName() == null ? child.toString() : child.getFileName().toString();
            entries.add(new Entry(
                new EntryId(normalized, kind),
                name,
                kind,
                expanded,
                depth,
                pendingSizeValue(),
                pendingDateValue()
            ));
            if (directoryEntry && expanded) {
                appendTreeEntries(child, depth + 1, entries);
            }
        }
    }

    private List<Path> listDirectorySorted(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream
                .filter(this::isVisiblePath)
                .sorted(
                    Comparator
                        .comparing((Path path) -> !Files.isDirectory(path))
                        .thenComparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                )
                .toList();
        }
    }

    private boolean isVisiblePath(Path path) {
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

    private void startMetadataEnrichment(
        List<Entry> entries,
        long generation,
        boolean includeSize,
        boolean includeDate
    ) {
        if ((!includeSize && !includeDate) || entries.isEmpty()) {
            return;
        }
        Thread worker = new Thread(
            () -> enrichMetadata(entries, generation, includeSize, includeDate),
            "tuink-tree-meta-loader"
        );
        worker.setDaemon(true);
        synchronized (metadataLock) {
            if (closed || metadataGeneration != generation) {
                return;
            }
            metadataWorker = worker;
        }
        worker.start();
    }

    private void enrichMetadata(
        List<Entry> entries,
        long generation,
        boolean includeSize,
        boolean includeDate
    ) {
        List<Entry> updatedEntries = new ArrayList<>(entries);
        // Preserve progressive updates without making full-list publication quadratic.
        int publishBatchSize = Math.max(
            MIN_METADATA_BATCH_SIZE,
            (int) (((long) entries.size() + MAX_METADATA_PUBLICATIONS - 1L) / MAX_METADATA_PUBLICATIONS)
        );
        int changedBatch = 0;
        for (int index = 0; index < entries.size(); index++) {
            if (Thread.currentThread().isInterrupted() || !isCurrentMetadataGeneration(generation)) {
                return;
            }
            Entry entry = entries.get(index);
            if (entry.kind() == Kind.PARENT || entry.kind() == Kind.ERROR) {
                continue;
            }
            String size = buildSizeValue(entry.path(), entry.directory(), includeSize);
            String date = buildDateValue(entry.path(), includeDate);
            Entry updated = entry.withMetadata(size, date);
            if (updated != entry) {
                updatedEntries.set(index, updated);
                changedBatch++;
                if (changedBatch >= publishBatchSize) {
                    publishMetadataRevision(updatedEntries, generation);
                    changedBatch = 0;
                }
            }
        }
        if (changedBatch > 0) {
            publishMetadataRevision(updatedEntries, generation);
        }
        synchronized (metadataLock) {
            if (metadataGeneration == generation && metadataWorker == Thread.currentThread()) {
                metadataWorker = null;
            }
        }
    }

    private void publishMetadataRevision(List<Entry> entries, long generation) {
        List<Entry> publishedEntries = List.copyOf(entries);
        synchronized (metadataLock) {
            if (closed || metadataGeneration != generation) {
                return;
            }
            snapshot = new Snapshot(snapshot.root(), ++revision, publishedEntries);
            metadataSignal.incrementAndGet();
        }
    }

    private boolean isCurrentMetadataGeneration(long generation) {
        synchronized (metadataLock) {
            return !closed && metadataGeneration == generation;
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

    private String pendingSizeValue() {
        return showSizeColumn ? PENDING_META : "";
    }

    private String pendingDateValue() {
        return showDateColumn ? PENDING_META : "";
    }

    private static String buildDateValue(Path path, boolean includeDate) {
        if (!includeDate) {
            return "";
        }
        try {
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                Files.getLastModifiedTime(path).toInstant(),
                ZoneId.systemDefault()
            );
            return DATE_FORMATTER.format(dateTime);
        } catch (IOException | RuntimeException ignored) {
            return UNKNOWN_DATE;
        }
    }

    private static String buildSizeValue(Path path, boolean directory, boolean includeSize) {
        if (!includeSize) {
            return "";
        }
        return directory ? formatDirectoryChildCount(path) : formatFileSize(path);
    }

    private static String formatDirectoryChildCount(Path path) {
        try (var stream = Files.list(path)) {
            long count = stream.count();
            return count + " items";
        } catch (IOException | RuntimeException ignored) {
            return "? items";
        }
    }

    private static String formatFileSize(Path path) {
        try {
            return humanReadableSize(Files.size(path));
        } catch (IOException | RuntimeException ignored) {
            return "?";
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

    public record Snapshot(Path root, long revision, List<Entry> entries) {
        public Snapshot {
            root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must be non-negative");
            }
            entries = List.copyOf(entries);
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
    }
}
