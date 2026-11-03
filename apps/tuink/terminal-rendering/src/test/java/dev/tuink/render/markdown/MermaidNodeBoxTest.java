package dev.tuink.render.markdown;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.CellText;
import dev.tuink.markdown.mermaid.Diagram;
import java.util.List;
import org.junit.jupiter.api.Test;

class MermaidNodeBoxTest {
    @Test void decisionsHaveSlantedOutlinesAndBoundaryPortsInsteadOfARoundedRectangle() {
        var box = new MermaidNodeBox(new Diagram.Node("choice", "Yes", Diagram.Shape.DECISION), 28, 0, 0);
        box.x = 2; box.y = 1;
        var lines = draw(box);
        String text = String.join("\n", lines);
        assertTrue(text.contains("╱") && text.contains("╲"));
        assertFalse(text.contains("┌") || text.contains("╭"));
        assertEquals(4, text.codePoints().filter(cp -> cp == '◇').count());
        assertTrue(text.contains("Yes"));
        assertEquals(box.height, lines.size());
        for (int row = box.y; row < box.y + box.height; row++) {
            int left = box.leftAt(row), right = box.rightAt(row);
            String line = lines.get(row - box.y);
            assertEquals(row == box.centerY() || left == right ? '◇' : row < box.centerY() ? '╱' : '╲', line.charAt(left));
            assertEquals(row == box.centerY() || left == right ? '◇' : row < box.centerY() ? '╲' : '╱', line.charAt(right));
        }
        for (int column = box.x; column < box.x + box.width; column++) {
            assertEquals(box.y + Math.abs(column - box.centerX()), box.topAt(column));
            assertEquals(box.y + box.height - 1 - Math.abs(column - box.centerX()), box.bottomAt(column));
        }
    }

    @Test void minimumDimensionsLeaveRoomForSeparatePortsWithoutStretchingTheLabel() {
        for (var shape : Diagram.Shape.values()) {
            var box = new MermaidNodeBox(new Diagram.Node("node", "padded", shape), 20, 17, 11);
            box.x = 3; box.y = 2;
            assertTrue(box.width >= 17);
            assertTrue(box.height >= 11);
            var lines = draw(box);
            assertEquals(box.height, lines.size());
            assertEquals(box.x + box.width, lines.stream().mapToInt(CellText::width).max().orElseThrow());
            String text = String.join("\n", lines);
            assertEquals(text.indexOf("padded"), text.lastIndexOf("padded"));
            assertTrue(text.contains("padded"));
            for (int row = box.y; row < box.y + box.height; row++) {
                assertTrue(box.leftAt(row) >= box.x);
                assertTrue(box.rightAt(row) < box.x + box.width);
                assertTrue(box.leftAt(row) <= box.rightAt(row));
            }
        }
    }

    @Test void unicodeAndMultilineDecisionLabelsRemainInsideTheirActualOutline() {
        String label = "文件 / Café 😀\nВторая строка";
        var box = new MermaidNodeBox(new Diagram.Node("unicode", label, Diagram.Shape.DECISION), 28, 0, 0);
        box.x = 2; box.y = 2;
        var lines = draw(box);
        String text = String.join("\n", lines);
        assertTrue(text.contains("文件"));
        assertTrue(text.contains("é"));
        assertTrue(text.contains("😀"));
        assertTrue(box.label.size() > 1);
        int firstRow = box.y + (box.height - box.label.size()) / 2;
        for (int row = 0; row < box.label.size(); row++) {
            String value = box.label.get(row);
            int cells = CellText.width(value);
            int start = box.x + (box.width - cells) / 2;
            assertTrue(start >= box.leftAt(firstRow + row) + 2);
            assertTrue(start + cells <= box.rightAt(firstRow + row) - 1);
            assertTrue(text.contains(value));
        }
        assertTrue(lines.stream().allMatch(line -> CellText.width(line) <= box.x + box.width));
    }

    @Test void rectangleAndRoundedNodesKeepTheirDistinctCornersAndAbsoluteBounds() {
        for (var shape : List.of(Diagram.Shape.BOX, Diagram.Shape.ROUNDED)) {
            var box = new MermaidNodeBox(new Diagram.Node("rect", "one\ntwo", shape), 20, 0, 7);
            box.x = 4; box.y = 3;
            var lines = draw(box);
            assertEquals(shape == Diagram.Shape.BOX ? '┌' : '╭', lines.get(0).charAt(box.x));
            assertEquals(shape == Diagram.Shape.BOX ? '└' : '╰', lines.get(box.height - 1).charAt(box.x));
            assertEquals(box.x, box.leftAt(box.centerY()));
            assertEquals(box.x + box.width - 1, box.rightAt(box.centerY()));
            assertEquals(box.y, box.topAt(box.centerX()));
            assertEquals(box.y + box.height - 1, box.bottomAt(box.centerX()));
            assertThrows(IllegalArgumentException.class, () -> box.leftAt(box.y - 1));
            assertThrows(IllegalArgumentException.class, () -> box.topAt(box.x + box.width));
        }
    }

    private static List<String> draw(MermaidNodeBox box) {
        var grid = new MermaidLayout.Grid(box.x + box.width + 2, box.y + box.height + 2);
        box.draw(grid);
        return grid.lines();
    }
}
