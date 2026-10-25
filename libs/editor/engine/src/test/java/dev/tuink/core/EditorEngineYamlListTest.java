package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditorEngineYamlListTest {
    private static final List<String> BLOCK_INDICATORS = List.of(
        "|", "|-", "|+", "|2", "|2-", "|-2", "|2+", "|+2",
        ">", ">-", ">+", ">2", ">2-", ">-2", ">2+", ">+2");

    @Test
    void blockScalarsNeverContinueLiteralDashesIncludingChompingAndIndentIndicators() {
        for (String extension : List.of("yaml", "yml")) {
            for (String indicator : BLOCK_INDICATORS) {
                for (boolean autoIndent : List.of(false, true)) {
                    assertNewline(extension, List.of("description: " + indicator + " # note", "  - literal text"),
                        autoIndent, autoIndent ? "  " : "");
                    assertNewline(extension, List.of("settings:", "  description: " + indicator, "    - [x] literal"),
                        autoIndent, autoIndent ? "    " : "");
                }
            }
        }
    }

    @Test
    void compactMappingScalarsReleaseFollowingKeysAndSequencesAtTheMappingIndent() {
        for (String extension : List.of("yaml", "yml")) {
            for (String prefix : List.of("- ", "- - ", "  - - ", "-   ")) {
                String mappingIndent = " ".repeat(prefix.length());
                String scalarIndent = mappingIndent + "  ";
                for (String indicator : BLOCK_INDICATORS) {
                    String header = prefix + "description: " + indicator + " # note";
                    for (boolean empty : List.of(false, true)) {
                        List<String> scalar = empty
                            ? List.of(header, "")
                            : List.of(header, scalarIndent + "text", "");
                        List<String> followingKey = new ArrayList<>(scalar);
                        followingKey.add(mappingIndent + "items:");
                        assertNewline(extension, followingKey, true, scalarIndent,
                            TabInsertMode.ALWAYS_SPACES_2);

                        List<String> followingSequence = new ArrayList<>(followingKey);
                        followingSequence.add(scalarIndent + "- value");
                        for (boolean autoIndent : List.of(false, true)) {
                            assertNewline(extension, followingSequence, autoIndent,
                                (autoIndent ? scalarIndent : "") + "- ");
                        }
                    }
                }
            }
        }
    }

    @Test
    void compactMappingScalarContentKeepsLiteralKeysAndDashes() {
        for (String prefix : List.of("- ", "- - ", "  - - ", "-   ")) {
            String scalarIndent = " ".repeat(prefix.length() + 2);
            for (String indicator : BLOCK_INDICATORS) {
                String header = prefix + "'description': &text !!str " + indicator + " # note";
                for (String content : List.of("- literal", "items:")) {
                    assertNewline("yaml", List.of(header, scalarIndent + content), true,
                        scalarIndent, TabInsertMode.ALWAYS_SPACES_2);
                }
            }
        }
    }

    @Test
    void compactSequenceScalarsUseTheirInnermostDashIndentEvenWithNodeProperties() {
        for (String prefix : List.of("- ", "- - ", "  - - ", "-   -   ")) {
            String sequenceIndent = " ".repeat(prefix.lastIndexOf('-'));
            String scalarIndent = sequenceIndent + "  ";
            for (String indicator : BLOCK_INDICATORS) {
                for (String properties : List.of("", "&text !!str ")) {
                    String header = prefix + properties + indicator;
                    assertNewline("yaml", List.of(header, scalarIndent + "- literal"), true, scalarIndent);
                    for (boolean empty : List.of(false, true)) {
                        List<String> lines = empty
                            ? List.of(header, sequenceIndent + "- value")
                            : List.of(header, scalarIndent + "text", sequenceIndent + "- value");
                        assertNewline("yaml", lines, true, sequenceIndent + "- ");
                    }
                }
            }
        }
    }

    @Test
    void multilineQuotedScalarsDoNotContinueDashesEvenWhenTheQuoteClosesBeforeTheCursor() {
        for (String extension : List.of("yaml", "yml")) {
            for (boolean autoIndent : List.of(false, true)) {
                for (List<String> lines : List.of(
                    List.of("description: \"first", "  - literal text"),
                    List.of("description: 'first", "  - literal text"),
                    List.of("description: \"first", "  - literal text\""),
                    List.of("description: 'first", "  - literal text'"),
                    List.of("description: 'first''quote", "  - literal text"),
                    List.of("description: \"first\\\"quote", "  - literal text"),
                    List.of("description: &text \"first", "  - literal text"),
                    List.of("description: !!str 'first", "  - literal text"),
                    List.of("description: &text !<tag:yaml.org,2002:str> \"first", "  - literal text"),
                    List.of("{\"description\":\"first", "  - literal text")
                )) {
                    assertNewline(extension, lines, autoIndent, autoIndent ? "  " : "");
                }
            }
        }
    }

    @Test
    void aRealMarkerCannotContinueIntoTheScalarItIntroduces() {
        for (String extension : List.of("yaml", "yml")) {
            for (String text : List.of("  - |", "  - >-", "  - |2+", "  - \"unfinished", "  - 'unfinished")) {
                assertNewline(extension, List.of("items:", text), true, "  ");
                assertNewline(extension, List.of("items:", text), false, "");
            }
        }
    }

    @Test
    void realSequencesContinueAfterClosedQuotesCommentsAndFinishedScalars() {
        for (String extension : List.of("yaml", "yml")) {
            for (boolean autoIndent : List.of(false, true)) {
                for (String text : List.of("  - item", "  - item # note", "  - \"finished\"", "  - 'finished'",
                    "  - \"escaped \\\"quote\"", "  - 'doubled '' quote'", "  - 'C:\\'", "  - item |", "  - item >",
                    "  - # note", "  - [X] task")) {
                    String marker = text.startsWith("  - [X]") ? "- [ ] " : "- ";
                    assertNewline(extension, List.of("items:", text), autoIndent,
                        (autoIndent ? "  " : "") + marker);
                }
                assertNewline(extension, List.of("description: |", "  - literal", "items:", "  - item"),
                    autoIndent, (autoIndent ? "  " : "") + "- ");
                assertNewline(extension, List.of("description: \"first", "  - literal\"", "items:", "  - item"),
                    autoIndent, (autoIndent ? "  " : "") + "- ");
                assertNewline(extension, List.of("description: 'C:\\'", "items:", "  - item"),
                    autoIndent, (autoIndent ? "  " : "") + "- ");
            }
        }
    }

    @Test
    void quotesInsidePlainScalarsCannotHideRealSequencesBelowThem() {
        for (String plain : List.of("John's app", "a plain \"quote", "a plain 'quote", "http:\"quote",
            "a plain - \"quote")) {
            assertNewline("yaml", List.of("description: " + plain, "items:", "  - item"), true, "  - ");
        }
    }

    @Test
    void explicitSelectionsKeepTheirRangesAndUndoStateInsideAndOutsideLiterals() {
        for (boolean literal : List.of(false, true)) {
            List<String> original = List.of(literal ? "description: |" : "items:", "  - item trailing");
            Fixture fixture = fixture("yaml", original);
            Position anchor = new Position(1, "  - item".length());
            Position cursor = new Position(1, original.getLast().length());
            fixture.engine().restoreSelection(cursor, anchor);

            fixture.engine().insertNewline();

            List<String> expected = List.of(original.getFirst(), "  - item", literal ? "  " : "  - ");
            assertEquals(expected, fixture.model().snapshotLines());
            assertFalse(fixture.state().hasSelection());
            assertTrue(fixture.engine().undo());
            assertEquals(original, fixture.model().snapshotLines());
            assertEquals(anchor, fixture.state().getSelectionAnchor());
            assertEquals(cursor, fixture.state().getCursor());
            assertFalse(fixture.state().isDirty());
            assertTrue(fixture.engine().redo());
            assertEquals(expected, fixture.model().snapshotLines());
        }
    }

    @Test
    void changingAnEarlierScalarHeaderInvalidatesWarmListContextInBothDirections() {
        for (boolean originallyLiteral : List.of(false, true)) {
            List<String> original = List.of(originallyLiteral ? "description: |" : "items:", "  - item");
            Fixture fixture = fixture("yml", original);
            fixture.engine().setAutoIndentEnabled(false);
            fixture.engine().insertNewline();
            assertEquals(originallyLiteral ? "" : "- ", fixture.model().getLine(2));
            assertTrue(fixture.engine().undo());
            fixture.engine().restoreSelection(new Position(0, original.getFirst().length()), new Position(0, 0));
            fixture.engine().insertInput(originallyLiteral ? "items:" : "description: |");
            fixture.engine().moveCursorTo(new Position(1, original.getLast().length()));

            fixture.engine().insertNewline();

            assertEquals(originallyLiteral ? "- " : "", fixture.model().getLine(2));
            assertTrue(fixture.engine().undo());
            assertTrue(fixture.engine().undo());
            assertEquals(original, fixture.model().snapshotLines());
            assertFalse(fixture.state().isDirty());
            fixture.engine().moveCursorTo(new Position(1, original.getLast().length()));
            fixture.engine().insertNewline();
            assertEquals(originallyLiteral ? "" : "- ", fixture.model().getLine(2));
        }
    }

    @Test
    void unknownBoundedContextFallsBackToAnOrdinaryNewline() {
        assertNewline("yaml", List.of("description: |", "  " + "x".repeat(IndentationContext.MAX_SCAN_CODE_UNITS),
            "  - literal"), true, "  ");
    }

    @Test
    void disablingListsLeavesBothLiteralAndRealSequencesToOrdinaryIndentation() {
        for (String header : List.of("items:", "description: |")) {
            Fixture fixture = fixture("yaml", List.of(header, "  - item"));
            fixture.engine().setAutoListEnabled(false);
            fixture.engine().insertNewline();
            assertEquals("  ", fixture.model().getLine(2));
        }
    }

    private static void assertNewline(String extension, List<String> original, boolean autoIndent, String inserted) {
        assertNewline(extension, original, autoIndent, inserted, TabInsertMode.AUTO);
    }

    private static void assertNewline(String extension, List<String> original, boolean autoIndent, String inserted,
                                      TabInsertMode tabInsertMode) {
        Fixture fixture = fixture(extension, original);
        fixture.engine().setAutoIndentEnabled(autoIndent);
        fixture.engine().setTabInsertMode(tabInsertMode);
        Position before = fixture.state().getCursor();
        List<String> expected = new ArrayList<>(original);
        expected.add(inserted);

        fixture.engine().insertNewline();

        assertEquals(expected, fixture.model().snapshotLines(), original.toString());
        assertEquals(new Position(original.size(), inserted.length()), fixture.state().getCursor());
        assertTrue(fixture.state().isDirty());
        assertTrue(fixture.engine().undo());
        assertEquals(original, fixture.model().snapshotLines());
        assertEquals(before, fixture.state().getCursor());
        assertFalse(fixture.state().isDirty());
        assertFalse(fixture.engine().undo());
        assertTrue(fixture.engine().redo());
        assertEquals(expected, fixture.model().snapshotLines());
    }

    private static Fixture fixture(String extension, List<String> lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        EditorState state = new EditorState();
        state.setCurrentResource(new MemoryDocumentStorage().resource(Path.of("config." + extension).toString()));
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.moveCursorTo(new Position(lines.size() - 1, lines.getLast().length()));
        return new Fixture(model, state, engine);
    }

    private record Fixture(TextModel model, EditorState state, EditorEngine engine) {}
}
