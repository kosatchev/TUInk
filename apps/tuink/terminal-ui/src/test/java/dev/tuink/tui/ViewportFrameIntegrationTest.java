package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;
import static dev.tuink.tui.file.FileTreeTestSupport.awaitLoaded;

import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.jline.presentation.ScreenPresenter;
import dev.tuiframe.memory.RenderMemoryBudget;
import dev.tuiframe.screen.Frame;
import dev.tuiframe.screen.ScrollHint;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.TextProjection;
import dev.tuink.render.EditorLayout;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.render.markdown.MarkdownGeometry;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuink.tui.render.RenderRasterizer;
import dev.tuiframe.explorer.file.FileTreeModel;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.tui.ui.ConfirmBarView;
import dev.tuink.tui.ui.EditorView;
import dev.tuiframe.explorer.ui.FileTreeView;
import dev.tuink.tui.ui.InlinePromptBar;
import dev.tuink.tui.ui.NotificationBar;
import dev.tuink.tui.ui.OpenFileDialogController.OpenDialogLayout;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ViewportFrameIntegrationTest {
    @TempDir Path root;
    private static final int ROWS = 14, COLS = 60;
    private static final Rect PREVIEW = new Rect(6, 2, 44, 10);
    private static final Rect BODY = new Rect(6, 2, 43, 9);

    @Test void previewUsesSharedVerticalAndHorizontalHintsInsideItsBodyOnly() {
        var host = host();
        var output = new StringWriter();
        var controller = host.new RenderFrameController(true, presenter(output, TerminalCapabilities.DEFAULT));
        try (var geometry = geometry()) {
            assertEquals(ScrollHint.none(), preview(controller, geometry, PREVIEW, 0, 0, false, layers()).scrollHint());
            output.getBuffer().setLength(0);
            Frame vertical = preview(controller, geometry, PREVIEW, 1, 0, false, layers());
            assertEquals(new ScrollHint.Vertical(BODY), vertical.scrollHint());
            assertTrue(output.toString().contains("\u001b[3;11r"), "Shared AnsiDiff accepts the body candidate");
            assertTrue(row(vertical, 2).contains("row-1"));
            assertFalse(row(vertical, 1).contains("row-1"), "window chrome stays outside scroll region");
            assertEquals(" ", vertical.cells().get(3, 5).grapheme());
            assertEquals(" ", vertical.cells().get(3, 50).grapheme());
            assertEquals(new ScrollHint.Vertical(BODY), preview(controller, geometry, PREVIEW, 0, 0, false, layers()).scrollHint());
            assertEquals(new ScrollHint.Vertical(BODY), preview(controller, geometry, PREVIEW, 1, 0, false, layers()).scrollHint());
            Frame horizontal = preview(controller, geometry, PREVIEW, 1, 2, false, layers());
            assertEquals(new ScrollHint.Horizontal(BODY, 2), horizontal.scrollHint());
            assertEquals(new ScrollHint.Horizontal(BODY, -2), preview(controller, geometry, PREVIEW, 1, 0, false, layers()).scrollHint());
            assertEquals(new ScrollHint.Horizontal(BODY, 2), preview(controller, geometry, PREVIEW, 1, 2, false, layers()).scrollHint());
            var selected = placement(geometry, PREVIEW, 1, 3, new PreviewSelection.Snapshot(0, 1));
            Frame selection = controller.renderPreviewFrameIfNeeded(composition(selected, false, layers()));
            assertEquals(ScrollHint.none(), selection.scrollHint());
        }
    }

    @Test void layoutChangesModalsAndFullRedrawInvalidatePreviewReuse() {
        var host = host();
        var controller = host.new RenderFrameController(true, presenter(new StringWriter(), TerminalCapabilities.DEFAULT));
        try (var first = geometry(); var reflowed = geometry()) {
            preview(controller, first, PREVIEW, 0, 0, false, layers());
            assertEquals(ScrollHint.none(), preview(controller, reflowed, PREVIEW, 1, 0, false, layers()).scrollHint());
            assertEquals(new ScrollHint.Vertical(BODY), preview(controller, reflowed, PREVIEW, 2, 0, false, layers()).scrollHint());
            var confirmBar = ConfirmBarView.build("Save?", COLS);
            var confirm = confirmBar.place(new Object(), ROWS - 2, new Rect(0, 0, COLS, ROWS));
            var modal = new FrameComposition.Layers(null, null, false, null, confirm,
                FrameComposition.OpenFile.hidden(), null, null, false, false, new Rect(0, ROWS - 2, COLS, 1));
            var promptSurface = new FrameComposition.Surface(surface().topBar(), surface().bottomBar(),
                InlinePromptBar.of(confirmBar.row(), dev.tuink.render.Style.DEFAULT, 1), NotificationBar.hidden());
            var modalFrame = new PreviewFrameComposition(ROWS, COLS, 2,
                placement(reflowed, PREVIEW, 3, 0, PreviewSelection.Snapshot.EMPTY), promptSurface, modal, false);
            assertEquals(ScrollHint.none(), controller.renderPreviewFrameIfNeeded(modalFrame).scrollHint());
            assertEquals(ScrollHint.none(), preview(controller, reflowed, PREVIEW, 4, 0, false, layers()).scrollHint());
            assertEquals(ScrollHint.none(), preview(controller, reflowed, PREVIEW, 5, 0, true, layers()).scrollHint());
            assertEquals(new ScrollHint.Vertical(BODY), preview(controller, reflowed, PREVIEW, 6, 0, false, layers()).scrollHint());
            assertEquals(ScrollHint.none(), preview(controller, reflowed, new Rect(7, 2, 43, 10),
                7, 0, false, layers()).scrollHint());
        }
    }

    @Test void capabilityRefusalStillPresentsTheSamePreviewCellsThroughOrdinaryDiff() {
        var host = host();
        var output = new StringWriter();
        var controller = host.new RenderFrameController(false, presenter(output, TerminalCapabilities.MINIMAL));
        try (var geometry = geometry()) {
            preview(controller, geometry, PREVIEW, 0, 0, false, layers());
            output.getBuffer().setLength(0);
            Frame current = preview(controller, geometry, PREVIEW, 1, 0, false, layers());
            assertEquals(new ScrollHint.Vertical(BODY), current.scrollHint());
            assertFalse(output.toString().contains("\u001b[3;11r"));
            assertTrue(row(current, 2).contains("row-1"));
        }
    }

    @Test void failedPresentationInvalidatesBothPhysicalAndViewportHistory() {
        var host = host();
        var failure = new AssertionError("synthetic terminal failure");
        var output = new StringWriter() {
            boolean fail;
            @Override public void write(String value, int offset, int length) {
                if (fail) throw failure;
                super.write(value, offset, length);
            }
        };
        var controller = host.new RenderFrameController(true, presenter(output, TerminalCapabilities.DEFAULT));
        try (var geometry = geometry()) {
            preview(controller, geometry, PREVIEW, 0, 0, false, layers());
            output.fail = true;
            assertSame(failure, assertThrows(AssertionError.class,
                () -> preview(controller, geometry, PREVIEW, 1, 0, false, layers())));
            output.fail = false;
            output.getBuffer().setLength(0);
            Frame retry = preview(controller, geometry, PREVIEW, 2, 0, false, layers());
            assertEquals(ScrollHint.none(), retry.scrollHint());
            assertTrue(output.toString().startsWith("\u001b[H"));
        }
    }

    @Test void sourceHistoryUsesPrimitiveProducerIdentityAndVisualTopWithoutRetainingAPreviousPlan() {
        var host = host();
        var output = new StringWriter();
        var text = new SimpleLineBuffer();
        text.setLine(0, "row-0 " + "abcdef".repeat(20));
        for (int index = 1; index < 40; index++) text.insertLine(index, "row-" + index + " " + "abcdef".repeat(20));
        var model = new TextModel(text);
        var state = new EditorState();
        var layout = new TerminalEditorTextLayout(model);
        var builder = RenderPlanBuilder.standalone(ignored -> List.of(), layout);
        var otherBuilder = RenderPlanBuilder.standalone(ignored -> List.of(), layout);
        assertNotEquals(builder.renderIdentity(), otherBuilder.renderIdentity());
        try (var rasterizer = new RenderRasterizer()) {
            var controller = host.new RenderFrameController(rasterizer, new EditorView(), true,
                presenter(output, TerminalCapabilities.DEFAULT));
            var initial = controller.renderFrameIfNeeded(source(builder, model, state, layout));
            String region = "\u001b[2;" + (1 + initial.plan().contentRows()) + "r";
            output.getBuffer().setLength(0);
            state.setTopLine(1);
            controller.renderFrameIfNeeded(source(builder, model, state, layout));
            assertTrue(output.toString().contains(region));
            output.getBuffer().setLength(0);
            state.setTopLine(0);
            controller.renderFrameIfNeeded(source(builder, model, state, layout));
            assertTrue(output.toString().contains(region));
            output.getBuffer().setLength(0);
            state.setTopLine(1);
            controller.renderFrameIfNeeded(source(builder, model, state, layout));
            assertTrue(output.toString().contains(region));
            output.getBuffer().setLength(0);
            state.setLeftColumn(2); state.setLeftVisualColumn(2);
            controller.renderFrameIfNeeded(source(builder, model, state, layout));
            assertTrue(output.toString().contains("\u001b[2P"));
            output.getBuffer().setLength(0);
            state.setLeftColumn(0); state.setLeftVisualColumn(0);
            controller.renderFrameIfNeeded(source(builder, model, state, layout));
            assertTrue(output.toString().contains("\u001b[2@"));
            output.getBuffer().setLength(0);
            state.setLeftColumn(2); state.setLeftVisualColumn(2);
            controller.renderFrameIfNeeded(source(builder, model, state, layout));
            assertTrue(output.toString().contains("\u001b[2P"));
            output.getBuffer().setLength(0);
            state.setSelectionAnchor(new Position(0, 0)); state.setCursor(new Position(0, 3));
            state.setLeftColumn(4); state.setLeftVisualColumn(4);
            controller.renderFrameIfNeeded(source(builder, model, state, layout));
            assertFalse(output.toString().contains("\u001b[2P"));
            state.setSelectionAnchor(null);
            output.getBuffer().setLength(0);
            state.setTopLine(2);
            controller.renderFrameIfNeeded(source(otherBuilder, model, state, layout));
            assertFalse(output.toString().contains(region));
            try (var geometry = geometry()) {
                assertEquals(ScrollHint.none(), preview(controller, geometry, PREVIEW, 0, 0, false, layers()).scrollHint());
                assertEquals(new ScrollHint.Vertical(BODY), preview(controller, geometry, PREVIEW, 1, 0, false, layers()).scrollHint());
            }
            output.getBuffer().setLength(0);
            state.setTopLine(3);
            controller.renderFrameIfNeeded(source(otherBuilder, model, state, layout));
            assertFalse(output.toString().contains(region));
        }
    }

    @Test void pickerUsesItsOwnImmutableSurfaceAndInvalidatesWhenTheDialogChanges() throws Exception {
        for (int index = 0; index < 30; index++) Files.createFile(root.resolve("file-" + index + ".txt"));
        var host = host();
        var controller = host.new RenderFrameController(true, presenter(new StringWriter(), TerminalCapabilities.DEFAULT));
        try (var model = new FileTreeModel(root, false, false); var geometry = geometry()) {
            var snapshot = awaitLoaded(model);
            var view = FileTreeView.fromSnapshot(snapshot, false, false);
            var bounds = new Rect(2, 3, 56, 8);
            var clip = new Rect(0, 0, COLS, ROWS);
            var initial = view.place(bounds, new FileTreeView.State(null,
                new dev.tuiframe.widget.TreeView.State(0, 0, 0), snapshot.revision(), null, 0L), clip, true);
            var dialog = new OpenDialogLayout(1, 1, 58, 12, 3, 9);
            preview(controller, geometry, PREVIEW, 0, 0, false, picker(root.toString(), dialog, initial));
            var next = view.place(bounds, new FileTreeView.State(initial.state().selectedId(),
                new dev.tuiframe.widget.TreeView.State(0, 1, 0), snapshot.revision(), null, 0L), clip, true);
            assertEquals(1, next.state().widgetState().firstVisibleIndex());
            Frame scrolled = preview(controller, geometry, PREVIEW, 0, 0, false, picker(root.toString(), dialog, next));
            assertEquals(new ScrollHint.Vertical(next.contentBounds()), scrolled.scrollHint());
            var changed = view.place(bounds, new FileTreeView.State(next.state().selectedId(),
                new dev.tuiframe.widget.TreeView.State(0, 2, 0), snapshot.revision(), null, 0L), clip, true);
            Frame differentRoot = preview(controller, geometry, PREVIEW, 0, 0, false, picker("other root", dialog, changed));
            assertEquals(ScrollHint.none(), differentRoot.scrollHint());
            assertEquals(ScrollHint.none(), preview(controller, geometry, PREVIEW, 1, 0, false, layers()).scrollHint());
            assertEquals(new ScrollHint.Vertical(BODY), preview(controller, geometry, PREVIEW, 2, 0, false, layers()).scrollHint());
        }
    }

    private static FrameComposition.Layers picker(String root, OpenDialogLayout layout, FileTreeView.Placement tree) {
        var dialog = OpenFileDialogView.place(TerminalEditorApp.OPEN_FILE_SCOPE_ID, layout, tree, false,
            new Rect(0, 0, COLS, ROWS));
        return new FrameComposition.Layers(null, null, false, null, null,
            new FrameComposition.OpenFile(root, dialog, OpenFileInputRouter.Focus.LIST), null, null, false, false, dialog.frame().bounds());
    }

    private static FrameComposition source(RenderPlanBuilder builder, TextModel model, EditorState state,
            TerminalEditorTextLayout textLayout) {
        var layout = EditorLayout.resolve(model, textLayout, state, ROWS, COLS, 1, 0, false, false, 4);
        return new FrameComposition(new FrameComposition.Document(builder, model, state, layout),
            surface(), new FrameComposition.Search(false, "", SearchOptions.DEFAULT, false, null, false),
            layers(), false, false, false);
    }

    private static Frame preview(TerminalEditorApp.RenderFrameController controller, MarkdownGeometry geometry,
            Rect bounds, int top, int left, boolean fullRedraw, FrameComposition.Layers layers) {
        return controller.renderPreviewFrameIfNeeded(composition(placement(geometry, bounds, top, left,
            PreviewSelection.Snapshot.EMPTY), fullRedraw, layers));
    }
    private static PreviewFrameComposition composition(MarkdownPreviewState.Placement placement,
            boolean fullRedraw, FrameComposition.Layers layers) {
        return new PreviewFrameComposition(ROWS, COLS, 2, placement, surface(), layers, fullRedraw);
    }
    private static MarkdownPreviewState.Placement placement(MarkdownGeometry geometry, Rect bounds,
            int top, int left, PreviewSelection.Snapshot selection) {
        return new MarkdownPreviewState.Placement(bounds, geometry.result(), top, left, false, "", selection,
            List.of(), -1, "", false, null, null, geometry,
            MarkdownPreviewState.Placement.Focus.CONTENT, MarkdownPreviewState.SearchFailure.NONE);
    }
    private static MarkdownGeometry geometry() {
        var lines = new ArrayList<MarkdownLayout.Line>();
        for (int index = 0; index < 40; index++) {
            String text = "row-" + index + " " + "abcdef".repeat(20);
            lines.add(new MarkdownLayout.Line(text, List.of(), text, null, "text"));
        }
        var document = new MarkdownDocument(new DocumentId("scroll-preview"), 1, List.of(), List.of(),
            new TextProjection("", List.of()));
        return new MarkdownGeometry(new MarkdownLayout.Result(document, lines, 43, 130), RenderMemoryBudget.bounded(1L << 20));
    }
    private static TerminalEditorApp host() {
        var host = new TerminalEditorApp();
        host.styles = UiStyles.forTheme(UiStyles.Theme.CYAN);
        return host;
    }
    private static ScreenPresenter presenter(StringWriter output, TerminalCapabilities capabilities) {
        return new ScreenPresenter(new PrintWriter(output), capabilities);
    }
    private static FrameComposition.Surface surface() {
        return new FrameComposition.Surface(new TopMenuView.TopBar(" File | document ", 0), " status",
            InlinePromptBar.hidden(), NotificationBar.hidden());
    }
    private static FrameComposition.Layers layers() {
        return new FrameComposition.Layers(null, null, false, null, null,
            FrameComposition.OpenFile.hidden(), null, null, false, false, new Rect(0, 0, 0, 0));
    }
    private static String row(Frame frame, int row) {
        var result = new StringBuilder();
        for (int column = 0; column < frame.cells().cols(); column++) {
            var cell = frame.cells().get(row, column);
            if (!cell.continuation()) result.append(cell.grapheme());
        }
        return result.toString();
    }
}
