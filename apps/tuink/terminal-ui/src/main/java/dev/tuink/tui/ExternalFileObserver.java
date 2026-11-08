package dev.tuink.tui;

import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.editor.session.document.DocumentSaveSubject;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.editor.session.document.FrozenDocumentSession;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.storage.jvm.FileBaseline;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Window-owned, bounded observation of the selected local document. No filesystem work runs on its owner. */
final class ExternalFileObserver implements AutoCloseable {
    static final long CHECK_NANOS = TimeUnit.SECONDS.toNanos(1);
    static final long FULL_CHECK_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long WORKER_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    enum Kind { CHANGED, UNCHANGED, MISSING, DENIED, UNKNOWN }

    record Observation(DocumentSaveSubject subject, ResourceBinding expected, long generation, Path path,
        DocumentOpenMode mode, Kind kind, DocumentLoad loaded, Object version, String failure) {
        Observation {
            Objects.requireNonNull(subject); Objects.requireNonNull(expected); Objects.requireNonNull(path);
            Objects.requireNonNull(mode); Objects.requireNonNull(kind);
            if (kind == Kind.CHANGED && version == null) throw new IllegalArgumentException("Changed bytes require an observed version");
        }
        Observation(DocumentSaveSubject subject, ResourceBinding expected, long generation, Path path,
            DocumentOpenMode mode, Kind kind, DocumentLoad loaded) {
            this(subject, expected, generation, path, mode, kind, loaded, loaded == null ? null : loaded.baseline(), null);
        }
    }

    /** A clicked notice is re-read on a worker; even UNCHANGED contains the freshly validated load. */
    record ReadResult(Kind kind, DocumentLoad loaded, Object version, String failure) {
        ReadResult { Objects.requireNonNull(kind); }
        ReadResult(Kind kind, DocumentLoad loaded) { this(kind, loaded, loaded == null ? null : loaded.baseline(), null); }
    }

    @FunctionalInterface
    interface Loader {
        DocumentLoad load(DocumentResource resource, DocumentOpenMode mode) throws IOException;
    }

    private record Request(DocumentSaveSubject subject, ResourceBinding expected, long generation, Path path,
        DocumentOpenMode mode, boolean force) {
        boolean sameState(Request other) {
            return other != null && subject == other.subject && expected.equals(other.expected)
                && generation == other.generation && mode == other.mode;
        }
    }
    private record Stamp(Path physical, Object key, long size, FileTime modified, FileTime created) {}
    private record ReadRequest(ResourceBinding expected, Path path, DocumentOpenMode mode, boolean force) {}
    private record CachedRead(Stamp stamp, long checkedAt, ReadResult result) {}
    private record Cache(DocumentSaveSubject subject, ResourceBinding expected, DocumentOpenMode mode,
        CachedRead read) {
        boolean matches(Request request) {
            return subject == request.subject && expected.equals(request.expected) && mode == request.mode;
        }
    }
    private record Checked(ReadResult result, CachedRead cache) {}
    private record Pending(Request request, FutureTask<Checked> task) {}

    private final Thread owner = Thread.currentThread();
    private final Executor executor;
    private final ExecutorService worker;
    private final Loader loader;
    private final BooleanSupplier windowBusy;
    private final LongSupplier clock;
    private DocumentSaveSubject selected;
    private long nextCheck;
    private Pending pending;
    private Request queued;
    private Cache cache;
    private boolean closed;

    ExternalFileObserver() { this(() -> false); }

