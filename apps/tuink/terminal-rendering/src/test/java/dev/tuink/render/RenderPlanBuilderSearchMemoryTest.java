package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorState;
import dev.tuink.core.SearchMatcher;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.SearchWorkBudget;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RenderPlanBuilderSearchMemoryTest {
    @Test
    void refusedWildcardScanKeepsTheFrameAndCachesItsFailureUntilTheSourceChanges() {
        TextModel model = model("a".repeat(132));
        EditorState state = new EditorState();
        RenderPlanBuilder builder = builder(model);
        String query = "a".repeat(64) + "?a";
        SearchOptions options = new SearchOptions(false, SearchOptions.MatchMode.WILDCARD);
        var view = state.viewMemento();
        long revision = model.revision();

        RenderPlan refused = SearchWorkBudget.run(0,
            () -> builder.build(model, state, 4, 20, "top", "bottom", query, options));

        assertTrue(refused.lines().getFirst().spans().isEmpty());
        assertEquals("a".repeat(refused.lines().getFirst().text().length()), refused.lines().getFirst().text());
        assertEquals(view, state.viewMemento());
        assertEquals(revision, model.revision());
        RenderPlan cached = builder.build(model, state, 4, 20, "top", "bottom", query, options);
        assertTrue(cached.lines().getFirst().spans().isEmpty(), "Unchanged frames must reuse the refusal");

        model.replaceLines(List.of("a".repeat(133)));
        RenderPlan edited = builder.build(model, state, 4, 20, "top", "bottom", query, options);
        assertFalse(edited.lines().getFirst().spans().isEmpty(), "A source edit must retry optional highlighting");
    }

    @Test
    void rendererHandlesMemoryRefusalAfterEarlierCompilationRounds() throws Exception {
        var locations = new LinkedHashSet<String>();
        for (String name : List.of(
            getClass().getName(), "dev.tuink.render.RenderPlanBuilder", "dev.tuink.core.SearchMatcher", "dev.tuink.core.TextModel",
            "dev.tuink.editor.document.DocumentView", "dev.tuink.unicode.GraphemeText",
            "dev.tuiframe.cell.CellBuffer", "org.junit.jupiter.api.Assertions",
            "org.junit.platform.commons.util.StringUtils", "org.opentest4j.AssertionFailedError"
        )) locations.add(Path.of(Class.forName(name, false, getClass().getClassLoader())
            .getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-cp", String.join(File.pathSeparator, locations), Probe.class.getName())
            .redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Search render probe exceeded its deadline");
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
        } finally {
            child.destroyForcibly();
        }
    }

    private static TextModel model(String text) {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, text);
        return new TextModel(buffer);
    }

    private static RenderPlanBuilder builder(TextModel model) {
        RenderPlanBuilder builder = RenderPlanBuilder.standalone(line -> List.of(), new TerminalEditorTextLayout(model));
        builder.setSyntaxHighlightingEnabled(false);
        builder.setBracketMatchingEnabled(false);
        builder.setRainbowBracketsEnabled(false);
        builder.setDecorationStyles(Style.DEFAULT, Style.fg(Color.RED), Style.fg(Color.BLUE),
            Style.DEFAULT, Style.DEFAULT, Style.DEFAULT, List.of(Style.DEFAULT));
        return builder;
    }

    public static final class Probe {
        public static void main(String[] args) {
            String query = "a".repeat(1_048_576);
            TextModel model = model(query);
            EditorState state = new EditorState();
            RenderPlanBuilder builder = builder(model);
            var view = state.viewMemento();
            long revision = model.revision();
            RenderPlan refused = SearchWorkBudget.run(Long.MAX_VALUE, () -> {
                SearchMatcher.compile(query, SearchOptions.DEFAULT);
                SearchMatcher.compile(query, SearchOptions.DEFAULT);
                return builder.build(model, state, 4, 20, "top", "bottom", query, SearchOptions.DEFAULT);
            });
            require(refused.lines().getFirst().spans().isEmpty(), "The refused matcher must omit optional highlights");
            require(!refused.lines().getFirst().text().isEmpty(), "The document frame must remain visible");
            require(state.viewMemento().equals(view) && model.revision() == revision, "Rendering changed the editor");
            System.gc();
            RenderPlan cached = builder.build(model, state, 4, 20, "top", "bottom", query, SearchOptions.DEFAULT);
            require(cached.lines().getFirst().spans().isEmpty(), "An unchanged refused fingerprint must stay cached");
            SearchOptions changed = new SearchOptions(true, SearchOptions.MatchMode.SUBSTRING);
            RenderPlan options = builder.build(model, state, 4, 20, "top", "bottom", query, changed);
            require(!options.lines().getFirst().spans().isEmpty(), "Changed options must retry highlighting");
            RenderPlan simpler = builder.build(model, state, 4, 20, "top", "bottom", "a", SearchOptions.DEFAULT);
            require(!simpler.lines().getFirst().spans().isEmpty(), "Changing the query must restore highlighting");
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
