package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.search.SearchOptions;
import dev.tuink.project.search.ProjectSearch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSearchStopTest {
    @TempDir Path root;

    @Test void stoppingAPendingQueryCannotAuthorizeResultsFromThePreviousQuery() throws Exception {
        Files.writeString(root.resolve("note.txt"), "needle");
        var panel = new ProjectSearchPanel();
        panel.query.set("needle");
        try (var controller = new ProjectSearchController(panel, () -> {})) {
            search(controller, panel);
            panel.mode = SearchOptions.MatchMode.REGEX;
            panel.query.set("[");
            // Keep the debounce pending without relying on the test's execution speed.
            controller.changed(System.nanoTime() + TimeUnit.HOURS.toNanos(1));
            assertTrue(panel.pending);
            controller.stop();
            assertEquals(ProjectSearch.Outcome.CANCELLED, panel.finished.outcome());
            assertEquals(1, panel.matches(), "Stopping preserves the previously displayed matches");
            assertTrue(panel.stale);
            assertNotEquals(controller.generation(), panel.resultGeneration,
                "Old ranges must not be validated or opened using the changed, possibly invalid query");
        }
    }

    @Test void stoppingAnUnchangedQueryKeepsItsAcceptedResultsAvailableForNavigation() throws Exception {
        Files.writeString(root.resolve("note.txt"), "needle");
        var panel = new ProjectSearchPanel();
        panel.query.set("needle");
        try (var controller = new ProjectSearchController(panel, () -> {})) {
            search(controller, panel);
            controller.stop();
            assertEquals(1, panel.matches());
            assertEquals(controller.generation(), panel.resultGeneration);
            assertFalse(panel.stale);
        }
    }

    private void search(ProjectSearchController controller, ProjectSearchPanel panel) throws Exception {
        controller.request();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (panel.finished == null && System.nanoTime() < deadline) {
            controller.tick(root, List::of, Map.of(), Map.of(), System.nanoTime());
            Thread.sleep(1);
        }
        assertNotNull(panel.finished);
        assertEquals(ProjectSearch.Outcome.COMPLETE, panel.finished.outcome());
        assertEquals(controller.generation(), panel.resultGeneration);
    }
}