    ExternalFileObserver(BooleanSupplier windowBusy) {
        this(new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
            var thread = new Thread(task, "tuink-external-file");
            thread.setDaemon(true);
            return thread;
        }), true, ExternalFileObserver::load, windowBusy, System::nanoTime);
    }

    ExternalFileObserver(Executor executor, boolean owned, Loader loader, BooleanSupplier windowBusy, LongSupplier clock) {
        this.executor = Objects.requireNonNull(executor);
        worker = owned ? (ExecutorService) executor : null;
        this.loader = Objects.requireNonNull(loader);
        this.windowBusy = Objects.requireNonNull(windowBusy);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Activation and explicit Refresh force a content check; repeated requests never cancel an in-flight read. */
    void schedule(DocumentSaveSubject subject) {
        requireOwner();
        if (closed) return;
        selected = subject;
        long now = clock.getAsLong();
        nextCheck = now + CHECK_NANOS;
        enqueue(capture(subject, true));
    }

    /** Called by the normal owner turn. Only the active subject is periodically inspected. */
    void observe(DocumentSaveSubject subject, long now) {
        requireOwner();
        if (closed) return;
        if (selected != subject) {
            selected = subject;
            nextCheck = now;
        }
        if (subject == null || now - nextCheck < 0) return;
        nextCheck = now + CHECK_NANOS;
        enqueue(capture(subject, false));
    }

    long nanosUntilWakeup(long now) {
        requireOwner();
        if (closed) return -1;
        long delay = selected == null ? -1 : Math.max(0, nextCheck - now);
        if (pending != null || queued != null) delay = delay < 0 ? WORKER_POLL_NANOS : Math.min(delay, WORKER_POLL_NANOS);
        return delay;
    }

    List<Observation> poll() {
        requireOwner();
        if (closed || windowBusy.getAsBoolean()) return List.of();
        Observation observation = null;
        if (pending != null && pending.task().isDone()) {
            Pending completed = pending;
            pending = null;
            Checked checked;
            try { checked = completed.task().get(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                checked = new Checked(new ReadResult(Kind.UNKNOWN, null), null);
            } catch (ExecutionException | java.util.concurrent.CancellationException failed) {
                checked = new Checked(new ReadResult(Kind.UNKNOWN, null), null);
            }
            Request request = completed.request();
            if (current(request)) {
                cache = checked.cache() == null ? null
                    : new Cache(request.subject(), request.expected(), request.mode(), checked.cache());
                observation = new Observation(request.subject(), request.expected(), request.generation(), request.path(),
                    request.mode(), checked.result().kind(), checked.result().loaded(), checked.result().version(), checked.result().failure());
            }
        }
        startNext();
        return observation == null ? List.of() : List.of(observation);
    }

    boolean busy() { requireOwner(); return !closed && (pending != null || queued != null); }

    private Request capture(DocumentSaveSubject subject, boolean force) {
        if (subject == null || !subject.isOwnerThread() || subject.io().busy()
            || subject.io().state() == DocumentIoCoordinator.State.STOPPED) return null;
        ResourceBinding expected = subject.binding();
        if (expected.disposition() != SaveDisposition.REPLACE_EXISTING || !(expected.version() instanceof FileBaseline)
            || expected.resource() == null || !(expected.resource().storage() instanceof JvmDocumentStorage)) return null;
        Path path = JvmDocumentStorage.pathOf(expected.resource());
        return path == null ? null : new Request(subject, expected, generation(subject), path, subject.reopenMode(), force);
    }

    private void enqueue(Request request) {
        if (request == null) return;
        if (pending != null && request.sameState(pending.request()) && (!request.force() || pending.request().force())) {
            queued = null;
            return;
        }
        if (request.sameState(queued) && (!request.force() || queued.force())) return;
        queued = request;
        startNext();
    }

    private void startNext() {
        if (closed || pending != null || queued == null || windowBusy.getAsBoolean()) return;
        Request request = queued;
        queued = null;
        if (!current(request)) return;
        CachedRead previous = cache != null && cache.matches(request) ? cache.read() : null;
        var detached = new ReadRequest(request.expected(), request.path(), request.mode(), request.force());
        Loader detachedLoader = loader;
        LongSupplier detachedClock = clock;
        // No subject, view, document callback or observer is accessed by the worker body.
        var task = new FutureTask<>(() -> inspect(detached, previous, detachedLoader, detachedClock));
        pending = new Pending(request, task);
        try { executor.execute(task); }
        catch (RuntimeException | OutOfMemoryError unavailable) { task.cancel(false); }
    }

    private boolean current(Request request) {
        return request.subject().isOwnerThread() && !request.subject().io().busy()
            && request.subject().io().state() != DocumentIoCoordinator.State.STOPPED
            && request.expected().equals(request.subject().binding())
            && request.generation() == generation(request.subject()) && request.mode() == request.subject().reopenMode();
    }

    static long generation(DocumentSaveSubject subject) {
        if (subject instanceof EditorDocumentSession live) return live.status().recoveryRevision();
        if (subject instanceof FrozenDocumentSession frozen) return frozen.generation();
        return 0;
    }

    /** Worker-only revalidation of exactly the notice being acted on. Never reads mutable owner state. */
    static ReadResult revalidate(Observation observation) {
        Objects.requireNonNull(observation);
        return read(observation.expected(), observation.path(), observation.mode(), ExternalFileObserver::load);
    }

    /** Worker-only fresh read, including for an explicit Update without an unresolved notice. */
    static ReadResult read(ResourceBinding expected, Path path, DocumentOpenMode mode) {
        return read(Objects.requireNonNull(expected), Objects.requireNonNull(path), Objects.requireNonNull(mode),
            ExternalFileObserver::load);
    }

    static ReadResult read(DocumentResource resource, Path path, DocumentOpenMode mode) {
        return read(new ResourceBinding(Objects.requireNonNull(resource), SaveDisposition.REPLACE_EXISTING, null), path, mode);
    }

    /** Version equality is constant-space: the raw byte fingerprint already covers text, BOM and line separators. */
    static boolean sameVersion(DocumentLoad first, DocumentLoad second) {
        return first != null && second != null && Objects.equals(first.resource().id(), second.resource().id())
            && Objects.equals(first.baseline(), second.baseline())
            && first.metadata().charset().equals(second.metadata().charset())
            && first.metadata().bomKind() == second.metadata().bomKind();
    }

    static boolean sameVersion(Observation first, Observation second) {
        return first != null && second != null && first.version() != null
            && Objects.equals(first.version(), second.version()) && first.mode() == second.mode();
    }

    private static Checked inspect(ReadRequest request, CachedRead previous, Loader loader, LongSupplier clock) {
        try {
            Stamp stamp = stamp(request.path());
            long now = clock.getAsLong();
            if (!request.force() && previous != null && stamp.equals(previous.stamp())
                && now - previous.checkedAt() < FULL_CHECK_NANOS) return new Checked(previous.result(), previous);
            FileBaseline raw = null;
            if (!request.force()) {
                try { raw = fingerprint(stamp); }
                catch (IOException | RuntimeException unavailable) {
                    // The full read below classifies access, disappearance and known-change failures.
                }
                if (raw != null && matchesBaseline(request.expected(), raw)) {
                    if (!stamp.equals(stamp(request.path()))) return new Checked(new ReadResult(Kind.UNKNOWN, null), null);
                    var unchanged = new ReadResult(Kind.UNCHANGED, null);
                    return new Checked(unchanged, new CachedRead(stamp, clock.getAsLong(), unchanged));
                }
            }
            ReadResult result = read(request.expected(), request.path(), request.mode(), loader, stamp, raw);
            if (result.kind() != Kind.CHANGED && result.kind() != Kind.UNCHANGED) return new Checked(result, null);
            if (!stamp.equals(stamp(request.path()))) return new Checked(new ReadResult(Kind.UNKNOWN, null), null);
            // Normal observations do not retain a decoded copy of bytes already owned by the document.
            ReadResult published = result.kind() == Kind.UNCHANGED ? new ReadResult(Kind.UNCHANGED, null) : result;
            return new Checked(published, new CachedRead(stamp, clock.getAsLong(), published));
        } catch (NoSuchFileException missing) { return new Checked(new ReadResult(Kind.MISSING, null), null); }
        catch (AccessDeniedException | SecurityException denied) { return new Checked(new ReadResult(Kind.DENIED, null), null); }
        catch (IOException | RuntimeException unavailable) { return new Checked(new ReadResult(Kind.UNKNOWN, null), null); }
    }

    private static ReadResult read(ResourceBinding expected, Path path, DocumentOpenMode mode, Loader loader) {
        return read(expected, path, mode, loader, null, null);
    }

    private static ReadResult read(ResourceBinding expected, Path path, DocumentOpenMode mode, Loader loader,
        Stamp before, FileBaseline observed) {
        try {
            if (before == null) before = stamp(path);
            if (observed == null) observed = fingerprint(before);
            DocumentOpenMode decoder = matchesBaseline(expected, observed) ? mode : externalDecoder(path, mode);
            DocumentLoad loaded = loader.load(expected.resource(), decoder);
            if (!(loaded.baseline() instanceof FileBaseline baseline)
                || loaded.disposition() != SaveDisposition.REPLACE_EXISTING
                || !loaded.resource().id().equals(expected.resource().id())
                || !baseline.target().equals(before.physical()) || !baseline.equals(observed) || !before.equals(stamp(path))
                || !matches(baseline) || !before.equals(stamp(path))) return new ReadResult(Kind.UNKNOWN, null);
            Kind kind = matchesBaseline(expected, baseline) ? Kind.UNCHANGED : Kind.CHANGED;
            return new ReadResult(kind, loaded);
        } catch (NoSuchFileException missing) { return new ReadResult(Kind.MISSING, null); }
        catch (AccessDeniedException | SecurityException denied) {
            return failedRead(expected, before, observed, denied, Kind.DENIED);
        } catch (IOException | RuntimeException unavailable) {
            return failedRead(expected, before, observed, unavailable, Kind.UNKNOWN);
        }
    }

    private static DocumentLoad load(DocumentResource resource, DocumentOpenMode mode) throws IOException {
        return resource.storage().load(resource, mode, false);
    }

    private static DocumentOpenMode externalDecoder(Path path, DocumentOpenMode accepted) throws IOException {
        // An explicit legacy decoder interprets BOM-like bytes as text, including after edits.
        if (accepted == DocumentOpenMode.WINDOWS_1251 || accepted == DocumentOpenMode.CP866
            || accepted == DocumentOpenMode.KOI8_R) return accepted;
        // Unicode BOM changes are explicit format information. An earlier BOM-required UTF-16
        // input without a BOM may only fall back to strict UTF-8.
        int first;
        int second;
        int third;
        try (InputStream input = Files.newInputStream(path)) {
            first = input.read(); second = input.read(); third = input.read();
        }
        boolean unicodeBom = first == 0xEF && second == 0xBB && third == 0xBF
            || first == 0xFF && second == 0xFE || first == 0xFE && second == 0xFF;
        if (accepted == DocumentOpenMode.UTF_16LE && first == 0xFF && second == 0xFE
            || accepted == DocumentOpenMode.UTF_16BE && first == 0xFE && second == 0xFF) return accepted;
        return unicodeBom ? DocumentOpenMode.DETECT
            : accepted == DocumentOpenMode.UTF_16LE || accepted == DocumentOpenMode.UTF_16BE ? DocumentOpenMode.UTF_8 : accepted;
    }

    private static boolean matchesBaseline(ResourceBinding expected, FileBaseline loaded) {
        return expected.version() instanceof FileBaseline previous && previous.target().equals(loaded.target())
            && previous.sha256().equals(loaded.sha256()) && (previous.byteSize() < 0 || previous.byteSize() == loaded.byteSize());
    }

    private static ReadResult failedRead(ResourceBinding expected, Stamp stamp, FileBaseline observed,
        Exception failure, Kind fallback) {
        boolean changed = observed != null && !matchesBaseline(expected, observed)
            || stamp != null && expected.version() instanceof FileBaseline baseline
                && (!baseline.target().equals(stamp.physical()) || baseline.byteSize() >= 0 && baseline.byteSize() != stamp.size());
        if (!changed || failure instanceof java.io.InterruptedIOException) return new ReadResult(fallback, null);
        String message = failure.getMessage();
        return new ReadResult(Kind.CHANGED, null, observed == null ? stamp : observed,
            message == null || message.isBlank() ? "Cannot read the changed file" : message);
    }

    private static FileBaseline fingerprint(Stamp stamp) throws IOException {
        dev.tuink.core.DocumentCodec.requireLoadSize(stamp.size(), dev.tuink.core.DocumentCodec.currentLoadLimits(),
            stamp.physical().toString());
        MessageDigest digest = digest();
        long remaining = stamp.size();
        try (InputStream input = Files.newInputStream(stamp.physical())) {
            byte[] buffer = new byte[8192];
            while (remaining > 0) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("External read cancelled");
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) throw new IOException("External file shrank during verification");
                digest.update(buffer, 0, read);
                remaining -= read;
            }
            if (input.read() != -1) throw new IOException("External file grew during verification");
        }
        return new FileBaseline(stamp.physical(), HexFormat.of().formatHex(digest.digest()), stamp.size());
    }

    private static Stamp stamp(Path path) throws IOException {
        Path physical = path.toRealPath();
        BasicFileAttributes attributes = Files.readAttributes(physical, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) throw new IOException("External document is not a regular file");
        return new Stamp(physical, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime(), attributes.creationTime());
    }

    private static boolean matches(FileBaseline baseline) throws IOException {
        MessageDigest digest = digest();
        long remaining = baseline.byteSize();
        if (remaining < 0) return false;
        try (InputStream input = Files.newInputStream(baseline.target())) {
            byte[] buffer = new byte[8192];
            while (remaining > 0) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("External read cancelled");
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) return false;
                digest.update(buffer, 0, read);
                remaining -= read;
            }
            if (input.read() != -1) return false;
        }
        return baseline.sha256().equals(HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    void cancel() {
        requireOwner();
        selected = null;
        if (pending != null) pending.task().cancel(true);
        pending = null; queued = null; cache = null;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("External file observation belongs to its owner thread");
    }

    @Override public void close() {
        requireOwner();
        if (closed) return;
        cancel();
        closed = true;
        if (worker != null) worker.shutdownNow();
    }
}
