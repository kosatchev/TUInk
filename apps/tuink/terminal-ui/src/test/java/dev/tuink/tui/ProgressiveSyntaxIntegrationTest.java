package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import dev.tuink.core.Position;
import dev.tuink.highlight.regex.RegexHighlighter;
import dev.tuink.render.RenderPlanBuilder;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressiveSyntaxIntegrationTest {
    @Test
    void incompleteSyntaxSchedulesIdleWorkUntilTheVisibleLinesAreHighlighted(@TempDir Path directory) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false,
            Collections.nCopies(10_000, "public class Example { int value = 123; }"))) {
            window.resize(8, 80);
            var session = window.active().tabSession();
            var builder = new RenderPlanBuilder(RegexHighlighter.forJava(), session.engine().textLayout());
            window.context.replaceActiveRenderPlanBuilder(builder);
            builder.setBracketMatchingEnabled(false);
            builder.setRainbowBracketsEnabled(false);
            session.state().setCursor(new Position(9990, 0));
            session.state().setTopLine(9990);

            window.frame();

            assertTrue(builder.hasPendingSyntaxWork());
            int sourceRow = window.rowStartingWith("  9991 ");
            int sourceColumn = window.screenRow(sourceRow).indexOf("public");
            assertEquals(CellStyle.DEFAULT, window.screenStyle(sourceRow, sourceColumn));
            long deadline = window.controller.nanosUntilWakeup(System.nanoTime());
            assertTrue(deadline >= 0L && deadline <= 16_000_000L, "Incomplete syntax needs an idle wakeup");
            for (int attempt = 0; attempt < 20 && builder.hasPendingSyntaxWork(); attempt++) {
                window.frame();
            }
            assertFalse(builder.hasPendingSyntaxWork());
            CellStyle keyword = new CellStyle(CellColor.BRIGHT_BLUE, CellColor.DEFAULT, true, false);
            assertEquals(keyword, window.screenStyle(sourceRow, sourceColumn),
                "Completed syntax must replace the earlier plain-text cells in the rasterizer cache");
            window.frame();
            assertEquals(keyword, window.screenStyle(sourceRow, sourceColumn));
            assertTrue(builder.build(session.model(), session.state(), 8, 80, "top", "bottom", null,
                dev.tuink.core.SearchOptions.DEFAULT, null, null, null, null, false, false)
                .lines().stream().allMatch(line -> !line.spans().isEmpty()));
        }
    }
}
