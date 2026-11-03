package dev.tuink.tui;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.MarkdownParser;
import dev.tuink.markdown.ParseLimits;
import dev.tuink.markdown.commonmark.CommonmarkMarkdownParser;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuink.render.markdown.MathLayout;
import dev.tuink.markdown.math.builtin.BuiltinMathProvider;
import dev.tuink.markdown.mermaid.builtin.BuiltinMermaidProvider;
import dev.tuink.render.markdown.MermaidLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One bounded worker per window; snapshots and detached results cross its boundary. */
final class MarkdownPreviewService implements AutoCloseable {
    private record Key(DocumentId id, long revision) {}
    private record LayoutKey(Key source, int width) {}
    private record CachedModel(MarkdownDocument document, MarkdownPreviewBudget.Lease lease) {}
    private record Rendered(MarkdownLayout.Result result, MarkdownPreviewBudget.Lease lease) {}
    private static final class StartGate {
        volatile long deadline;
        volatile boolean layoutNeeded = true;
        StartGate(long delayMillis) { deadline = System.nanoTime() + Math.min(250, Math.max(0, delayMillis)) * 1_000_000L; }
        void await() throws InterruptedException {
            for (long remaining; (remaining = deadline - System.nanoTime()) > 0;)
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, 5_000_000L));
        }
    }
    private final Object lock = new Object();
    private final MarkdownParser parser;
    private final Runnable wakeup;
    private final ThreadPoolExecutor worker;
    private final Map<Key, CachedModel> models = new LinkedHashMap<>(4, .75f, true);
    private final Map<LayoutKey, Work> work = new HashMap<>();
    private final Set<Task> consumers = new HashSet<>();
    private final MarkdownPreviewBudget budget;
    private final long embeddedUnitsLimit;
    private volatile boolean closed;

    MarkdownPreviewService(Runnable wakeup) { this(new CommonmarkMarkdownParser(), wakeup); }
    MarkdownPreviewService(MarkdownParser parser, Runnable wakeup) {
        this(parser, wakeup, MarkdownPreviewBudget.Limits.DEFAULT);
    }
    MarkdownPreviewService(MarkdownParser parser, Runnable wakeup, MarkdownPreviewBudget.Limits limits) {
        this.parser = parser; this.wakeup = wakeup;
        budget = new MarkdownPreviewBudget(limits);
        embeddedUnitsLimit = Math.max(1, limits.layoutBytes() / 4);
        worker = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), operation -> {
            Thread thread = new Thread(operation, "tuink-markdown-preview"); thread.setDaemon(true); return thread;
        }, (operation, executor) -> {
            Runnable displaced = executor.getQueue().poll();
            if (displaced instanceof FutureTask<?> future) future.cancel(false);
            if (!executor.isShutdown() && executor.getQueue().offer(operation)) return;
            if (operation instanceof FutureTask<?> future) future.cancel(false);
        });
        worker.allowCoreThreadTimeOut(true);
    }
    /** A consumer owns only its subscription; cancelling it cannot retire another consumer. */
    final class Task extends FutureTask<MarkdownLayout.Result> implements AutoCloseable {
        private Work owner;
        private MarkdownPreviewBudget.Lease retention;
        private Task(Work owner) { super(() -> null); this.owner = owner; }
        private void complete(Rendered rendered) {
            retention = budget.result(rendered.result());
            set(rendered.result());
        }
        private void fail(Throwable failure) { setException(failure); }
        MarkdownPreviewBudget.Lease takeRetention() {
            synchronized (lock) {
                var retained = retention; retention = null; consumers.remove(this); return retained;
            }
        }
        @Override public boolean cancel(boolean interrupt) {
            synchronized (lock) {
                boolean cancelled = super.cancel(false);
                if (owner != null) {
                    Work previous = owner; owner = null; previous.subscribers.remove(this);
                    if (previous.subscribers.isEmpty() && !previous.isDone()) {
                        previous.start.layoutNeeded = false;
                        boolean sharedParse = work.values().stream().anyMatch(other -> other != previous
                            && other.key.source().equals(previous.key.source()) && !other.subscribers.isEmpty()
                            && !other.obsolete && !other.isDone());
                        if (!sharedParse || previous.runner == null) previous.abort(interrupt);
                        if (!sharedParse) for (Work orphan : List.copyOf(work.values()))
                            if (orphan.key.source().equals(previous.key.source()) && orphan.subscribers.isEmpty()
                                && !orphan.isDone()) orphan.abort(interrupt);
                    }
                }
                if (retention != null) { retention.close(); retention = null; }
                consumers.remove(this); return cancelled;
            }
        }
        @Override protected void done() { wakeup.run(); }
        @Override public void close() { cancel(true); }
    }
    private final class Work extends FutureTask<Rendered> {
        final LayoutKey key;
        final List<Task> subscribers = new ArrayList<>();
        volatile Thread runner;
        volatile boolean obsolete;
        final StartGate start;
        final MarkdownPreviewBudget.Lease inputRetention;
        Work(LayoutKey key, DocumentSnapshot snapshot, int width, long delayMillis, MarkdownPreviewBudget.Lease inputRetention) {
            this(key, snapshot, width, new StartGate(delayMillis), inputRetention);
        }
        private Work(LayoutKey key, DocumentSnapshot snapshot, int width, StartGate start, MarkdownPreviewBudget.Lease inputRetention) {
            super(() -> { start.await(); return MarkdownPreviewService.this.render(snapshot, width, start); });
            this.key = key; this.start = start; this.inputRetention = inputRetention;
        }
        @Override public void run() {
            runner = Thread.currentThread();
            try { if (obsolete) cancel(false); else super.run(); }
            finally {
                inputRetention.close(); runner = null;
                wakeup.run();
            }
        }
        void abort(boolean interrupt) {
            obsolete = true;
            if (worker.getQueue().remove(this)) cancel(false);
            else if (interrupt && runner != null) runner.interrupt();
        }
        @Override protected void done() {
            Rendered rendered = null; Throwable failure = null;
            try { rendered = get(); }
            catch (java.util.concurrent.ExecutionException failed) { failure = failed.getCause(); }
            catch (java.util.concurrent.CancellationException | InterruptedException cancelled) { failure = cancelled; }
            synchronized (lock) {
                work.remove(key, this);
                for (Task consumer : List.copyOf(subscribers)) {
                    consumer.owner = null;
                    if (consumer.isCancelled()) continue;
                    if (failure instanceof java.util.concurrent.CancellationException || failure instanceof InterruptedException)
                        consumer.cancel(false);
                    else if (failure != null) consumer.fail(failure);
                    else consumer.complete(rendered);
                }
                subscribers.clear();
                if (rendered != null) rendered.lease().close();
                // A cancelled queued job never enters run(). A running cancellation may call
                // done before a provider returns, so only run's finally can release its input.
                if (runner == null) inputRetention.close();
            }
        }
    }
    Task submit(DocumentSnapshot snapshot, int width) {
        return submit(snapshot, width, 0);
    }
    Task submit(DocumentSnapshot snapshot, int width, long delayMillis) {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Preview service is closed");
            var key = new LayoutKey(new Key(snapshot.documentId(), snapshot.revision()), width);
            Work shared = work.get(key);
            boolean create = shared == null || shared.obsolete;
            if (create) {
                MarkdownPreviewBudget.Lease input;
                try { input = budget.sourceText(snapshot); }
                catch (MarkdownPreviewBudget.Refused refused) { clearModels(); input = budget.sourceText(snapshot); }
                try { shared = new Work(key, snapshot, width, delayMillis, input); }
                catch (RuntimeException | Error failure) { input.close(); throw failure; }
                work.put(key, shared);
            }
            else if (delayMillis <= 0) shared.start.deadline = System.nanoTime();
            shared.start.layoutNeeded = true;
            Task consumer = new Task(shared); shared.subscribers.add(consumer); consumers.add(consumer);
            if (create) worker.execute(shared);
            return consumer;
        }
    }
    MarkdownPreviewBudget.Lease retainSource(FrozenDocumentSource source) {
        synchronized (lock) {
            try { return budget.source(source); }
            catch (MarkdownPreviewBudget.Refused refused) { clearModels(); return budget.source(source); }
        }
    }
    MarkdownPreviewBudget.Lease replaceSource(MarkdownPreviewBudget.Lease previous, FrozenDocumentSource source) {
        synchronized (lock) {
            try { return budget.replaceSource(previous, source); }
            catch (MarkdownPreviewBudget.Refused refused) { clearModels(); return budget.replaceSource(previous, source); }
        }
    }
    dev.tuiframe.memory.RenderMemoryBudget renderMemoryBudget() { return budget.renderMemoryBudget(); }
    long retainedBytes() { return budget.usedBytes(); }
    long retainedSourceBytes() { return budget.usedBytes(MarkdownPreviewBudget.Kind.SOURCE); }
    long retainedLayoutBytes() { return budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT); }
    int cachedModelCount() { synchronized (lock) { return models.size(); } }
    private Rendered render(DocumentSnapshot snapshot, int width, StartGate consumer) {
        Key key = new Key(snapshot.documentId(), snapshot.revision());
        MarkdownDocument document;
        synchronized (lock) { var cached = models.get(key); document = cached == null ? null : cached.document(); }
        if (document == null) {
            document = parser.parse(snapshot, ParseLimits.DEFAULT, () -> Thread.currentThread().isInterrupted());
            synchronized (lock) {
                checkRunning();
                MarkdownPreviewBudget.Lease lease;
                try { lease = budget.model(document); }
                catch (MarkdownPreviewBudget.Refused refused) { clearModels(); lease = budget.model(document); }
                models.put(key, new CachedModel(document, lease));
                while (models.size() > 2) evictOldestModel();
            }
        }
        checkRunning();
        if (!consumer.layoutNeeded) throw new java.util.concurrent.CancellationException("Preview layout consumer retired");
        var result = new MarkdownLayout(width, embeddedRenderer(embeddedUnitsLimit)).layout(document);
        synchronized (lock) {
            checkRunning();
            MarkdownPreviewBudget.Lease lease;
            try { lease = budget.result(result); }
            catch (MarkdownPreviewBudget.Refused refused) { clearModels(); lease = budget.result(result); }
            return new Rendered(result, lease);
        }
    }
    private void checkRunning() {
        if (closed || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Preview cancelled");
    }
    private void evictOldestModel() {
        var iterator = models.entrySet().iterator();
        var cached = iterator.next().getValue(); iterator.remove(); cached.lease().close();
    }
    private void clearModels() { while (!models.isEmpty()) evictOldestModel(); }
    private static MarkdownLayout.EmbeddedRenderer embeddedRenderer(long unitsLimit) {
        var math = new BuiltinMathProvider();
        var mermaid = new BuiltinMermaidProvider();
        return new MarkdownLayout.EmbeddedRenderer() {
            final Map<String, MarkdownLayout.Box> formulas = new LinkedHashMap<>(16, .75f, true);
            long cachedUnits;
            long renderedUnits;
            private long units(List<String> lines) { return lines.stream().mapToLong(String::length).sum(); }
            private void admit(List<String> lines) {
                long next = renderedUnits + units(lines);
                if (next > unitsLimit) throw new MarkdownPreviewBudget.Refused("Preview embedded rendering memory budget exceeded");
                renderedUnits = next;
            }
            @Override public MarkdownLayout.Box math(String source) {
                MarkdownLayout.Box result = formulas.get(source);
                if (result == null) {
                    var expression = math.parse(source);
                    if (!expression.supported()) {
                        var lines = new java.util.ArrayList<String>();
                        lines.add("[Formula: " + String.join("; ", expression.diagnostics()) + "]");
                        lines.addAll(source.lines().toList());
                        result = new MarkdownLayout.Box(lines, 0);
                    } else {
                        var box = MathLayout.layout(expression);
                        result = new MarkdownLayout.Box(box.lines(), box.baseline());
                    }
                    long size = units(result.lines());
                    if (size <= 262_144) {
                        formulas.put(source, result); cachedUnits += size;
                        while (formulas.size() > 128 || cachedUnits > 262_144) {
                            var oldest = formulas.entrySet().iterator().next();
                            cachedUnits -= units(oldest.getValue().lines()); formulas.remove(oldest.getKey());
                        }
                    }
                }
                admit(result.lines()); return result;
            }
            @Override public java.util.List<String> mermaid(String source, int width) {
                var lines = MermaidLayout.render(mermaid.parse(source), width); admit(lines); return lines;
            }
        };
    }
    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            for (Task consumer : List.copyOf(consumers)) consumer.close();
            for (Runnable queued : worker.shutdownNow()) if (queued instanceof FutureTask<?> future) future.cancel(false);
            work.clear(); clearModels(); budget.close();
        }
    }
}
