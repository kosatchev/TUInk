package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorEngineMarkdownContextCacheTest {
    @Test
    void repeatedEnterAndDisplayTabWidthChangesDoNotReadTheEarlierPrefixAgain() {
        CountingModel model = new CountingModel();
        List<String> lines = new ArrayList<>(Collections.nCopies(20_000, "body"));
        lines.set(0, "```sh");
        lines.set(19_999, "- literal code");
        model.replaceLines(lines);
        EditorEngine engine = engine(model);
        toEnd(engine);

        engine.insertNewline();
        assertEquals("", model.getLine(20_000));
        model.earlierPrefixReads = 0;
        engine.setTabWidth(2);

        engine.insertNewline();

        assertEquals("", model.getLine(20_001));
        assertEquals(0, model.earlierPrefixReads, "Only the changed tail should be reparsed after Enter");
    }

    @Test
    void editsThroughAnotherViewInvalidateTheCachedOpeningFence() {
        TextModel model = model(List.of("```sh", "- item"));
        EditorEngine first = engine(model);
        EditorEngine second = first.document().attach(LogicalEditorTextLayout::new, new EditorClipboard()).engine();
        first.setAutoIndentEnabled(false);
        toEnd(first);
        first.insertNewline();
        assertTrue(first.undo());

        replaceOpener(second, "ordinary text");
        toEnd(first);
        first.insertNewline();

        assertEquals(List.of("ordinary text", "- item", "- "), model.snapshotLines());
    }

    @Test
    void changingDisplayTabWidthDoesNotReclassifyMarkdownIndentation() {
        for (int tabWidth : List.of(2, 4)) {
            TextModel model = model(List.of("\t- parent", "\t\t- child"));
            EditorEngine engine = engine(model);
            engine.setTabWidth(tabWidth);
            toEnd(engine);
            engine.insertNewline();
            assertEquals("", model.getLine(2), "Markdown tabs retain four-column grammar indentation");

            engine.setTabWidth(tabWidth == 2 ? 4 : 2);
            engine.moveCursorTo(new Position(1, model.getLine(1).length()));
            engine.insertNewline();

            assertEquals(List.of("\t- parent", "\t\t- child", "", ""), model.snapshotLines());
        }
    }

    @Test
    void undoAndRedoOfEarlierEditsUpdateTheFenceContext() {
        for (boolean redo : List.of(false, true)) {
            TextModel model = model(List.of("```sh", "- item"));
            EditorEngine engine = engine(model);
            toEnd(engine);
            engine.insertNewline();
            assertTrue(engine.undo());
            replaceOpener(engine, "ordinary text");
            toEnd(engine);
            engine.insertNewline();
            assertEquals("- ", model.getLine(2));
            assertTrue(engine.undo());
            assertTrue(engine.undo());
            if (redo) {
                assertTrue(engine.redo());
            }

            toEnd(engine);
            engine.insertNewline();

            assertEquals(redo ? "- " : "", model.getLine(2), "redo=" + redo);
        }
    }

    @Test
    void openingAnotherDocumentDiscardsThePreviousFenceState(@TempDir Path directory) throws Exception {
        TextModel model = model(List.of("```sh", "- item"));
        EditorEngine engine = engine(model);
        toEnd(engine);
        engine.insertNewline();
        Path other = directory.resolve("other.md");
        Files.writeString(other, "ordinary text\n- item");

        engine.openExisting(JvmDocumentStorage.resource(other));
        toEnd(engine);
        engine.insertNewline();

        assertEquals(List.of("ordinary text", "- item", "- "), model.snapshotLines());
    }

    @Test
    void standaloneIndentedCodeDoesNotGainListMarkersWithOrWithoutAutoIndent() {
        for (boolean indent : List.of(false, true)) {
            for (String code : List.of("    - literal code", "\t1. literal code", "    - [ ] literal code")) {
                TextModel model = model(List.of(code));
                EditorEngine engine = engine(model);
                engine.setAutoIndentEnabled(indent);
                toEnd(engine);

                engine.insertNewline();

                String expected = !indent ? "" : code.startsWith("\t") ? "\t" : "    ";
                assertEquals(List.of(code, expected), model.snapshotLines());
                assertTrue(engine.undo());
                assertEquals(List.of(code), model.snapshotLines());
                assertTrue(engine.redo());
                assertEquals(List.of(code, expected), model.snapshotLines());
            }
        }
    }

    private static TextModel model(List<String> lines) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(lines);
        return model;
    }

    private static EditorEngine engine(TextModel model) {
        EditorState state = new EditorState();
        state.setCurrentResource(new MemoryDocumentStorage().resource(Path.of("notes.md").toString()));
        EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
        engine.setGutterDiffMode(GutterDiffMode.DISABLED);
        engine.setAutoIndentEnabled(false);
        return engine;
    }

    private static void toEnd(EditorEngine engine) {
        TextModel model = engine.model();
        int last = model.lineCount() - 1;
        engine.moveCursorTo(new Position(last, model.getLine(last).length()));
    }

    private static void replaceOpener(EditorEngine engine, String replacement) {
        engine.restoreSelection(new Position(0, engine.model().getLine(0).length()), new Position(0, 0));
        engine.pasteText(replacement);
    }

    private static final class CountingModel extends TextModel {
        int earlierPrefixReads;

        CountingModel() {
            super(new SimpleLineBuffer());
        }

        @Override
        public String getLine(int line) {
            if (line < 19_000) {
                earlierPrefixReads++;
            }
            return super.getLine(line);
        }
    }
}
