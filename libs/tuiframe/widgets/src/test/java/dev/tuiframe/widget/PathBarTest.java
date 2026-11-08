package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class PathBarTest {
    @Test void collapsesWholeDirectoriesBeforeTouchingTheLeaf() {
        var absolute = new PathBar("/", List.of("home", "person", "project", "src", "File.java"), "/");
        assertEquals("/home/person/project/src/File.java", absolute.fit(80));
        assertEquals("/…/src/File.java", absolute.fit(16));
        var relative = new PathBar("", List.of("project", "long-directory", "src", "File.java"), "/");
        assertEquals("project/…/src/File.java", relative.fit(24));
        assertEquals("…/src/File.java", relative.fit(16));
    }

    @Test void narrowFilenamesKeepTheirExtensionAndUnicodeClustersWithinTheCellBudget() {
        var bar = new PathBar("/", List.of("very-long-directory", "e\u0301日本🙂-very-long-filename.java"), "/");
        assertTrue(bar.fit(15).endsWith(".java"));
        var context = new PathBar("/", List.of("long-directory", "src", "very-long-filename-that-needs-elision.java"), "/");
        assertTrue(context.fit(30).startsWith("/…/src/"));
        assertTrue(context.fit(30).endsWith(".java"));
        for (int width = 0; width < 100; width++) {
            String fitted = bar.fit(width);
            assertTrue(CellText.width(fitted) <= width, width + ": " + fitted);
            assertFalse(fitted.contains("\uFFFD"));
            assertFalse(fitted.startsWith("\u0301"));
        }
    }

    @Test void draftSuffixSurvivesDirectoryElisionAndTinyAllocationsRemainBounded() {
        var bar = new PathBar("/", List.of("long-parent", "document.md"), "/", " (Draft)", LabelPolicy.DEFAULT);
        assertEquals("/…/document.md (Draft)", bar.fit(23));
        for (int width = 0; width < 50; width++) assertTrue(CellText.width(bar.fit(width)) <= width);
    }

    @Test void paintFillsOnlyItsSingleAllocatedRowAndHonorsParentClipping() {
        var bar = new PathBar("/", List.of("somewhere", "document.txt"), "/");
        var placement = bar.layout(new Rect(4, 2, 20, 3));
        assertEquals(new Rect(4, 2, 20, 1), placement.bounds());
        var cells = new CellBuffer(6, 30);
        var style = new CellStyle(CellColor.WHITE, CellColor.BLUE, false, false);
        var canvas = new Canvas(cells);
        canvas.clip(new Rect(5, 0, 12, 6));
        placement.paint(canvas, role -> WidgetThemeKeys.PATH_BAR.equals(role) ? style : null);
        assertEquals(CellStyle.DEFAULT, cells.get(2, 4).style());
        assertEquals(style, cells.get(2, 5).style());
        assertEquals(CellStyle.DEFAULT, cells.get(2, 17).style());
        assertEquals(CellStyle.DEFAULT, cells.get(3, 5).style());
    }

    @Test void aDeepPathKeepsVisibleContextWithoutChangingTheCompletePath() {
        var segments = new ArrayList<>(Collections.nCopies(2000, "d"));
        segments.add("document.md");
        var bar = new PathBar("/", segments, "/");
        assertEquals("/" + String.join("/", segments), bar.text());
        for (int width : new int[] {0, 1, 8, 20, 40, 80}) {
            String fitted = bar.fit(width);
            assertTrue(CellText.width(fitted) <= width);
            if (width >= 20) {
                assertTrue(fitted.startsWith("/…/d/"), fitted);
                assertTrue(fitted.endsWith("/document.md"), fitted);
            }
        }
    }
}
