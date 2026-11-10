package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import static dev.tuink.tui.PreviewSelectionTest.*;

import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.markdown.commonmark.CommonmarkMarkdownParser;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuink.tui.render.EditorStyleAdapter;
import dev.tuink.tui.style.UiStyles;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MarkdownPreviewInteractionTest {
    @Test void pointerUsesWindowOriginAndSelectionCopiesOnlyItsRange() {
        var state = ready(result("alpha beta", line("alpha beta", 0, 10, 0, 10)), new Rect(5, 3, 21, 4));
        state.pointerAt(3, 8, false);
        state.pointerAt(3, 11, true);
        assertEquals("ha ", state.selectedText());
        state.pointerAt(3, 5, false);
        assertEquals(0, state.selection.caret(), "the first row is document content");
        assertFalse(state.selection.snapshot().selected());
        state.close();
    }

    @Test void heightOnlyResizeClampsViewportWithoutResubmittingParse() throws Exception {
        var input = source(List.of("```", "x", "x", "x", "x", "x", "x", "x", "x", "x", "x", "```"));
        var parser = new CommonmarkMarkdownParser(); var calls = new AtomicInteger();
        try (var service = new MarkdownPreviewService((snapshot, limits, control) -> {
            calls.incrementAndGet(); return parser.parse(snapshot, limits, control);
        }, () -> {}); var state = new MarkdownPreviewState()) {
            accept(state, input, new Rect(0, 0, 21, 3), service);
            int rows = state.result.lines().size();
            state.top = rows - 2;
            state.refresh(input, 0, new Rect(0, 0, 21, 8), service);
            assertEquals(rows - state.contentBounds().height(), state.top);
            assertNull(state.pending);
            assertEquals(1, calls.get());
        }
    }

    @Test void suspendReleasesLayoutAndActivateRestoresSemanticSelectionAndAnchor() throws Exception {
        String text = "alpha beta gamma delta";
        var input = source(List.of(text));
        try (var service = new MarkdownPreviewService(() -> {}); var state = new MarkdownPreviewState()) {
            accept(state, input, new Rect(0, 0, 11, 2), service);
            state.selection.selectRange(text, 6, 10); state.top = 1;
            state.suspend();
            assertNull(state.result); assertNull(state.request); assertFalse(state.ready());
            accept(state, input, new Rect(0, 0, 7, 2), service);
            assertEquals("beta", state.selectedText()); assertEquals(2, state.top);
        }
    }

    @Test void staleResultCannotBeCopiedOrPaintedAndNewRevisionResetsSelection() {
        var state = new MarkdownPreviewState(); state.active = true; state.bounds = new Rect(0, 0, 21, 4);
        admit(state, result("alpha", line("alpha", 0, 5, 0, 5)));
        state.selectAll(); state.requestedRevision = 1;
        assertFalse(state.ready());
        assertEquals("", state.selectedText());
        assertNull(state.placement(MarkdownPreviewState.Placement.Focus.CONTENT).result());
        admit(state, result(1, 20, "beta", line("beta", 0, 4, 0, 4)));
        assertEquals(state.selectionStart(), state.selectionEnd());
        state.close();
    }

    @Test void semanticSearchSelectsMatchesAndWrapsWithoutChangingDocument() {
        var state = ready(result("alpha beta alpha", line("alpha beta alpha", 0, 16, 0, 16)), new Rect(0, 0, 21, 4));
        state.setSearchQuery("alpha");
        assertEquals(2, state.searchMatches.size());
        state.nextMatch(false);
        assertEquals("alpha", state.selectedText());
        assertEquals(0, state.selectionStart());
        state.nextMatch(false);
        assertEquals(11, state.selectionStart());
        state.nextMatch(false);
        assertEquals(0, state.selectionStart());
        state.nextMatch(true);
        assertEquals(11, state.selectionStart());
        assertEquals(0, state.result.document().revision());
        state.close();
    }

    @Test void sharedWildcardMatchingRespectsProjectionLinesAndOriginalUtf16Offsets() {
        String text = "🙂 ALPHA alpha\nalphaX\nİx Ix ıx ix\n";
        try (var state = ready(result(text, line(text, 0, text.length(), 0, text.length())), new Rect(0, 0, 80, 4))) {
            state.setSearchQuery("alpha");
            assertEquals(List.of(new PreviewSelection.Range(3, 8), new PreviewSelection.Range(9, 14),
                new PreviewSelection.Range(15, 20)), state.searchMatches);
            state.setSearchOptions(new SearchOptions(true, SearchOptions.MatchMode.WILDCARD));
            state.setSearchQuery("a*x");
            assertEquals(List.of(new PreviewSelection.Range(15, 21)), state.searchMatches,
                "stars never cross logical projection line separators");
            state.setSearchQuery("i?");
            assertEquals(List.of(new PreviewSelection.Range(22, 24), new PreviewSelection.Range(25, 27),
                new PreviewSelection.Range(28, 30), new PreviewSelection.Range(31, 33)), state.searchMatches);
        }
    }

    @Test void boundedSearchFailureKeepsTheAcceptedDocumentQuerySelectionAndViewportAndCachesTheFailure() {
        String text = "a".repeat(132);
        var result = result(text, line(text, 0, text.length(), 0, text.length()));
        try (var state = ready(result, new Rect(0, 0, 80, 4), 0)) {
            state.beginSearch();
            var options = new SearchOptions(false, SearchOptions.MatchMode.WILDCARD);
            state.setSearchOptions(options);
            var selection = state.selection.snapshot(); int top = state.top, left = state.left;
            String query = "a".repeat(64) + "?a";
            state.setSearchQuery(query);
            assertEquals(query, state.searchField.text());
            assertEquals(query, state.searchQuery);
            assertEquals("Search is too complex", state.searchFailure.summary());
            assertTrue(state.searchMatches.isEmpty());
            assertEquals(-1, state.activeMatch);
            assertEquals("", state.error, "a rejected search must never hide the accepted Markdown document");
            assertSame(result, state.placement(MarkdownPreviewState.Placement.Focus.CONTENT).result());
            assertTrue(state.searchVisible);
            var failure = state.searchFailure;
            state.setSearchQuery(query); state.setSearchOptions(options); state.nextMatch(false); state.placement(MarkdownPreviewState.Placement.Focus.CONTENT);
            assertSame(failure, state.searchFailure, "stable frames and repeated Find must not retry rejected work");
            assertEquals(selection, state.selection.snapshot());
            assertEquals(top, state.top); assertEquals(left, state.left);
            state.setSearchQuery("a?a");
            assertEquals(44, state.searchMatches.size());
            assertEquals(MarkdownPreviewState.SearchFailure.NONE, state.searchFailure);
        }
    }

    @Test void matchingStopsAtTheRetainedResultCapAcrossLogicalProjectionLines() {
        String text = String.join("\n", java.util.Collections.nCopies(MarkdownPreviewState.SEARCH_MATCH_LIMIT + 1, "a"));
        try (var state = ready(result(text, line("a", 0, 1, 0, 1)), new Rect(0, 0, 80, 4))) {
            state.setSearchQuery("a");
            assertEquals(MarkdownPreviewState.SEARCH_MATCH_LIMIT, state.searchMatches.size());
            assertEquals(new PreviewSelection.Range(19_998, 19_999), state.searchMatches.getLast());
            var matches = state.searchMatches;
            state.setSearchQuery("a"); state.placement(MarkdownPreviewState.Placement.Focus.CONTENT); state.nextMatch(false);
            assertSame(matches, state.searchMatches, "cursor-only frames reuse the accepted matches");
        }
    }

    @Test void painterPreservesTabPhaseAndHighlightsOnlyMappedContent() {
        var palette = UiStyles.forTheme(UiStyles.Theme.CYAN);
        var line = new MarkdownLayout.Line("│ a\t字", List.of(new MarkdownLayout.Run(2, 5,
            MarkdownLayout.Role.CODE_BLOCK, SOURCE, 0, 3)), "a\t字", SOURCE, "code");
        var result = result("a\t字", line);
        var placement = new MarkdownPreviewState.Placement(new Rect(4, 2, 8, 3), result, 0, 0, false, "",
            new PreviewSelection.Snapshot(1, 2), List.of(new PreviewSelection.Range(2, 3)), 0, "字", false, null, null, null,
            MarkdownPreviewState.Placement.Focus.CONTENT, MarkdownPreviewState.SearchFailure.NONE);
        var buffer = new CellBuffer(8, 20);
        MarkdownPreviewPainter.paint(new Canvas(buffer), placement, palette, true);
        assertEquals(EditorStyleAdapter.toCell(palette.windows().renderSelection()), buffer.get(2, 7).style());
        assertEquals(EditorStyleAdapter.toCell(palette.windows().renderCurrentSearchMatch()), buffer.get(2, 8).style());
        assertEquals(EditorStyleAdapter.toCell(palette.windows().renderCurrentSearchMatch()), buffer.get(2, 9).style());
        assertNotEquals(EditorStyleAdapter.toCell(palette.windows().renderSelection()), buffer.get(2, 4).style());
    }

    @Test void painterClipsAStyledRunAndItsTabsAtANonzeroViewportOrigin() {
        var palette = UiStyles.forTheme(UiStyles.Theme.CYAN);
        var result = result("a\tbcde", line("a\tbcde", 0, 6, 0, 6));
        var buffer = new CellBuffer(5, 15);
        MarkdownPreviewPainter.paint(new Canvas(buffer), new MarkdownPreviewState.Placement(
            new Rect(5, 1, 5, 3), result, 0, 2, false, "", PreviewSelection.Snapshot.EMPTY, List.of(), -1, "",
            false, null, null, null, MarkdownPreviewState.Placement.Focus.CONTENT, MarkdownPreviewState.SearchFailure.NONE), palette, true);
        assertEquals(" ", buffer.get(1, 5).grapheme());
        assertEquals(" ", buffer.get(1, 6).grapheme());
        assertEquals("b", buffer.get(1, 7).grapheme());
        assertEquals("c", buffer.get(1, 8).grapheme());
    }

    private static MarkdownPreviewState ready(MarkdownLayout.Result result, Rect bounds) {
        return ready(result, bounds, SearchPresentation.INTERACTIVE_SEARCH_WORK_LIMIT);
    }
    private static MarkdownPreviewState ready(MarkdownLayout.Result result, Rect bounds, long maximumWork) {
        var state = new MarkdownPreviewState(maximumWork);
        state.active = true; state.result = result; state.bounds = bounds;
        state.requestedId = ID; state.requestedRevision = result.document().revision(); state.requestedWidth = result.columns();
        return state;
    }
    private static void admit(MarkdownPreviewState state, MarkdownLayout.Result result) {
        state.requestedId = ID; state.requestedRevision = result.document().revision(); state.requestedWidth = result.columns();
        state.request = state.coordinator.prepare(new DocumentSnapshot(ID, result.document().revision(), List.of("source")));
        state.pending = new FutureTask<>(() -> result); state.pending.run();
        assertTrue(state.collect(ID, result.document().revision()));
    }
    private static FrozenDocumentSource source(List<String> lines) {
        return new FrozenDocumentSource(new DocumentSnapshot(ID, 0, lines), DocumentFileMetadata.newDocument(lines.size()), ResourceBinding.unbound());
    }
    private static void accept(MarkdownPreviewState state, FrozenDocumentSource input, Rect bounds,
                               MarkdownPreviewService service) throws Exception {
        assertTrue(state.activate(input, 0, bounds, service));
        assertNotNull(state.pending); state.pending.get(3, TimeUnit.SECONDS);
        assertTrue(state.collect(ID, 0)); assertTrue(state.ready());
    }
}
