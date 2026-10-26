package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.Position;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.TabSession;
import dev.tuink.render.TerminalEditorTextLayout;
import org.junit.jupiter.api.Test;

class SearchSelectionTest {
    @Test
    void explicitWhitespaceSelectionCanPrefillSearchWithoutEnablingAutomaticHighlighting() {
        TabSession tab = selected("\t  \t");

        assertEquals("\t  \t", SearchSelection.prefill(tab.model(), tab.state()));
        assertTrue(SearchSelection.matchesQuery(tab.model(), tab.state(), "\t  \t", SearchOptions.DEFAULT));
        assertNull(SearchSelection.highlightQuery(false, "", tab.model(), tab.state(), true, false));
        assertEquals("\t  \t", SearchSelection.highlightQuery(true, "\t  \t", tab.model(), tab.state(), true, true));
    }

    @Test
    void matchingAnExistingSelectionHasNoAutomaticPrefillLengthLimit() {
        String query = "x".repeat(300);
        TabSession tab = selected(query);

        assertNull(SearchSelection.prefill(tab.model(), tab.state()));
        assertNull(SearchSelection.highlightQuery(false, "", tab.model(), tab.state(), true, false));
        assertTrue(SearchSelection.matchesQuery(tab.model(), tab.state(), query, SearchOptions.DEFAULT));
        tab.engine().restoreSelection(new Position(0, 0), new Position(0, query.length()));
        assertTrue(SearchSelection.matchesQuery(tab.model(), tab.state(), query, SearchOptions.DEFAULT));
    }

    @Test
    void automaticHighlightingRespectsItsOwnLimitAndTheLiveSelectionGesture() {
        TabSession tab = selected("word");
        assertEquals("word", SearchSelection.highlightQuery(false, "", tab.model(), tab.state(), true, false));
        assertNull(SearchSelection.highlightQuery(false, "", tab.model(), tab.state(), false, false));
        assertNull(SearchSelection.highlightQuery(false, "", tab.model(), tab.state(), true, true));
    }

    private static TabSession selected(String text) {
        TabSession session = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        session.engine().insertInput(text);
        session.engine().selectAll();
        return session;
    }
}
