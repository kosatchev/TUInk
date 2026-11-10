package dev.tuink.project.search;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.DocumentResource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class ProjectSearchTest {
    private ProjectSearch.Source source(String key, List<String> lines) {
        return ProjectSearch.snapshot(key, key, null, new DocumentSnapshot(new DocumentId(key), 1, lines), DocumentOpenMode.UTF_8);
    }
    @Test void sourcePortsWorkWithoutAFilesystemOrEditorSession() {
        ArrayList<ProjectSearch.Event> events = new ArrayList<>();
        ProjectSearch.run(new ProjectSearch.Request("needle", SearchOptions.DEFAULT, 100),
            (control, visitor) -> visitor.accept(source("virtual", List.of("🙂needle", "needle"))), () -> false, events::add);
        var matches = events.stream().filter(ProjectSearch.Matches.class::isInstance).map(ProjectSearch.Matches.class::cast)
            .flatMap(batch -> batch.matches().stream()).toList();
        assertEquals(List.of(2, 0), matches.stream().map(ProjectSearch.Match::start).toList());
        assertEquals(ProjectSearch.Outcome.COMPLETE, ((ProjectSearch.Finished) events.getLast()).outcome());
    }
    @Test void aFailedSourcePublishesNoPartialMatchesAndOtherSourcesContinue() {
        ArrayList<ProjectSearch.Event> events = new ArrayList<>();
        ProjectSearch.Source broken = new ProjectSearch.Source() {
            public String key() { return "broken"; }
            public String label() { return "broken"; }
            public DocumentResource resource() { return null; }
            public ProjectSearch.ReadVersion read(ProjectSearch.Control control, ProjectSearch.Lines lines) throws IOException {
                lines.accept(0, "needle"); throw new IOException("source changed");
            }
        };
        ProjectSearch.run(new ProjectSearch.Request("needle", SearchOptions.DEFAULT, 100), (control, visitor) -> {
            visitor.accept(broken); visitor.accept(source("valid", List.of("needle")));
        }, () -> false, events::add);
        var matches = events.stream().filter(ProjectSearch.Matches.class::isInstance).map(ProjectSearch.Matches.class::cast)
            .flatMap(batch -> batch.matches().stream()).toList();
        assertEquals(1, matches.size()); assertEquals("valid", matches.getFirst().target().key());
        assertEquals(ProjectSearch.Outcome.PARTIAL, ((ProjectSearch.Finished) events.getLast()).outcome());
    }
    @Test void retainedResultMemoryBoundsTheSearchIndependentlyOfTheCountSetting() {
        ArrayList<ProjectSearch.Event> events = new ArrayList<>();
        ProjectSearch.run(new ProjectSearch.Request("needle", SearchOptions.DEFAULT, 100_000),
            (control, visitor) -> visitor.accept(source("many", java.util.Collections.nCopies(100_000, "needle"))), () -> false, events::add);
        var finished = (ProjectSearch.Finished) events.getLast();
        assertEquals(ProjectSearch.Outcome.LIMIT, finished.outcome());
        assertTrue(finished.matches() > 0 && finished.matches() < 100_000);
    }

    @Test void cancellationWhileDeliveringAnInvalidQueryFailureDoesNotEscapeTheWorker() {
        var request = new ProjectSearch.Request("[", new SearchOptions(false, SearchOptions.MatchMode.REGEX), 100);
        assertDoesNotThrow(() -> ProjectSearch.run(request, (control, visitor) -> {}, () -> false, event -> {
            if (event instanceof ProjectSearch.Finished finished) {
                assertEquals(ProjectSearch.Outcome.FAILED, finished.outcome());
                throw new CancellationException("superseded while publishing the error");
            }
        }));
    }

    @Test void cancellationWhileDeliveringALimitCompletionDoesNotEscapeTheWorker() {
        var request = new ProjectSearch.Request("needle", SearchOptions.DEFAULT, 1);
        assertDoesNotThrow(() -> ProjectSearch.run(request,
            (control, visitor) -> visitor.accept(source("limited", List.of("needle needle"))), () -> false, event -> {
                if (event instanceof ProjectSearch.Finished finished) {
                    assertEquals(ProjectSearch.Outcome.LIMIT, finished.outcome());
                    throw new CancellationException("superseded while publishing the limit");
                }
            }));
    }
}
