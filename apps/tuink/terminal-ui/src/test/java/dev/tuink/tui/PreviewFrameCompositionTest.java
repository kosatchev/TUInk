package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import static dev.tuink.tui.PreviewSelectionTest.*;

import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.jline.presentation.ScreenPresenter;
import dev.tuiframe.screen.Frame;
import dev.tuiframe.screen.ScrollHint;
import dev.tuiframe.widget.TabBar;
import dev.tuiframe.widget.TextField;
import dev.tuink.tui.render.EditorStyleAdapter;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.tui.ui.ConfirmBarView;
import dev.tuink.tui.flow.ConfirmationShortcuts;
import dev.tuink.tui.ui.InlinePromptBar;
import dev.tuink.tui.ui.NotificationBar;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuink.tui.ui.TabBarView;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Preview rendering is deliberately constructed without a source session, editor view or rasterizer. */
class PreviewFrameCompositionTest {
    @Test void standalonePreviewPaintsChromeTabsNoticeAndReadonlyCaretWithoutAnEditor() {
        var host = host();
        int rows = 12, cols = 80;
        var tabs = TabBarView.build(List.of(
            new TabBarView.TabItem(Path.of("README.md"), false, false, false, false, "Source"),
            new TabBarView.TabItem(Path.of("README.md"), false, false, false, false, "Preview")),
            1, TabBar.State.initial()).place(new Rect(0, 1, cols, 1), new Rect(0, 0, cols, rows));
        var layers = new FrameComposition.Layers(tabs, null, false, null, null,
            FrameComposition.OpenFile.hidden(), null, null, false, false, new Rect(0, 0, 0, 0));
        var surface = surface(InlinePromptBar.hidden(), NotificationBar.of("Snapshot retained", null));
        var preview = preview(new Rect(0, 2, cols, rows - 4));
        StringWriter output = new StringWriter();
        Frame frame = host.new RenderFrameController(true, presenter(output))
            .renderPreviewFrameIfNeeded(new PreviewFrameComposition(rows, cols, 2, preview, surface, layers, true));

        assertTrue(row(frame, 0).contains("Preview READ"));
        assertTrue(row(frame, 1).contains("Source"));
        assertTrue(row(frame, 1).contains("Preview"));
        assertTrue(row(frame, 2).startsWith("alpha"));
        assertFalse(row(frame, 2).contains("Ctrl+P"));
        assertTrue(row(frame, rows - 2).startsWith("Snapshot retained"));
        assertTrue(row(frame, rows - 1).startsWith(" Preview · read-only"));
        assertEquals(new Point(1, 2), frame.cursor().position());
        assertTrue(frame.cursor().visible());
        assertEquals(ScrollHint.none(), frame.scrollHint());
        assertFalse(output.toString().isEmpty());
    }

    @Test void saveAsUsesTheSameFieldPainterAndCursorOwnershipOverStandalonePreview() {
        var host = host();
        int rows = 10, cols = 60;
        TextField field = new TextField();
        field.set("界-draft.md");
        var bar = SaveAsBarView.build(field, cols);
        var placed = bar.place(new Object(), rows - 2, new Rect(0, 1, cols, rows - 1));
        placed.activate(host.saveAsFocusManager());
        var prompt = InlinePromptBar.of(bar.row(), EditorStyleAdapter.toEditor(host.styles.promptAlert()), 1);
        var layers = new FrameComposition.Layers(null, null, true, placed, null,
            FrameComposition.OpenFile.hidden(), null, null, false, false, new Rect(0, rows - 2, cols, 1));
        Frame frame = render(host, rows, cols, preview(new Rect(0, 1, cols, rows - 3)), surface(prompt, NotificationBar.hidden()), layers);

        assertTrue(row(frame, rows - 2).startsWith("Save As: 界-draft.md"));
        assertEquals("界", frame.cells().get(rows - 2, placed.fieldBounds().x()).grapheme());
        assertTrue(frame.cells().get(rows - 2, placed.fieldBounds().x() + 1).continuation());
        assertEquals(new Point(placed.fieldBounds().x() + CellText.width(field.text()), rows - 2),
            frame.cursor().position());
        assertEquals(placed.cursorPosition(host.saveAsFocusManager()).orElseThrow(), frame.cursor().position());
        assertTrue(frame.cursor().visible());
        assertTrue(row(frame, 1).startsWith("alpha"));
        assertEquals("界-draft.md", field.text());
    }

    @Test void confirmationUsesSharedButtonsAttentionAndHidesThePreviewCaret() {
        var host = host();
        int rows = 14, cols = 60;
        var bar = ConfirmBarView.responsive("Save snapshot?", cols, rows - 2,
            "Save", "Discard", true, "Cancel", ConfirmationShortcuts.SAVE);
        var prompt = InlinePromptBar.of(bar.row(), EditorStyleAdapter.toEditor(host.styles.promptAlert()), 1);
        var placed = bar.place(new Object(), rows - 1 - prompt.height(), new Rect(0, 1, cols, rows - 1));
        placed.activate(host.confirmFocusManager());
        var layers = new FrameComposition.Layers(null, null, false, null, placed,
            FrameComposition.OpenFile.hidden(), null, null, true, false,
            new Rect(0, rows - 1 - prompt.height(), cols, prompt.height()));
        Frame frame = render(host, rows, cols, preview(new Rect(0, 1, cols, rows - 2 - prompt.height())),
            surface(prompt, NotificationBar.hidden()), layers);

        assertTrue(row(frame, rows - 2).contains("[Save]"));
        assertTrue(row(frame, rows - 2).contains("[Cancel]"));
        assertFalse(frame.cursor().visible());
        assertTrue(row(frame, 1).startsWith("alpha"));
        assertNotEquals(host.styles.renderBody(), frame.cells().get(rows - 2, 0).style());
    }

