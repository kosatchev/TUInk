package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorEngineAutoPairContextTest {
    @TempDir
    Path tempDir;

    @Test
    void backspaceAfterTypingAnEscapedQuoteKeepsTheOriginalClosingQuote() throws IOException {
        for (char quote : new char[] {'\'', '"'}) {
            Fixture fixture = fixture(List.of(""));
            fixture.engine.insertChar(quote);
            fixture.engine.insertChar('\\');
            fixture.engine.insertChar(quote);
            String typed = "" + quote + '\\' + quote + quote;
            assertEquals(typed, fixture.model.getLine(0));
            assertEquals(new Position(0, 3), fixture.state.getCursor());
            fixture.engine.saveAs(tempDir.resolve("quote-" + (int) quote + ".txt"));

            fixture.engine.backspace();

            String expected = "" + quote + '\\' + quote;
            assertEquals(expected, fixture.model.getLine(0));
            assertEquals(new Position(0, 2), fixture.state.getCursor());
            assertFalse(fixture.state.hasSelection());
            assertTrue(fixture.state.isDirty());
            assertTrue(fixture.engine.undo());
            assertEquals(typed, fixture.model.getLine(0));
            assertEquals(new Position(0, 3), fixture.state.getCursor());
            assertFalse(fixture.state.isDirty());
            assertTrue(fixture.engine.redo());
            assertEquals(expected, fixture.model.getLine(0));
            assertEquals(new Position(0, 2), fixture.state.getCursor());
            assertTrue(fixture.state.isDirty());
        }
    }

    @Test
    void deletingQuotePairsUsesTheEscapeParityOfTheLeftQuote() {
        for (char quote : new char[] {'\'', '"'}) {
            for (int backslashes = 0; backslashes <= 4; backslashes++) {
                String prefix = " " + "\\".repeat(backslashes);
                String original = prefix + quote + quote;
                Fixture fixture = fixture(List.of(original));
                Position before = new Position(0, prefix.length() + 1);
                fixture.engine.moveCursorTo(before);

                fixture.engine.backspace();

                String expected = prefix + (backslashes % 2 == 0 ? "" : String.valueOf(quote));
                assertEquals(expected, fixture.model.getLine(0), original);
                assertEquals(new Position(0, prefix.length()), fixture.state.getCursor());
                assertTrue(fixture.engine.undo());
                assertEquals(original, fixture.model.getLine(0));
                assertEquals(before, fixture.state.getCursor());
                assertFalse(fixture.state.isDirty());
                assertFalse(fixture.engine.undo());
                assertTrue(fixture.engine.redo());
                assertEquals(expected, fixture.model.getLine(0));
            }
        }
    }

    @Test
    void backslashesDoNotDisableDeletionOfOrdinaryBracketPairs() {
        for (String pair : List.of("()", "[]", "{}")) {
            Fixture fixture = fixture(List.of("\\" + pair));
            fixture.engine.moveCursorTo(new Position(0, 2));

            fixture.engine.backspace();

            assertEquals("\\", fixture.model.getLine(0));
            assertTrue(fixture.engine.undo());
            assertEquals("\\" + pair, fixture.model.getLine(0));
        }
    }

    @Test
    void replacingASelectionUsesItsSurvivingLeftWordNeighborInEitherDirection() {
        for (String word : List.of("x", "René", "Rene\u0301", "𝒜", "𐐀", "_", "7")) {
            for (boolean reverse : List.of(false, true)) {
                Fixture fixture = fixture(List.of(word + "' "));
                select(fixture, new Position(0, word.length()), new Position(0, word.length() + 2), reverse);

                fixture.engine.insertChar('\'');

                assertEquals(word + "'", fixture.model.getLine(0), word + ", reverse=" + reverse);
                assertEquals(new Position(0, word.length() + 1), fixture.state.getCursor());
                assertFalse(fixture.state.hasSelection());
            }
        }
    }

    @Test
    void replacingASelectionUsesItsSurvivingRightWordNeighborInEitherDirection() {
        for (String word : List.of("x", "René", "Rene\u0301", "𝒜", "𐐀", "_", "7")) {
            for (boolean reverse : List.of(false, true)) {
                Fixture fixture = fixture(List.of(" ' " + word));
                select(fixture, new Position(0, 1), new Position(0, 3), reverse);

                fixture.engine.insertChar('\'');

                assertEquals(" '" + word, fixture.model.getLine(0), word + ", reverse=" + reverse);
                assertEquals(new Position(0, 2), fixture.state.getCursor());
            }
        }
    }

    @Test
    void replacingASelectionStillPairsBetweenNonWordNeighbors() {
        for (String prefix : List.of("", " ", "😀", "!\u0301")) {
            for (String suffix : List.of("", " ", ")", ",", ";", ":")) {
                for (boolean reverse : List.of(false, true)) {
                    String selected = "'selected";
                    Fixture fixture = fixture(List.of(prefix + selected + suffix));
                    select(fixture, new Position(0, prefix.length()),
                        new Position(0, prefix.length() + selected.length()), reverse);

                    fixture.engine.insertChar('\'');

                    assertEquals(prefix + "''" + suffix, fixture.model.getLine(0));
                    assertEquals(new Position(0, prefix.length() + 1), fixture.state.getCursor());
                }
            }
        }
    }

    @Test
    void replacingASelectionUsesTheSurvivingBackslashParity() {
        for (int backslashes = 1; backslashes <= 4; backslashes++) {
            for (boolean reverse : List.of(false, true)) {
                String prefix = " " + "\\".repeat(backslashes);
                Fixture fixture = fixture(List.of(prefix + "'selected"));
                select(fixture, new Position(0, prefix.length()),
                    new Position(0, prefix.length() + "'selected".length()), reverse);

                fixture.engine.insertChar('\'');

                assertEquals(prefix + "'".repeat(backslashes % 2 == 0 ? 2 : 1), fixture.model.getLine(0));
                assertEquals(new Position(0, prefix.length() + 1), fixture.state.getCursor());
            }
        }
    }

    @Test
    void multilineReplacementUsesJoinedNeighborsAndRestoresSelectionAndMetadata() {
        for (String prefix : List.of("𝒜", ".")) {
            for (String suffix : List.of("𐐀", ")")) {
                for (boolean reverse : List.of(false, true)) {
                    Fixture fixture = fixture(List.of(prefix + "'", "selected", "  " + suffix), new DocumentFileMetadata(
                        StandardCharsets.UTF_8, BomKind.UTF_8, "\n", List.of("\r\n", "\r")
                    ));
                    List<String> original = fixture.model.snapshotLines();
                    DocumentFileMetadata metadata = fixture.model.fileMetadata();
                    Position start = new Position(0, prefix.length());
                    Position end = new Position(2, 2);
                    select(fixture, start, end, reverse);

                    fixture.engine.insertChar('\'');

                    String expected = prefix + (prefix.equals(".") && suffix.equals(")") ? "''" : "'") + suffix;
                    assertEquals(List.of(expected), fixture.model.snapshotLines());
                    assertTrue(fixture.engine.undo());
                    assertEquals(original, fixture.model.snapshotLines());
                    assertEquals(metadata, fixture.model.fileMetadata());
                    assertEquals(reverse ? start : end, fixture.state.getCursor());
                    assertEquals(reverse ? end : start, fixture.state.getSelectionAnchor());
                    assertFalse(fixture.state.isDirty());
                    assertFalse(fixture.engine.undo());
                    assertTrue(fixture.engine.redo());
                    assertEquals(List.of(expected), fixture.model.snapshotLines());
                    assertFalse(fixture.state.hasSelection());
                }
            }
        }
    }

    @Test
    void selectionReplacementRoundTripsAcrossTheSavedHistoryPosition() throws IOException {
        for (boolean reverse : List.of(false, true)) {
            Fixture fixture = fixture(List.of("x' "));
            Position start = new Position(0, 1);
            Position end = new Position(0, 3);
            select(fixture, start, end, reverse);

            fixture.engine.insertChar('\'');
            assertEquals("x'", fixture.model.getLine(0));
            assertTrue(fixture.state.isDirty());
            fixture.engine.saveAs(tempDir.resolve("selection-" + reverse + ".txt"));
            assertFalse(fixture.state.isDirty());

            assertTrue(fixture.engine.undo());
            assertEquals("x' ", fixture.model.getLine(0));
            assertEquals(reverse ? start : end, fixture.state.getCursor());
            assertEquals(reverse ? end : start, fixture.state.getSelectionAnchor());
            assertTrue(fixture.state.isDirty());
            assertFalse(fixture.engine.undo());
            assertTrue(fixture.engine.redo());
            assertEquals("x'", fixture.model.getLine(0));
            assertEquals(new Position(0, 2), fixture.state.getCursor());
            assertFalse(fixture.state.hasSelection());
            assertFalse(fixture.state.isDirty());
        }
    }

    @Test
    void ordinarySelectionWrappingKeepsItsContentAndSelection() {
        for (char open : new char[] {'\'', '"', '(', '[', '{'}) {
            for (boolean reverse : List.of(false, true)) {
                Fixture fixture = fixture(List.of("L😀e\u0301R"));
                Position start = new Position(0, 1);
                Position end = new Position(0, 5);
                select(fixture, start, end, reverse);
                char close = switch (open) {
                    case '(' -> ')';
                    case '[' -> ']';
                    case '{' -> '}';
                    default -> open;
                };

                fixture.engine.insertChar(open);

                assertEquals("L" + open + "😀e\u0301" + close + "R", fixture.model.getLine(0));
                assertEquals(new Position(0, 2), fixture.state.getSelectionAnchor());
                assertEquals(new Position(0, 6), fixture.state.getCursor());
                assertTrue(fixture.engine.undo());
                assertEquals("L😀e\u0301R", fixture.model.getLine(0));
                assertEquals(reverse ? start : end, fixture.state.getCursor());
                assertEquals(reverse ? end : start, fixture.state.getSelectionAnchor());
            }
        }
    }

    @Test
    void autoPairContextDoesNotMaterializeTheSelectedTextBeforeCapturingHistory() {
        class RangeCountingModel extends TextModel {
            int rangeCopies;

            RangeCountingModel() {
                super(new SimpleLineBuffer());
            }

            @Override
            public String getTextRange(Position start, Position end) {
                rangeCopies++;
                return super.getTextRange(start, end);
            }
        }
        RangeCountingModel model = new RangeCountingModel();
        List<String> lines = new ArrayList<>();
        lines.add("😀'selected");
        for (int line = 0; line < 2_000; line++) {
            lines.add("selected content");
        }
        lines.add("selected)");
        model.replaceLines(lines);
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        engine.restoreSelection(new Position(lines.size() - 1, 8), new Position(0, 2));

        engine.insertChar('\'');

        assertEquals(List.of("😀'')"), model.snapshotLines());
        assertEquals(1, model.rangeCopies, "Only the edit delta needs a copy of the selected text");
    }

    private static void select(Fixture fixture, Position start, Position end, boolean reverse) {
        fixture.engine.restoreSelection(reverse ? start : end, reverse ? end : start);
    }

    private static Fixture fixture(List<String> lines) {
        return fixture(lines, DocumentFileMetadata.newDocument(lines.size()));
    }

    private static Fixture fixture(List<String> lines, DocumentFileMetadata metadata) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines, metadata);
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        return new Fixture(model, state, engine);
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {
    }
}
