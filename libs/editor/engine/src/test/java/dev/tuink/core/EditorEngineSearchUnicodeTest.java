package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EditorEngineSearchUnicodeTest {
    private static final SearchOptions CASE_INSENSITIVE = new SearchOptions(
        true,
        SearchOptions.MatchMode.SUBSTRING
    );

    @Test
    void navigationAndCountingUseOriginalUtf16OffsetsAfterCaseFolding() {
        TextModel model = model("İx İX");
        EditorState state = new EditorState();
        EditorEngine engine = engine(model, state);

        assertEquals(2, TabSearch.countMatchesInModel(model, "x", CASE_INSENSITIVE));
        assertTrue(engine.findNext("x", CASE_INSENSITIVE));
        assertEquals(new Position(0, 1), state.getSelectionAnchor());
        assertEquals(new Position(0, 2), state.getCursor());

        assertTrue(engine.findNext("x", CASE_INSENSITIVE));
        assertEquals(new Position(0, 4), state.getSelectionAnchor());
        assertEquals(new Position(0, 5), state.getCursor());
    }

    @Test
    void replaceAllUsesOriginalRangesWhenLowercaseWouldExpandTheSource() {
        TextModel model = model("İx İX");
        EditorEngine engine = engine(model, new EditorState());

        assertEquals(2, engine.replaceAll("x", "!", CASE_INSENSITIVE));
        assertEquals("İ! İ!", model.getLine(0));
    }

    @Test
    void wildcardReplaceUsesTheFullSupplementarySourceRange() {
        TextModel model = model("𝔸x");
        EditorEngine engine = engine(model, new EditorState());
        SearchOptions wildcard = new SearchOptions(false, SearchOptions.MatchMode.WILDCARD);

        assertEquals(1, engine.replaceAll("?x", "!", wildcard));
        assertEquals("!", model.getLine(0));
    }

    @Test
    void wholeWordReplaceDoesNotSplitACombiningSequence() {
        TextModel model = model("e\u0301 e");
        EditorEngine engine = engine(model, new EditorState());
        SearchOptions wholeWord = new SearchOptions(false, SearchOptions.MatchMode.WHOLE_WORD);

        assertEquals(1, engine.replaceAll("e", "!", wholeWord));
        assertEquals("e\u0301 !", model.getLine(0));
    }

    @Test
    void replaceCurrentRejectsASelectionThatIsNotAnActualWholeWordMatch() {
        TextModel model = model("𝔸x x");
        EditorState state = new EditorState();
        state.setSelectionAnchor(new Position(0, 2));
        state.setCursor(new Position(0, 3));
        EditorEngine engine = engine(model, state);
        SearchOptions wholeWord = new SearchOptions(false, SearchOptions.MatchMode.WHOLE_WORD);

        assertTrue(engine.replaceCurrentOrNext("x", "!", wholeWord));
        assertEquals("𝔸x !", model.getLine(0));
    }

    private static TextModel model(String text) {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, text);
        return new TextModel(buffer);
    }

    private static EditorEngine engine(TextModel model, EditorState state) {
        return new EditorEngine(model, state, new TestEditorTextLayout(model));
    }
}
