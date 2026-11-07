package dev.tuiframe.explorer.file;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/** Waits for asynchronous fixtures without making production model reads block. */
public final class FileTreeTestSupport {
    private FileTreeTestSupport() {
    }

    public static FileTreeModel.Snapshot awaitLoaded(FileTreeModel model) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            FileTreeModel.Snapshot snapshot = model.snapshot();
            if (!snapshot.loading() && snapshot.entries().stream().noneMatch(entry ->
                "...".equals(entry.sizeValue()) || "...".equals(entry.dateValue()))) {
                return snapshot;
            }
            LockSupport.parkNanos(1_000_000L);
        }
        throw new AssertionError("File tree did not finish loading: " + model.snapshot());
    }
}
