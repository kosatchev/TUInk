package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSearchWorkerFailureTest {
    @TempDir Path root;

    @Test void unexpectedWorkerFailuresBecomePanelErrorsWithoutInvokingTheConsoleHandler() throws Exception {
        Files.writeString(root.resolve("note.txt"), "needle");
        var panel = new ProjectSearchPanel(); panel.query.set("needle");
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        AtomicBoolean firstSignal = new AtomicBoolean(true);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (thread.getName().equals("tuink-project-search")) uncaught.set(failure);
            else if (previous != null) previous.uncaughtException(thread, failure);
        });
        try (var controller = new ProjectSearchController(panel, () -> {
            if (firstSignal.getAndSet(false)) throw new AssertionError("synthetic worker signal failure");
        })) {
            controller.request();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            do {
                controller.tick(root, List::of, Map.of(), Map.of(), System.nanoTime());
                if (panel.error != null) break;
                Thread.sleep(1);
            } while (System.nanoTime() < deadline);
            assertFalse(firstSignal.get());
            assertNotNull(panel.error, "The owner must report the failed background operation");
            assertNull(uncaught.get(), "An uncaught worker failure would write a stack trace over the terminal UI");
            assertFalse(panel.running);
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous); }
    }
}
