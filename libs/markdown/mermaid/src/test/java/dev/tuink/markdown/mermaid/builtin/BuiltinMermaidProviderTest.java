package dev.tuink.markdown.mermaid.builtin;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.markdown.mermaid.Diagram;
import dev.tuink.markdown.mermaid.MermaidProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuiltinMermaidProviderTest {
    private final BuiltinMermaidProvider provider = new BuiltinMermaidProvider();

    @Test
    void demoFlowchartPreservesTopologyDecisionAndCyrillicLabels() {
        String source = """
            flowchart LR
                A["Открыть Markdown"] --> B{"Режим"}
                B -->|"Исходник"| C["Редактор"]
                B -->|"Просмотр"| D["Рендер"]
                C --> E["Сохранить"]
                D --> E
            """;
        var graph = assertInstanceOf(Diagram.Flowchart.class, provider.parse(source));
        assertEquals(source, graph.source());
        assertEquals(Diagram.Direction.LR, graph.direction());
        assertEquals(List.of("A", "B", "C", "D", "E"), graph.nodes().stream().map(Diagram.Node::id).toList());
        assertEquals(Diagram.Shape.DECISION, graph.nodes().get(1).shape());
        assertEquals("Открыть Markdown", graph.nodes().get(0).label());
        assertEquals(List.of("A>B", "B>C", "B>D", "C>E", "D>E"),
            graph.edges().stream().map(edge -> edge.from() + ">" + edge.to()).toList());
        assertEquals(List.of("", "Исходник", "Просмотр", "", ""),
            graph.edges().stream().map(Diagram.Edge::label).toList());
        assertThrows(UnsupportedOperationException.class, () -> graph.nodes().clear());
    }

    @Test
    void demoSequencePreservesParticipantsAliasesOrderAndReplyStyle() {
        String source = """
            sequenceDiagram
                participant U as Пользователь
                participant E as Редактор
                participant R as Рендер
                U->>E: Изменить документ
                E->>R: Snapshot новой ревизии
                R-->>E: Модель для текущей ревизии
                E-->>U: Обновлённый просмотр
            """;
        var sequence = assertInstanceOf(Diagram.Sequence.class, provider.parse(source));
        assertEquals(source, sequence.source());
        assertEquals(List.of("Пользователь", "Редактор", "Рендер"),
            sequence.participants().stream().map(Diagram.Participant::label).toList());
        assertEquals(List.of("U>E", "E>R", "R>E", "E>U"),
            sequence.messages().stream().map(message -> message.from() + ">" + message.to()).toList());
        assertFalse(sequence.messages().get(0).dashed());
        assertTrue(sequence.messages().get(2).dashed());
        assertEquals("Обновлённый просмотр", sequence.messages().get(3).label());
    }

    @Test
    void graphHeaderDirectionsChainsDeclarationsAndCommentsAreInterpreted() {
        for (String direction : List.of("LR", "RL", "TD", "TB", "BT")) {
            var graph = assertInstanceOf(Diagram.Flowchart.class, provider.parse(
                "%% comment\ngraph " + direction + "; A-->B-.->C\nA[\"renamed\"] %% comment\n"));
            assertEquals(direction.equals("TB") ? Diagram.Direction.TD : Diagram.Direction.valueOf(direction),
                graph.direction());
            assertEquals("renamed", graph.nodes().get(0).label());
            assertTrue(graph.edges().get(1).dashed());
            assertEquals("B", graph.edges().get(1).from());
            assertEquals("C", graph.edges().get(1).to());
        }
    }

    @Test
    void infixEdgeLabelsQuotedPipesAndLineBreakLabelsArePreserved() {
        var graph = assertInstanceOf(Diagram.Flowchart.class, provider.parse("""
            flowchart TD
                A("start") -- "yes" --> B["a | b<br/>next"]
            """));
        assertEquals(Diagram.Shape.ROUNDED, graph.nodes().get(0).shape());
        assertEquals("yes", graph.edges().get(0).label());
        assertEquals("a | b\nnext", graph.nodes().get(1).label());
    }

    @Test
    void implicitSequenceParticipantsSelfMessagesAndActorAliasesWork() {
        var sequence = assertInstanceOf(Diagram.Sequence.class, provider.parse("""
            sequenceDiagram
                actor U as User
                U->>E: open
                E-->>E: update
            """));
        assertTrue(sequence.participants().get(0).actor());
        assertEquals("E", sequence.participants().get(1).id());
        assertEquals("E", sequence.messages().get(1).from());
        assertEquals("E", sequence.messages().get(1).to());
    }

    @Test
    void sequenceMessagePunctuationIsNotMistakenForFlowchartShapes() {
        var sequence = assertInstanceOf(Diagram.Sequence.class, provider.parse("""
            sequenceDiagram
                A->>B: call(value[0
                B-->>A: 100%% complete
            """));
        assertEquals("call(value[0", sequence.messages().get(0).label());
        assertEquals("100%% complete", sequence.messages().get(1).label());
    }

    @Test
    void unsupportedSyntaxDoesNotProduceASilentlyPartialDiagram() {
        for (String source : List.of(
            "classDiagram\nclass Thing",
            "flowchart LR\nA-->B\nstyle A fill:red",
            "flowchart XX\nA-->B",
            "flowchart LR\nA-->B & C",
            "flowchart LR\nA[\"not closed]",
            "sequenceDiagram\nA->>B: hello\nactivate B",
            "sequenceDiagram\nA--xB: stop",
            "flowchart LR\nA[\"<b>html</b>\"]")) {
            var unsupported = assertInstanceOf(Diagram.Unsupported.class, provider.parse(source), source);
            assertEquals(source, unsupported.source());
            assertFalse(unsupported.diagnostic().isBlank());
        }
    }

    @Test
    void sourceNodeEdgeAndLabelBudgetsAreAdmissionLimits() {
        assertInstanceOf(Diagram.Unsupported.class, provider.parse("flowchart LR\nA-->B",
            new MermaidProvider.Limits(5, 10, 10, 10)));
        assertInstanceOf(Diagram.Unsupported.class, provider.parse("flowchart LR\nA-->B-->C",
            new MermaidProvider.Limits(1_000, 2, 10, 10)));
        assertInstanceOf(Diagram.Unsupported.class, provider.parse("flowchart LR\nA-->B-->C",
            new MermaidProvider.Limits(1_000, 10, 1, 10)));
        assertInstanceOf(Diagram.Unsupported.class, provider.parse("flowchart LR\nA[\"long label\"]",
            new MermaidProvider.Limits(1_000, 10, 10, 3)));
        assertThrows(IllegalArgumentException.class, () -> new MermaidProvider.Limits(0, 1, 1, 1));
    }

    @Test
    void interruptedWorkReturnsALocalFailureWithoutClearingTheInterrupt() {
        Thread.currentThread().interrupt();
        try {
            var unsupported = assertInstanceOf(Diagram.Unsupported.class,
                provider.parse("flowchart LR\nA-->B"));
            assertTrue(unsupported.diagnostic().contains("cancelled"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
