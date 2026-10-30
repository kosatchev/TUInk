package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorState;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.render.EditorLayout;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.Style;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.file.FileTreeModel;
import dev.tuink.tui.flow.UiModalState;
import dev.tuink.tui.render.RenderRasterizer;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.tui.ui.ConfirmBarView;
import dev.tuink.tui.ui.FileTreeView;
import dev.tuink.tui.ui.HelpPage;
import dev.tuink.tui.ui.InlinePromptBar;
import dev.tuink.tui.ui.NotificationBar;
import dev.tuink.tui.ui.OpenFileDialogController;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.widget.DialogAttention;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.TextField;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ModalAttentionIntegrationTest {
    private static final CellStyle BACKGROUND = new CellStyle(CellColor.WHITE, CellColor.BLACK, false, false);
    private static final CellStyle WARNING = UiStyles.forTheme(UiStyles.Theme.CYAN).notifyWarning();
    private static final HelpPage HELP = new HelpPage("Help 中🦀", List.of("Document shortcuts", "Wide text 中🦀", "Close with Escape"));

    @Test
    void saveAsAndConfirmationHighlightTheWholePromptAndKeepDocumentAndWideTextIntact() {
        for (boolean saveAs : List.of(true, false)) {
            TerminalEditorApp app = new TerminalEditorApp();
            FrameComposition composition = promptFrame(12, 60, saveAs, true);
            CellBuffer buffer = paintPrompt(app, composition);
            Cell[][] before = snapshot(buffer);
            int height = composition.surface().inlinePrompt().height();
            Rect expected = new Rect(0, 12 - 1 - height, 60, height);

            assertEquals(expected, app.blockingSurface(composition));
            assertFalse(composition.allowsScrollHints());
            app.paintBlockingAttention(buffer, composition, true);

            assertAttention(before, buffer, expected, false, WARNING);
            assertTrue(hasWideGlyph(before, expected), "the real prompt must exercise wide-cell preservation");
        }
    }

    @Test
    void helpAndOpenFileAttentionUseTheActuallyPaintedDialogFrameAndLeaveItsInteriorUntouched() throws Exception {
        for (boolean help : List.of(true, false)) {
            TerminalEditorApp app = new TerminalEditorApp();
            FrameComposition composition = dialogFrame(24, 80, help, true);
            CellBuffer buffer = background(24, 80);
            Cell[][] document = snapshot(buffer);
            paintDialog(app, buffer, composition, true);
            Rect paintedBounds = changedBounds(document, buffer);

            assertEquals(paintedBounds, app.blockingSurface(composition));
            Cell[][] before = snapshot(buffer);
            app.paintBlockingAttention(buffer, composition, true);

            assertAttention(before, buffer, paintedBounds, true, WARNING);
        }
    }

    @Test
    void inactiveAttentionLeavesTheDocumentAndTinyAttentionStaysOnItsVisibleCompactFrame() throws Exception {
        TerminalEditorApp app = new TerminalEditorApp();
        for (FrameComposition composition : List.of(promptFrame(12, 60, true, false),
            dialogFrame(24, 80, true, false), dialogFrame(24, 80, false, false))) {
            CellBuffer buffer = background(composition.document().layout().rows(), composition.document().layout().cols());
            Cell[][] before = snapshot(buffer);
            app.paintBlockingAttention(buffer, composition, true);
            assertAttention(before, buffer, new Rect(0, 0, 0, 0), false, WARNING);
            if (composition.layers().helpPage() != null) {
                assertFalse(composition.allowsScrollHints(), "document scrolling must not shift the help frame");
            } else {
                assertTrue(composition.allowsScrollHints());
            }
        }
        for (int[] size : List.of(new int[] {7, 80}, new int[] {24, 23}, new int[] {7, 23})) {
            for (boolean help : List.of(true, false)) {
                FrameComposition composition = dialogFrame(size[0], size[1], help, true);
                CellBuffer buffer = background(size[0], size[1]);
                Cell[][] document = snapshot(buffer);
                paintDialog(app, buffer, composition, true);
                Rect expected = app.blockingSurface(composition);
                assertFalse(expected.isEmpty());
                assertEquals(expected, changedBounds(document, buffer));
                Cell[][] before = snapshot(buffer);
                app.paintBlockingAttention(buffer, composition, true);
                assertAttention(before, buffer, expected, true, WARNING);
            }
        }
    }

    @Test
    void monochromeAttentionAddsVisibleAttributesToBothPromptAndDialog() throws Exception {
        TerminalEditorApp app = new TerminalEditorApp();
        CellStyle monochrome = new CellStyle(CellColor.DEFAULT, CellColor.DEFAULT, true, true);
        for (FrameComposition composition : List.of(promptFrame(12, 60, true, true), dialogFrame(24, 80, true, true))) {
            boolean framed = composition.layers().helpPage() != null;
            CellBuffer buffer;
            if (framed) {
                buffer = background(24, 80);
                paintDialog(app, buffer, composition, false);
            } else {
                buffer = paintPrompt(app, composition);
            }
            Cell[][] before = snapshot(buffer);
            app.paintBlockingAttention(buffer, composition, false);
            assertAttention(before, buffer, app.blockingSurface(composition), framed, monochrome);
        }
    }

    @Test
    void attentionDeadlineCompetesWithOtherWakeupsAndDisappearsWhenInactive() {
        DialogAttention attention = new DialogAttention();
        Object owner = TerminalEditorApp.modalInputOwner(UiModalState.saveAs(), false);
        assertEquals(-1L, TerminalEditorApp.earlierDeadline(-1L, attention.nanosUntilChange(0)));
        assertEquals(millis(500), TerminalEditorApp.earlierDeadline(millis(500), -1L));
        attention.request(owner, 0);
        assertEquals(millis(180), TerminalEditorApp.earlierDeadline(millis(500), attention.nanosUntilChange(0)));
        assertEquals(millis(10), TerminalEditorApp.earlierDeadline(attention.nanosUntilChange(0), millis(10)));
        assertEquals(0L, TerminalEditorApp.earlierDeadline(millis(500), attention.nanosUntilChange(millis(180))));
        attention.synchronize(owner, millis(180));
        assertEquals(millis(500), TerminalEditorApp.earlierDeadline(attention.nanosUntilChange(millis(180)), millis(500)));
    }

    @Test
    void modalOwnerProjectionRetainsEquivalentFlowsAndResetsFeedbackWhenTheOwnerChanges() {
        assertEquals(ModalInputPolicy.Kind.NONE, TerminalEditorApp.modalInputKind(UiModalState.none(), false));
        assertNull(TerminalEditorApp.modalInputOwner(UiModalState.none(), false));
        assertEquals(ModalInputPolicy.Kind.OPEN_FILE, TerminalEditorApp.modalInputKind(UiModalState.none(), true));
        assertSame(TerminalEditorApp.modalInputOwner(UiModalState.none(), true),
            TerminalEditorApp.modalInputOwner(UiModalState.none(), true));
        for (UiModalState modal : List.of(UiModalState.helpHints(), UiModalState.helpLicenses(), UiModalState.helpAuthor())) {
            assertEquals(ModalInputPolicy.Kind.HELP, TerminalEditorApp.modalInputKind(modal, false));
        }
        assertEquals(ModalInputPolicy.Kind.SAVE_AS, TerminalEditorApp.modalInputKind(UiModalState.saveAs(), false));
        for (UiModalState modal : List.of(UiModalState.quitConfirm(), UiModalState.closeTabConfirm(1),
            UiModalState.recoveryConfirm(), UiModalState.saveAs().confirmOverwrite(Path.of("file.txt")))) {
            assertEquals(ModalInputPolicy.Kind.CONFIRM, TerminalEditorApp.modalInputKind(modal, false));
        }

        DialogAttention attention = new DialogAttention();
        attention.request(TerminalEditorApp.modalInputOwner(UiModalState.saveAs(), false), 0);
        assertFalse(attention.synchronize(TerminalEditorApp.modalInputOwner(UiModalState.saveAs(), false), millis(50)));
        assertEquals(millis(130), attention.nanosUntilChange(millis(50)));
        Object confirmation = TerminalEditorApp.modalInputOwner(UiModalState.quitConfirm(), false);
        assertTrue(attention.synchronize(confirmation, millis(50)));
        assertFalse(attention.highlighted());
        assertTrue(attention.request(confirmation, millis(50)), "a new owner does not inherit the old cooldown");
        assertTrue(attention.synchronize(TerminalEditorApp.modalInputOwner(UiModalState.none(), false), millis(60)));
        assertFalse(attention.highlighted());
    }

    private static FrameComposition promptFrame(int rows, int cols, boolean saveAs, boolean attention) {
        int top = rows - (saveAs ? 2 : 3);
        Rect clip = new Rect(0, 1, cols, rows - 2);
        SaveAsBarView.Placement field = null;
        ConfirmBarView.Placement confirm = null;
        String text;
        if (saveAs) {
            TextField value = new TextField();
            value.set("file中🦀.txt");
            SaveAsBarView view = SaveAsBarView.build(value, cols);
            field = view.place("save-as-test", top, clip);
            text = view.row();
        } else {
            ConfirmBarView view = ConfirmBarView.build("Save 中🦀?", cols);
            confirm = view.place("confirm-test", top + 1, clip);
            text = "Unsaved document 中🦀\n" + view.row();
        }
        return composition(rows, cols, InlinePromptBar.of(text, Style.DEFAULT, 1),
            new FrameComposition.Layers(null, null, saveAs, field, confirm, FrameComposition.OpenFile.hidden(), null, attention, false));
    }

    private static FrameComposition dialogFrame(int rows, int cols, boolean help, boolean attention) {
        FrameComposition.OpenFile open = FrameComposition.OpenFile.hidden();
        if (!help) {
            var layout = OpenFileDialogController.layoutForTerminal(rows, cols);
            int treeLeft = layout.left() + 2;
            Rect tree = new Rect(treeLeft, layout.listStartRow(), Math.max(0, layout.right() - treeLeft), layout.listRows());
            FileTreeView.Placement placement = FileTreeView.fromSnapshot(
                new FileTreeModel.Snapshot(Path.of("/test/中🦀"), 1, List.of()), false, false
            ).place(tree, FileTreeView.State.initial(), new Rect(0, 0, cols, rows), true);
            open = new FrameComposition.OpenFile("/test/中🦀",
                OpenFileDialogView.place(TerminalEditorApp.OPEN_FILE_SCOPE_ID, layout, placement, false,
                    new Rect(0, 0, cols, rows)), OpenFileInputRouter.Focus.LIST);
        }
        return composition(rows, cols, InlinePromptBar.hidden(),
            new FrameComposition.Layers(null, null, false, null, null, open, help ? new TerminalEditorApp().helpOverlayPlacement(rows, cols, HELP) : null, attention, false));
    }

    private static FrameComposition composition(int rows, int cols, InlinePromptBar prompt, FrameComposition.Layers layers) {
        SimpleLineBuffer lines = new SimpleLineBuffer();
        lines.setLine(0, "document 中🦀 e\u0301");
        TextModel model = new TextModel(lines);
        EditorState state = new EditorState();
        TerminalEditorTextLayout textLayout = new TerminalEditorTextLayout(model);
        RenderPlanBuilder builder = RenderPlanBuilder.standalone(text -> List.of(), textLayout);
        EditorLayout layout = EditorLayout.resolve(model, textLayout, state, rows, cols, 1, prompt.height(), false, false, 4);
        return new FrameComposition(new FrameComposition.Document(builder, model, state, layout, null),
            new FrameComposition.Surface(new TopMenuView.TopBar(" File ", 6), "status", prompt, NotificationBar.hidden()),
            new FrameComposition.Search(false, "", SearchOptions.DEFAULT, false, null, false), layers, false, false, false);
    }

    private static CellBuffer paintPrompt(TerminalEditorApp app, FrameComposition frame) {
        var doc = frame.document();
        var prompt = frame.surface().inlinePrompt();
        var plan = doc.planBuilder().build(doc.model(), doc.state(), doc.layout().rows(), doc.layout().cols(),
            frame.surface().topBar().text(), frame.surface().bottomBar(), "", SearchOptions.DEFAULT,
            prompt.text(), prompt.style(), null, null, false, false, doc.layout());
        CellBuffer buffer = new RenderRasterizer().rasterize(plan, doc.layout().rows(), doc.layout().cols());
        Canvas canvas = new Canvas(buffer);
        if (frame.layers().saveAs() != null) {
            frame.layers().saveAs().paint(canvas, app::resolveSaveAsWidgetStyle, new FocusManager<>());
        }
        if (frame.layers().confirm() != null) {
            frame.layers().confirm().paint(canvas, app::resolveConfirmWidgetStyle, new FocusManager<>());
        }
        return buffer;
    }

    private static void paintDialog(TerminalEditorApp app, CellBuffer buffer, FrameComposition frame, boolean color) throws Exception {
        if (frame.layers().helpPage() != null) {
            app.paintHelpOverlay(new Canvas(buffer), frame.layers().help(), color);
        } else {
            Method painter = TerminalEditorApp.class.getDeclaredMethod("drawOpenFileOverlay", Canvas.class,
                int.class, int.class, FrameComposition.OpenFile.class, boolean.class);
            painter.setAccessible(true);
            painter.invoke(app, new Canvas(buffer), buffer.rows(), buffer.cols(), frame.layers().openFile(), color);
        }
    }

    private static CellBuffer background(int rows, int cols) {
        CellBuffer buffer = new CellBuffer(rows, cols);
        buffer.fill(new Cell(".", BACKGROUND));
        buffer.set(0, 1, "中", BACKGROUND);
        buffer.set(rows - 1, 1, "🦀", BACKGROUND);
        return buffer;
    }

    private static Cell[][] snapshot(CellBuffer buffer) {
        Cell[][] before = new Cell[buffer.rows()][buffer.cols()];
        for (int row = 0; row < buffer.rows(); row++) {
            for (int col = 0; col < buffer.cols(); col++) {
                before[row][col] = buffer.get(row, col);
            }
        }
        return before;
    }

    private static void assertAttention(Cell[][] before, CellBuffer buffer, Rect surface, boolean framed, CellStyle accent) {
        for (int row = 0; row < buffer.rows(); row++) {
            for (int col = 0; col < buffer.cols(); col++) {
                Cell original = before[row][col];
                Cell actual = buffer.get(row, col);
                boolean emphasized = surface.contains(col, row) && (!framed || row == surface.y()
                    || row == surface.bottomExclusive() - 1 || col == surface.x() || col == surface.rightExclusive() - 1);
                assertEquals(emphasized ? accent : original.style(), actual.style(), row + ":" + col);
                assertEquals(original.grapheme(), actual.grapheme(), row + ":" + col);
                assertEquals(original.width(), actual.width(), row + ":" + col);
                assertEquals(original.continuation(), actual.continuation(), row + ":" + col);
            }
        }
    }

    private static Rect changedBounds(Cell[][] before, CellBuffer buffer) {
        int left = buffer.cols(), right = -1, top = buffer.rows(), bottom = -1;
        for (int row = 0; row < buffer.rows(); row++) {
            for (int col = 0; col < buffer.cols(); col++) {
                if (!before[row][col].equals(buffer.get(row, col))) {
                    left = Math.min(left, col);
                    right = Math.max(right, col);
                    top = Math.min(top, row);
                    bottom = Math.max(bottom, row);
                }
            }
        }
        assertTrue(right >= left && bottom >= top, "the dialog must actually paint a visible frame");
        return Rect.fromEdges(left, top, right + 1, bottom + 1);
    }

    private static boolean hasWideGlyph(Cell[][] cells, Rect bounds) {
        for (int row = bounds.y(); row < bounds.bottomExclusive(); row++) {
            for (int col = bounds.x(); col < bounds.rightExclusive(); col++) {
                if (cells[row][col].width() == 2) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long millis(long milliseconds) {
        return TimeUnit.MILLISECONDS.toNanos(milliseconds);
    }
}
