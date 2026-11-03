package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.widget.TextField;
import dev.tuink.core.EditorClipboard;
import dev.tuink.core.LogicalEditorTextLayout;
import dev.tuink.core.SearchLimitException;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.TabSearch;
import dev.tuink.core.TabSession;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SearchQueryMemoryTest {
    @Test
    void acceptedLiteralInputHasAControlledSearchRefusalOn64MiB() throws Exception {
        runProbe("64m", "literal");
    }

    @Test
    void acceptedLiteralInputHasAControlledSearchRefusalOn128MiB() throws Exception {
        runProbe("128m", "literal");
    }

    @Test
    void acceptedSegmentedWildcardHasAControlledSearchRefusalOn64MiB() throws Exception {
        runProbe("64m", "segments");
    }

    private void runProbe(String heap, String mode) throws Exception {
        var locations = new LinkedHashSet<String>();
        for (String name : List.of(
            getClass().getName(), "dev.tuink.tui.SearchPresentation", "dev.tuink.core.EditorEngine",
            "dev.tuink.editor.language.LanguageRegistry", "dev.tuink.unicode.GraphemeText", "dev.tuiframe.widget.TextField",
            "dev.tuiframe.cell.CellBuffer"
        )) locations.add(Path.of(Class.forName(name, false, getClass().getClassLoader())
            .getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx" + heap, "-cp", String.join(File.pathSeparator, locations), Probe.class.getName(), mode)
            .redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Search query probe exceeded its deadline");
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
        } finally {
            child.destroyForcibly();
        }
    }

    public static final class Probe {
        public static void main(String[] args) {
            TabSession tab = TabSession.createEmpty(LogicalEditorTextLayout::new, new EditorClipboard());
            tab.engine().insertInput("unsaved document");
            TextField field = new TextField();
            SearchPresentation presentation = new SearchPresentation();
            var ui = SearchController.SearchUiOptions.DEFAULT;
            if (args[0].equals("segments")) {
                ui = new SearchController.SearchUiOptions(true, SearchOptions.MatchMode.WILDCARD, false);
                require(field.paste("*a".repeat(524_288)), "The field must admit the segmented query");
            } else {
                int chunks = (int) (Runtime.getRuntime().maxMemory() * 3 / 32 / 1_048_576);
                String chunk = "a".repeat(1_048_576);
                for (int index = 0; index < chunks; index++) {
                    require(field.paste(chunk), "The field must admit each literal chunk");
                    presentation.resolve(true, field.text(), ui, List.of(tab), 0);
                }
                require(field.length() == chunks * chunk.length(), "Accepted input was truncated");
            }
            String originalQuery = field.text();
            var rejected = presentation.resolve(true, originalQuery, ui, List.of(tab), 0);
            require(rejected.matchCount() == -1, "Memory refusal must be explicitly incomplete");
            require(rejected.query().isEmpty(), "A refused query must not reach render compilation");
            require(rejected.error().startsWith("Not enough memory to search"), "Missing memory-specific feedback");
            require(rejected.summary().equals("Not enough memory to search"), "The summary must show the short memory refusal");
            require(rejected.error().equals("Not enough memory to search this query; shorten the query"),
                "The full error must retain the memory guidance");
            require(field.text().equals(originalQuery), "Search refusal changed the field");
            for (int frame = 0; frame < 20; frame++) {
                require(presentation.resolve(true, field.text(), ui, List.of(tab), 0) == rejected,
                    "An unchanged refused fingerprint must reuse its snapshot");
            }

            var options = SearchController.toSearchOptions(ui);
            long revision = tab.model().revision();
            var view = tab.state().viewMemento();
            requireRefusal(() -> TabSearch.countMatches(List.of(tab), originalQuery, options));
            requireRefusal(() -> TabSearch.findNextAcrossTabs(List.of(tab), 0, originalQuery, options));
            requireRefusal(() -> tab.engine().findNext(originalQuery, options));
            requireRefusal(() -> tab.engine().findPrevious(originalQuery, options));
            requireRefusal(() -> tab.engine().replaceCurrentOrNext(originalQuery, "changed", options));
            requireRefusal(() -> tab.engine().replaceAll(originalQuery, "changed", options));
            require(tab.model().getLine(0).equals("unsaved document"), "A refused search changed unsaved text");
            require(tab.model().revision() == revision, "A refused search changed the revision");
            require(tab.state().viewMemento().equals(view), "A refused search changed the view");
            require(tab.state().isDirty(), "A refused search lost the dirty state");
            require(tab.engine().undo(), "Search refusals lost document undo history");
            require(tab.model().getLine(0).isEmpty(), "A refused search created an undo entry");
            require(tab.engine().redo(), "Search refusals lost document redo history");
            require(tab.model().getLine(0).equals("unsaved document"), "Redo lost unsaved text");

            tab.engine().insertInput("!");
            var afterSource = presentation.resolve(true, originalQuery, ui, List.of(tab), 0);
            require(afterSource != rejected,
                "A source revision must invalidate the refused fingerprint");
            var changedOptions = new SearchController.SearchUiOptions(!ui.caseSensitive(), ui.matchMode(), ui.allTabs());
            var afterOptions = presentation.resolve(true, originalQuery, changedOptions, List.of(tab), 0);
            require(afterOptions != afterSource, "Changed options must invalidate the refused fingerprint");
            field.selectAll();
            require(field.insert("unsaved"), "The refused input must remain editable");
            var recovered = presentation.resolve(true, field.text(), ui, List.of(tab), 0);
            require(field.text().equals("unsaved"), "Editing the query failed");
            require(recovered.matchCount() == 1 && recovered.error().isEmpty(), "A simpler query must recover");
            require(tab.engine().undo(), "Search refusals lost document undo history");
            require(tab.model().getLine(0).equals("unsaved document"), "Search refusals changed the source edit history");
            require(tab.engine().undo(), "Search refusals lost the original undo entry");
            require(tab.model().getLine(0).isEmpty(), "Search refusals created an extra undo entry");
        }

        private static void requireRefusal(Runnable action) {
            try {
                action.run();
                throw new AssertionError("Expected a controlled memory refusal");
            } catch (SearchLimitException limit) {
                require(limit.getMessage().startsWith("Not enough memory to search"), "Wrong resource error");
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
