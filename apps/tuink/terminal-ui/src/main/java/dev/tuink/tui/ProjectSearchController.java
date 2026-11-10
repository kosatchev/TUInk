package dev.tuink.tui;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.project.search.ProjectSearch;
import dev.tuink.project.search.jvm.JvmSearchSources;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Window host for detached project search, independent of the document I/O executor. */
final class ProjectSearchController implements AutoCloseable {
    private static final long DEBOUNCE = TimeUnit.MILLISECONDS.toNanos(200);
    private final Thread owner = Thread.currentThread();
    private final ProjectSearchPanel panel;
    private final Runnable wakeup;
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
        Thread thread = new Thread(task, "tuink-project-search"); thread.setDaemon(true); return thread;
    });
    private final ArrayBlockingQueue<Reply> replies = new ArrayBlockingQueue<>(16);
    private Run running;
    private long generation, due = Long.MIN_VALUE;
    private boolean closed;
    private Map<DocumentId, Long> captured = Map.of();
    private Map<DocumentId, dev.tuink.editor.storage.ResourceId> capturedNames = Map.of();
    private final java.util.Set<DocumentId> searchedBuffers = new java.util.HashSet<>();
    private record Reply(long generation, ProjectSearch.Event event) {}
    private static final class Run {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<ProjectSearch.Progress> progress = new AtomicReference<>();
        volatile boolean done;
    }
    ProjectSearchController(ProjectSearchPanel panel, Runnable wakeup) { this.panel = panel; this.wakeup = wakeup; }
    void changed(long now) {
        requireOwner(); revoke();
        panel.stale = !panel.groups.isEmpty();
        due = panel.preferences.automatic() && !panel.query.isEmpty() ? now + DEBOUNCE : Long.MIN_VALUE;
        panel.error = null; panel.dirty = true; panel.pending = due != Long.MIN_VALUE;
        if (panel.query.isEmpty()) { panel.reset(); captured = Map.of(); capturedNames = Map.of(); searchedBuffers.clear(); }
    }
    void request() {
        requireOwner();
        if (panel.query.isEmpty()) { reset(); return; }
        revoke(); panel.error = null; panel.pending = true; panel.dirty = true; due = System.nanoTime();
    }
    void reset() { requireOwner(); revoke(); due = Long.MIN_VALUE; captured = Map.of(); capturedNames = Map.of(); searchedBuffers.clear(); panel.reset(); }
    void stop() {
        requireOwner(); collect();
        boolean currentResults = panel.resultGeneration == generation;
        revoke(); due = Long.MIN_VALUE;
        panel.finished = new ProjectSearch.Finished(ProjectSearch.Outcome.CANCELLED, panel.scanned, panel.matches(), panel.skipped, "");
        // A pending query may still display the previous query's ranges. Stop
        // must not authorize those ranges against the new query and options.
        if (currentResults) panel.resultGeneration = generation;
        panel.dirty = false;
    }
    private void revoke() {
        generation++;
        if (running != null) running.cancelled.set(true);
        replies.clear(); panel.running = false; panel.pending = false;
    }
    boolean tick(Path root, Supplier<List<JvmSearchSources.Buffer>> buffers, Map<DocumentId, Long> revisions,
                 Map<DocumentId, dev.tuink.editor.storage.ResourceId> names, long now) {
        requireOwner(); boolean changed = collect();
        for (var entry : captured.entrySet()) {
            if (!searchedBuffers.contains(entry.getKey())) continue;
            Long current = revisions.get(entry.getKey());
            boolean renamed = !java.util.Objects.equals(names.get(entry.getKey()), capturedNames.get(entry.getKey()));
            if (current == null || !current.equals(entry.getValue()) || renamed) {
                boolean wasStale = panel.stale;
                panel.markStale(entry.getKey(), current == null ? -1 : current, renamed);
                // Even a captured buffer with no old matches can make the coverage stale.
                panel.stale = true;
                changed |= !wasStale;
            }
        }
        if (running != null && running.done && replies.isEmpty()) {
            if (panel.running) { panel.running = false; panel.error = "Search ended before completion; refresh results"; }
            running = null; changed = true;
        }
        if (!closed && due != Long.MIN_VALUE && now - due >= 0 && running == null) {
            due = Long.MIN_VALUE;
            if (root == null) { panel.pending = false; panel.error = "Open a folder to search"; return true; }
            try {
                var request = new ProjectSearch.Request(panel.query.text(), new SearchOptions(!panel.caseSensitive, panel.mode, panel.wholeWord),
                    panel.preferences.maximumMatches());
                List<JvmSearchSources.Buffer> capturedBuffers = buffers.get();
                HashMap<DocumentId, Long> next = new HashMap<>();
                HashMap<DocumentId, dev.tuink.editor.storage.ResourceId> nextNames = new HashMap<>();
                for (var buffer : capturedBuffers) {
                    next.put(buffer.snapshot().documentId(), buffer.snapshot().revision());
                    nextNames.put(buffer.snapshot().documentId(), buffer.binding().resource().id());
                }
                captured = Map.copyOf(next);
                capturedNames = Map.copyOf(nextNames);
                searchedBuffers.clear();
                var options = new JvmSearchSources.Options(panel.includes.text(), panel.excludes.text(), panel.respectIgnore,
                    panel.hidden, panel.encoding, panel.preferences.maximumFileMiB());
                var sources = new JvmSearchSources(root, panel.scope == null ? root : panel.scope, options, capturedBuffers,
                    path -> dev.tuiframe.explorer.file.PathLabels.relative(root, path));
                panel.reset(); panel.running = true;
                Run run = new Run(); running = run;
                long ticket = generation;
                panel.resultGeneration = ticket;
                // FutureTask contains unexpected worker failures. tick() observes run.done
                // and reports an incomplete search; executor stack traces must not paint over TUI.
                worker.submit(() -> {
                    try {
                        ProjectSearch.run(request, sources, run.cancelled::get, event -> {
                            if (event instanceof ProjectSearch.Progress progress) { run.progress.set(progress); return; }
                            try {
                                while (!run.cancelled.get()) {
                                    if (replies.offer(new Reply(ticket, event), 50, TimeUnit.MILLISECONDS)) { wakeup.run(); return; }
                                }
                                throw new CancellationException();
                            } catch (InterruptedException stop) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                        });
                    } finally { run.done = true; wakeup.run(); }
                });
            } catch (RuntimeException failure) { panel.error = failure.getMessage(); panel.running = false; panel.pending = false; }
            catch (OutOfMemoryError exhausted) { panel.error = "Not enough memory to capture search inputs"; panel.running = false; panel.pending = false; }
            return true;
        }
        return changed;
    }
    private boolean collect() {
        boolean changed = false;
        if (running != null && !running.cancelled.get()) {
            var progress = running.progress.getAndSet(null);
            if (progress != null) { panel.accept(progress); changed = true; }
        }
        for (int i = 0; i < 4; i++) {
            Reply reply = replies.poll(); if (reply == null) break;
            if (reply.generation == generation) {
                if (reply.event instanceof ProjectSearch.BufferSearched buffer) searchedBuffers.add(buffer.documentId());
                else panel.accept(reply.event);
                changed = true;
            }
        }
        return changed;
    }
    long nanosUntilWakeup(long now) {
        long poll = running != null || !replies.isEmpty() ? TimeUnit.MILLISECONDS.toNanos(20) : -1L;
        if (due == Long.MIN_VALUE) return poll;
        long delay = Math.max(0L, due - now);
        if (running != null && delay == 0) return poll;
        return poll < 0 ? delay : Math.min(delay, poll);
    }
    long generation() { return generation; }
    private void requireOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("Search owner thread required"); }
    @Override public void close() { requireOwner(); closed = true; revoke(); due = Long.MIN_VALUE; worker.shutdownNow(); }
}
