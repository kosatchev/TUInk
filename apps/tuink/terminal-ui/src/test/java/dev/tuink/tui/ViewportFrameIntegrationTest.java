package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuiframe.jline.presentation.ScreenPresenter;
import dev.tuink.core.EditorState;
import dev.tuink.core.Position;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.render.EditorLayout;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.render.RenderRasterizer;
import dev.tuink.tui.style.UiStyles;
import dev.tuink.tui.ui.EditorView;
import dev.tuink.tui.ui.InlinePromptBar;
import dev.tuink.tui.ui.NotificationBar;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class ViewportFrameIntegrationTest {
    private static final int ROWS = 14, COLS = 60;

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
        }
    }

    private static FrameComposition source(RenderPlanBuilder builder, TextModel model, EditorState state,
            TerminalEditorTextLayout textLayout) {
        var layout = EditorLayout.resolve(model, textLayout, state, ROWS, COLS, 1, 0, false, false, 4);
        return new FrameComposition(new FrameComposition.Document(builder, model, state, layout),
            surface(), new FrameComposition.Search(false, "", SearchOptions.DEFAULT, false, null, false),
            layers(), false, false, false);
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
            FrameComposition.OpenFile.hidden(), null, null, false, false);
    }
}
