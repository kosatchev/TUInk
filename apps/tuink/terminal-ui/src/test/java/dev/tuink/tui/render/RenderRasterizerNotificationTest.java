package dev.tuink.tui.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.CellBuffer;
import dev.tuink.core.EditorState;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.render.RenderPlan;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuink.render.TerminalEditorTextLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

class RenderRasterizerNotificationTest {
    @Test
    void rawRenderPlanNotificationsCannotClaimMultipleRowsBesideAMultilineSearchPanel() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        RenderPlanBuilder builder = RenderPlanBuilder.standalone(ignored -> List.of(), new TerminalEditorTextLayout(model));
        RenderPlan plain = builder.build(model, new EditorState(), 8, 60, "top", "bottom", null,
            SearchOptions.DEFAULT, "Find:\nReplace:", null, null, null, false, false);
        RenderPlan withNotice = builder.build(model, new EditorState(), 8, 60, "top", "bottom", null,
            SearchOptions.DEFAULT, "Find:\nReplace:", null, "A\nB\rC\tD\u001B\u0085", null, false, false);
        RenderRasterizer renderer = new RenderRasterizer();
        CellBuffer before = renderer.rasterize(plain, 8, 60);
        CellBuffer after = renderer.rasterize(withNotice, 8, 60);

        assertTrue(row(after, 4).startsWith("A\\nB\\rC\\tD\\u001B\\u0085"));
        assertTrue(row(after, 5).startsWith("Find:"));
        assertTrue(row(after, 6).startsWith("Replace:"));
        for (int line = 0; line < 8; line++) {
            if (line != 4) assertEquals(row(before, line), row(after, line));
        }
    }

    private static String row(CellBuffer buffer, int row) {
        StringBuilder text = new StringBuilder();
        for (int column = 0; column < buffer.cols(); column++) text.append(buffer.get(row, column).grapheme());
        return text.toString();
    }
}
