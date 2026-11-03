package dev.tuink.render.markdown;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.CellText;
import dev.tuink.markdown.mermaid.Diagram;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MermaidLayoutTest {
    @Test void labelsPreferWordBoundariesButStillWrapLongIdentifiers() {
        assertEquals(List.of("Есть", "изменения?"), MermaidLayout.wrap("Есть изменения?", 12));
        var identifier = "abcdefghijklmnopq";
        var wrapped = MermaidLayout.wrap(identifier, 8);
        assertEquals(identifier, String.join("", wrapped));
        assertTrue(wrapped.stream().allMatch(line -> CellText.width(line) <= 8));
    }

    private static final List<Diagram.Node> NODES = List.of(
        new Diagram.Node("A", "Открыть Markdown", Diagram.Shape.BOX),
        new Diagram.Node("B", "Режим", Diagram.Shape.DECISION),
        new Diagram.Node("C", "Редактор", Diagram.Shape.BOX),
        new Diagram.Node("D", "Рендер", Diagram.Shape.BOX),
        new Diagram.Node("E", "Сохранить", Diagram.Shape.BOX));
    private static final List<Diagram.Edge> EDGES = List.of(
        new Diagram.Edge("A", "B", "", false),
        new Diagram.Edge("B", "C", "Исходник", false),
        new Diagram.Edge("B", "D", "Просмотр", false),
        new Diagram.Edge("C", "E", "", false),
        new Diagram.Edge("D", "E", "", false));

    @Test
    void branchAndJoinRenderEachNodeOnceWithArrowsLabelsAndDecision() {
        List<String> lines = MermaidLayout.render(new Diagram.Flowchart("", Diagram.Direction.LR, NODES, EDGES), 80);
        String text = String.join("\n", lines);
        assertFalse(text.contains("[Mermaid:"));
        assertTrue(text.contains("◇ Режим"));
        assertTrue(text.contains("Исходник"));
        assertTrue(text.contains("Просмотр"));
        assertEquals(1, occurrences(text, "Сохранить"));
        assertEquals(1, occurrences(text, "Открыть Markdown"));
        assertEquals(EDGES.size(), text.codePoints().filter(cp -> cp == '→').count());
        assertTrue(lines.stream().anyMatch(line -> CellText.width(line) > 80));
        assertTrue(lines.stream().allMatch(line -> CellText.width(line) <= 1_024));
        assertTrue(lines.stream().mapToInt(CellText::width).max().orElseThrow() < 124,
            "The demonstration should use less space than the previous 124-column layout");
    }

    @Test
    void horizontalAndVerticalDirectionsTransformConnectionsButNotText() {
        for (Diagram.Direction direction : Diagram.Direction.values()) {
            var diagram = new Diagram.Flowchart("", direction,
                List.of(NODES.get(0), NODES.get(1)),
                List.of(new Diagram.Edge("A", "B", "next", false)));
            String text = String.join("\n", MermaidLayout.render(diagram, 80));
            assertFalse(text.contains("[Mermaid:"), direction.toString());
            assertTrue(text.contains("Открыть Markdown"));
            assertTrue(text.contains(switch (direction) {
                case LR -> "→"; case RL -> "←"; case TD -> "↓"; case BT -> "↑";
            }));
            assertTrue(text.contains("next"));
        }
    }

    @Test
    void edgesAcrossIntermediateLayersUseOutsideLanes() {
        var diagram = new Diagram.Flowchart("", Diagram.Direction.LR,
            List.of(NODES.get(0), NODES.get(1), NODES.get(2)),
            List.of(new Diagram.Edge("A", "B", "", false),
                new Diagram.Edge("B", "C", "", false),
                new Diagram.Edge("A", "C", "skip", true)));
        String text = String.join("\n", MermaidLayout.render(diagram, 80));
        assertFalse(text.contains("[Mermaid:"));
        assertTrue(text.contains("skip"));
        assertTrue(text.contains("╌"));
        assertEquals(1, occurrences(text, "Редактор"));
    }

    @Test
    void sequencesPreserveMessageOrderReplyDirectionAndLifelines() {
        var sequence = new Diagram.Sequence("", List.of(
            new Diagram.Participant("U", "Пользователь", false),
            new Diagram.Participant("E", "Редактор", false),
            new Diagram.Participant("R", "Рендер", false)), List.of(
            new Diagram.Message("U", "E", "Изменить документ", false),
            new Diagram.Message("E", "R", "Snapshot новой ревизии", false),
            new Diagram.Message("R", "E", "Модель для текущей ревизии", true),
            new Diagram.Message("E", "U", "Обновлённый просмотр", true)));
        String text = String.join("\n", MermaidLayout.render(sequence, 80));
        assertFalse(text.contains("[Mermaid:"));
        assertEquals(2, occurrences(text, "Пользователь"));
        assertTrue(text.indexOf("Изменить документ") < text.indexOf("Snapshot новой ревизии"));
        assertTrue(text.indexOf("Snapshot новой ревизии") < text.indexOf("Модель для текущей ревизии"));
        assertTrue(text.indexOf("Модель для текущей ревизии") < text.indexOf("Обновлённый просмотр"));
        assertEquals(2, text.codePoints().filter(cp -> cp == '→').count());
        assertEquals(2, text.codePoints().filter(cp -> cp == '←').count());
        assertTrue(text.contains("╌"));
        assertTrue(text.contains("│"));
    }

    @Test
    void selfMessagesAreASeparateVisibleLoop() {
        var sequence = new Diagram.Sequence("", List.of(new Diagram.Participant("A", "Actor", true)),
            List.of(new Diagram.Message("A", "A", "self", false)));
        String text = String.join("\n", MermaidLayout.render(sequence, 20));
        assertFalse(text.contains("[Mermaid:"));
        assertTrue(text.contains("self"));
        assertTrue(text.contains("←"));
        assertTrue(text.contains("┐"));
        assertTrue(text.contains("┘"));
    }

    @Test
    void labelsUseTerminalCellsAndRemainIntactInANarrowViewport() {
        var diagram = new Diagram.Flowchart("", Diagram.Direction.TD,
            List.of(new Diagram.Node("A", "界界界界界界", Diagram.Shape.BOX)), List.of());
        List<String> lines = MermaidLayout.render(diagram, 10);
        assertFalse(String.join("\n", lines).contains("[Mermaid:"));
        assertEquals(6, String.join("", lines).codePoints().filter(cp -> cp == '界').count());
        assertTrue(lines.stream().anyMatch(line -> CellText.width(line) > 10));
    }

    @Test
    void unsupportedCyclesUnknownEdgesAndHugeLayoutsHaveExplicitBoundedFallbacks() {
        var cycle = new Diagram.Flowchart("source", Diagram.Direction.LR,
            List.of(NODES.get(0), NODES.get(1)),
            List.of(new Diagram.Edge("A", "B", "", false), new Diagram.Edge("B", "A", "", false)));
        assertTrue(MermaidLayout.render(cycle, 80).get(0).contains("Cyclic"));
        var missing = new Diagram.Flowchart("source", Diagram.Direction.LR,
            List.of(NODES.get(0)), List.of(new Diagram.Edge("A", "Z", "", false)));
        assertTrue(MermaidLayout.render(missing, 80).get(0).contains("unknown"));
        List<Diagram.Node> many = new ArrayList<>();
        List<Diagram.Edge> edges = new ArrayList<>();
        for (int index = 0; index < 128; index++) {
            many.add(new Diagram.Node("n" + index, "node " + index, Diagram.Shape.BOX));
            if (index > 0) edges.add(new Diagram.Edge("n" + (index - 1), "n" + index, "", false));
        }
        var huge = new Diagram.Flowchart("source", Diagram.Direction.LR, many, edges);
        assertTrue(MermaidLayout.render(huge, 80).get(0).contains("budget"));
        List<String> fallback = MermaidLayout.render(new Diagram.Unsupported("x\n".repeat(100_000), "unsupported"), 80);
        assertTrue(fallback.size() <= 1_026);
        assertTrue(fallback.stream().mapToInt(String::length).sum() < 66_000);
    }

    private static int occurrences(String text, String token) {
        int result = 0;
        for (int offset = 0; (offset = text.indexOf(token, offset)) >= 0; offset += token.length()) result++;
        return result;
    }
}
