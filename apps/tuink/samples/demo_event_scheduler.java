/* Demo: In-memory event scheduler with retries and task state transitions. */
package dev.tuink.samples;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class EventScheduler {
    private final ConcurrentMap<Long, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1000);

    public long schedule(String name, Instant runAt, int retries) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is empty");
        }
        if (runAt == null) {
            throw new IllegalArgumentException("runAt is null");
        }
        if (retries < 0 || retries > 10) {
            throw new IllegalArgumentException("retries out of range");
        }
        long id = idSeq.incrementAndGet();
        Task task = new Task(id, name.trim(), runAt, retries, TaskState.QUEUED, null, 0);
        tasks.put(id, task);
        return id;
    }

    public List<Task> nextBatch(Instant now, int limit) {
        List<Task> result = new ArrayList<>();
        if (limit <= 0) {
            return result;
        }

        tasks.values().stream()
            .filter(t -> t.state == TaskState.QUEUED)
            .filter(t -> !t.runAt.isAfter(now))
            .sorted(Comparator.comparing((Task t) -> t.runAt).thenComparingLong(t -> t.id))
            .limit(limit)
            .forEach(result::add);

        for (Task t : result) {
            tasks.computeIfPresent(t.id, (id, old) -> old.withState(TaskState.RUNNING));
        }
        return result;
    }

    public void markDone(long taskId, Instant finishedAt) {
        tasks.compute(taskId, (id, old) -> {
            if (old == null) {
                throw new IllegalStateException("task not found: " + taskId);
            }
            if (old.state != TaskState.RUNNING) {
                throw new IllegalStateException("task is not running: " + taskId);
            }
            return old.withFinishedAt(finishedAt).withState(TaskState.DONE);
        });
    }

    public void markFailed(long taskId, String reason, Instant now) {
        tasks.compute(taskId, (id, old) -> {
            if (old == null) {
                throw new IllegalStateException("task not found: " + taskId);
            }
            if (old.state != TaskState.RUNNING) {
                throw new IllegalStateException("task is not running: " + taskId);
            }
            if (old.attempt >= old.maxRetries) {
                return old.withState(TaskState.FAILED).withLastError(reason).withFinishedAt(now);
            }
            Instant next = now.plus(Duration.ofSeconds(20L * (old.attempt + 1)));
            return new Task(
                old.id,
                old.name,
                next,
                old.maxRetries,
                TaskState.QUEUED,
                reason,
                old.attempt + 1
            );
        });
    }

    public Optional<Task> find(long id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public List<Task> snapshot() {
        return tasks.values().stream()
            .sorted(Comparator.comparingLong(t -> t.id))
            .toList();
    }

    public enum TaskState {
        QUEUED,
        RUNNING,
        DONE,
        FAILED
    }

    public static final class Task {
        public final long id;
        public final String name;
        public final Instant runAt;
        public final int maxRetries;
        public final TaskState state;
        public final String lastError;
        public final int attempt;
        public final Instant finishedAt;

        public Task(long id, String name, Instant runAt, int maxRetries, TaskState state, String lastError, int attempt) {
            this(id, name, runAt, maxRetries, state, lastError, attempt, null);
        }

        public Task(
            long id,
            String name,
            Instant runAt,
            int maxRetries,
            TaskState state,
            String lastError,
            int attempt,
            Instant finishedAt
        ) {
            this.id = id;
            this.name = name;
            this.runAt = runAt;
            this.maxRetries = maxRetries;
            this.state = state;
            this.lastError = lastError;
            this.attempt = attempt;
            this.finishedAt = finishedAt;
        }

        public Task withState(TaskState next) {
            return new Task(id, name, runAt, maxRetries, next, lastError, attempt, finishedAt);
        }

        public Task withLastError(String reason) {
            return new Task(id, name, runAt, maxRetries, state, reason, attempt, finishedAt);
        }

        public Task withFinishedAt(Instant ts) {
            return new Task(id, name, runAt, maxRetries, state, lastError, attempt, ts);
        }
    }
}