    @Test void queryFieldOwnsTheStandalonePreviewCursorAndConflictingModalsAreRejected() {
        var host = host();
        int rows = 8, cols = 50;
        var bounds = new Rect(0, 1, cols, rows - 2);
        TextField query = new TextField();
        query.set("needle");
        var input = query.layout(new Rect(7, 1, 25, 1));
        var base = preview(bounds);
        var preview = new MarkdownPreviewState.Placement(bounds, base.result(), 0, 0, false, "",
            PreviewSelection.Snapshot.EMPTY, List.of(), -1, "needle", true, null, input, null,
            MarkdownPreviewState.Placement.Focus.QUERY, MarkdownPreviewState.SearchFailure.NONE);
        Frame frame = render(host, rows, cols, preview, surface(InlinePromptBar.hidden(), NotificationBar.hidden()), emptyLayers());
        assertTrue(row(frame, 1).contains("needle"));
        assertEquals(new Point(13, 1), frame.cursor().position());
        assertTrue(frame.cursor().visible());

        var confirm = ConfirmBarView.build("Confirm?", cols).place(new Object(), rows - 2, new Rect(0, 0, cols, rows));
        var invalid = new FrameComposition.Layers(null, null, true, null, confirm,
            FrameComposition.OpenFile.hidden(), null, null, false, false, new Rect(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PreviewFrameComposition(rows, cols, 1, preview,
            surface(InlinePromptBar.hidden(), NotificationBar.hidden()), invalid, false));
    }

    @Test void standalonePreviewRejectsStaleModalBoundsInsteadOfSilentlyRoutingElsewhere() {
        int rows = 10, cols = 60;
        var field = new TextField();
        var bar = SaveAsBarView.build(field, cols);
        var placed = bar.place(new Object(), rows - 2, new Rect(0, 1, cols, rows - 1));
        var prompt = InlinePromptBar.of(bar.row(), dev.tuink.render.Style.DEFAULT, 1);
        var stale = new FrameComposition.Layers(null, null, true, placed, null,
            FrameComposition.OpenFile.hidden(), null, null, false, false, new Rect(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PreviewFrameComposition(rows, cols, 1,
            preview(new Rect(0, 1, cols, rows - 3)), surface(prompt, NotificationBar.hidden()), stale, false));
    }

    @Test void multilineInlineGeometryClipsToTheActualWindowBodyAtSmallSizes() {
        assertEquals(new Rect(0, 2, 20, 3), FrameComposition.Layers.prepareBlockingBounds(
            6, 20, 2, 8, true, FrameComposition.OpenFile.hidden(), null, null));
        assertEquals(new Rect(0, 0, 0, 0), FrameComposition.Layers.prepareBlockingBounds(
            6, 20, 2, 8, false, FrameComposition.OpenFile.hidden(), null, null));
    }

    private static TerminalEditorApp host() {
        var host = new TerminalEditorApp();
        host.styles = UiStyles.forTheme(UiStyles.Theme.CYAN);
        return host;
    }
    private static Frame render(TerminalEditorApp host, int rows, int cols, MarkdownPreviewState.Placement preview,
        FrameComposition.Surface surface, FrameComposition.Layers layers) {
        return host.new RenderFrameController(true, presenter(new StringWriter()))
            .renderPreviewFrameIfNeeded(new PreviewFrameComposition(rows, cols, 1, preview, surface, layers, false));
    }
    private static ScreenPresenter presenter(StringWriter output) {
        return new ScreenPresenter(new PrintWriter(output), TerminalCapabilities.DEFAULT);
    }
    private static FrameComposition.Surface surface(InlinePromptBar prompt, NotificationBar notice) {
        return new FrameComposition.Surface(new TopMenuView.TopBar(" File | README.md · Preview READ ", 0),
            " Preview · read-only", prompt, notice);
    }
    private static FrameComposition.Layers emptyLayers() {
        return new FrameComposition.Layers(null, null, false, null, null,
            FrameComposition.OpenFile.hidden(), null, null, false, false, new Rect(0, 0, 0, 0));
    }
    private static MarkdownPreviewState.Placement preview(Rect bounds) {
        return new MarkdownPreviewState.Placement(bounds, result("alpha", line("alpha", 0, 5, 0, 5)),
            0, 0, false, "", PreviewSelection.Snapshot.EMPTY, List.of(), -1, "", false,
            new PreviewSelection.VisualPosition(0, 1), null, null,
            MarkdownPreviewState.Placement.Focus.CONTENT, MarkdownPreviewState.SearchFailure.NONE);
    }
    private static String row(Frame frame, int row) {
        StringBuilder text = new StringBuilder();
        for (int col = 0; col < frame.cells().cols(); col++) {
            var cell = frame.cells().get(row, col);
            if (!cell.continuation()) text.append(cell.grapheme());
        }
        return text.toString();
    }
}
