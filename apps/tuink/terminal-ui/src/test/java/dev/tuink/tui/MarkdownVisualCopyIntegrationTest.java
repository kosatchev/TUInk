package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class MarkdownVisualCopyIntegrationTest {
    @TempDir Path directory;
    private static final String PARAGRAPH = "alpha beta gamma delta ".repeat(12) + "paragraph-end";
    private static final String WIDE_CODE = "wide_code_" + "x".repeat(150);

    @ParameterizedTest
    @EnumSource(value = ActiveSurface.Kind.class, names = {"LIVE_PREVIEW", "FROZEN_PREVIEW"})
    void defaultCopyStaysSemanticAndTogglingAfterSelectionChangesOnlyTheCopiedRepresentation(ActiveSurface.Kind kind) throws Exception {
        try (var window = window("toggle-" + kind, kind, content())) {
            assertFalse(window.ui.markdownVisualCopySetting);
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            var selection = window.preview().selection.snapshot();
            var result = window.preview().result;
            window.command(TuinkCommand.EDIT_COPY);
            String semantic = window.host.clipboard.text();
            assertTrue(semantic.contains("\\frac{a}{b}"));
            assertTrue(semantic.contains("flowchart LR"));
            assertFalse(semantic.contains("┌"));
            window.command(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_VISUAL_COPY);
            assertTrue(window.ui.markdownVisualCopySetting);
            assertEquals(selection, window.preview().selection.snapshot());
            assertSame(result, window.preview().result, "copy preference does not reparse or replace the prepared layout");
            window.command(TuinkCommand.EDIT_COPY);
            assertVisual(window.host.clipboard.text());
            assertEquals(selection, window.preview().selection.snapshot());
            window.command(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_VISUAL_COPY);
            window.command(TuinkCommand.EDIT_COPY);
            assertEquals(semantic, window.host.clipboard.text());
            assertEquals(String.join("\n", content()), Files.readString(window.document));
        }
    }

    @ParameterizedTest
    @EnumSource(value = ActiveSurface.Kind.class, names = {"LIVE_PREVIEW", "FROZEN_PREVIEW"})
    void copyUsesTheWholeSelectionBeyondTheViewportAndUnwrapsOnlySoftLines(ActiveSurface.Kind kind) throws Exception {
        try (var window = window("geometry-" + kind, kind, content())) {
            window.ui.markdownVisualCopySetting = true;
            window.resize(12, 34);
            window.await(() -> window.preview().ready());
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            var selection = window.preview().selection.snapshot();
            window.preview().scroll(200, 25);
            window.frame();
            assertTrue(window.preview().top > 0);
            assertTrue(window.preview().left > 0);
            int top = window.preview().top, left = window.preview().left;
            window.command(TuinkCommand.EDIT_COPY);
            String narrow = window.host.clipboard.text();
            assertVisual(narrow);
            assertTrue(narrow.contains(PARAGRAPH), "screen wrapping must not add line breaks to a paragraph");
            assertTrue(narrow.contains("hard first\nhard second"), "an explicit Markdown hard break remains a line break");
            assertTrue(narrow.contains(WIDE_CODE), "wide code copies beyond the visible columns");
            assertTrue(narrow.contains("OffscreenTail"), "copy includes selected rows outside the viewport");
            assertEquals(top, window.preview().top);
            assertEquals(left, window.preview().left);
            window.resize(30, 90);
            window.await(() -> window.preview().ready());
            assertEquals(selection, window.preview().selection.snapshot());
            window.command(TuinkCommand.EDIT_COPY);
            assertEquals(narrow, window.host.clipboard.text(), "soft wrapping alone does not alter copied text");
        }
    }

    @ParameterizedTest
    @EnumSource(value = ActiveSurface.Kind.class, names = {"LIVE_PREVIEW", "FROZEN_PREVIEW"})
    void explicitSourceAndTableTsvCommandsRetainTheirOwnFormats(ActiveSurface.Kind kind) throws Exception {
        try (var window = window("explicit-" + kind, kind, content())) {
            window.ui.markdownVisualCopySetting = true;
            var preview = window.preview();
            String text = preview.result.document().projection().text();
            int formula = text.indexOf("\\frac{a}{b}");
            assertTrue(formula >= 0);
            preview.selection.selectRange(text, formula, formula);
            window.command(TuinkCommand.VIEW_PREVIEW_COPY_SOURCE);
            assertEquals("\\frac{a}{b}", window.host.clipboard.text());
            int table = text.indexOf("left");
            assertTrue(table >= 0);
            preview.selection.selectRange(text, table, table);
            window.command(TuinkCommand.VIEW_PREVIEW_COPY_TABLE_TSV);
            assertEquals("CellA\tCellB\nleft\tright", window.host.clipboard.text());
        }
    }

    @ParameterizedTest
    @CsvSource({"SOURCE,FIND", "LIVE_PREVIEW,FIND", "FROZEN_PREVIEW,FIND",
        "SOURCE,SAVE_AS", "LIVE_PREVIEW,SAVE_AS", "FROZEN_PREVIEW,SAVE_AS"})
    void ordinaryFieldCopyRemainsLiteralEvenInsidePreview(ActiveSurface.Kind kind, String owner) throws Exception {
        try (var window = window("field-" + kind + "-" + owner, kind, content())) {
            window.ui.markdownVisualCopySetting = true;
            window.command(owner.equals("FIND") ? TuinkCommand.SEARCH_OPEN : TuinkCommand.DOCUMENT_SAVE_AS);
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            window.event(new TextInput("literal \\frac{a}{b}"));
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            window.command(TuinkCommand.EDIT_COPY);
            assertEquals("literal \\frac{a}{b}", window.host.clipboard.text());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ActiveSurface.Kind.class, names = {"LIVE_PREVIEW", "FROZEN_PREVIEW"})
    void rightClickCopiesDisplayedTextAndClearsSelectionWithoutMakingPreviewEditable(ActiveSurface.Kind kind) throws Exception {
        try (var window = window("right-" + kind, kind, content())) {
            window.ui.markdownVisualCopySetting = true;
            window.ui.rightClickClipboardSetting = true;
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            var caret = window.preview().selection.caret();
            right(window);
            assertVisual(window.host.clipboard.text());
            assertFalse(window.preview().selection.snapshot().selected());
            assertEquals(caret, window.preview().selection.caret());
            window.host.clipboard.setText("cannot paste");
            right(window);
            assertEquals("cannot paste", window.host.clipboard.text());
            assertEquals(String.join("\n", content()), Files.readString(window.document));
        }
    }

    @Test void thePreferenceDoesNotConvertSourceMarkdownOrChangeItsRevisionOrUndoHistory() throws Exception {
        try (var window = window("source", ActiveSurface.Kind.SOURCE, content())) {
            window.command(TuinkCommand.SETTINGS_TOGGLE_MARKDOWN_VISUAL_COPY);
            window.command(TuinkCommand.EDIT_SELECT_ALL);
            var source = window.active();
            long revision = source.tabSession().model().revision();
            var caret = source.state().getCursor();
            window.command(TuinkCommand.EDIT_COPY);
            assertEquals(String.join("\n", content()), window.host.clipboard.text());
            assertEquals(revision, source.tabSession().model().revision());
            assertEquals(caret, source.state().getCursor());
            assertFalse(source.state().isDirty());
            assertFalse(source.tabSession().engine().undo());
        }
    }

    private EditorWindowFixture window(String name, ActiveSurface.Kind kind, List<String> lines) throws Exception {
        var window = new EditorWindowFixture(directory.resolve(name), "document.md", false, lines);
        if (kind == ActiveSurface.Kind.SOURCE) return window;
        window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
        window.await(() -> window.preview().ready());
        if (kind == ActiveSurface.Kind.FROZEN_PREVIEW) {
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.command(TuinkCommand.TAB_CLOSE);
            window.await(() -> window.selected() instanceof PreviewTab preview
                && preview.source() instanceof PreviewTab.ClosedSnapshot && window.preview().ready());
        }
        assertEquals(kind, window.controller.activeSurface().kind());
        return window;
    }
    private static List<String> content() {
        var lines = new ArrayList<>(List.of("# Visual copy", "", PARAGRAPH, "", "hard first  ", "hard second", "",
            "> Quote token", "", "$$", "\\frac{a}{b}", "$$", "", "```text", WIDE_CODE, "```", "",
            "```mermaid", "flowchart LR", "A[Left] --> B[Right]", "```", "",
            "| CellA | CellB |", "|---|---|", "| left | right |", ""));
        for (int row = 0; row < 25; row++) { lines.add("extra paragraph " + row); lines.add(""); }
        lines.add("OffscreenTail");
        return List.copyOf(lines);
    }
    private static void assertVisual(String value) {
        assertTrue(value.contains("┌") && value.contains("→"), value);
        assertTrue(value.contains("─"), value);
        assertTrue(value.lines().anyMatch(line -> line.strip().equals("a")), value);
        assertTrue(value.lines().anyMatch(line -> line.strip().equals("b")), value);
        assertTrue(value.contains("│ Quote token"), value);
        assertFalse(value.contains("\\frac"), value);
        assertFalse(value.contains("flowchart LR"), value);
    }
    private static void right(EditorWindowFixture window) {
        int row = window.preview().bounds.y() + 2, column = window.preview().bounds.x() + 4;
        window.event(new dev.tuiframe.input.PointerEvent(row, column, PointerEvent.Kind.PRESS, PointerEvent.Button.RIGHT, Set.of()));
        window.event(new dev.tuiframe.input.PointerEvent(row, column, PointerEvent.Kind.RELEASE, PointerEvent.Button.RIGHT, Set.of()));
    }
}
