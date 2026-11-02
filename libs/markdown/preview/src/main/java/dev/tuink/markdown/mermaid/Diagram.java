package dev.tuink.markdown.mermaid;

import java.util.List;
import java.util.Objects;

/** Immutable, presentation-independent result of interpreting one Mermaid source block. */
public sealed interface Diagram permits Diagram.Flowchart, Diagram.Sequence, Diagram.Unsupported {
    String source();

    enum Direction { LR, RL, TD, BT }
    enum Shape { BOX, ROUNDED, DECISION }

    record Node(String id, String label, Shape shape) {
        public Node {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(shape, "shape");
        }
    }

    record Edge(String from, String to, String label, boolean dashed) {
        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(label, "label");
        }
    }

    record Flowchart(String source, Direction direction, List<Node> nodes, List<Edge> edges) implements Diagram {
        public Flowchart {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(direction, "direction");
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }
    }

    record Participant(String id, String label, boolean actor) {
        public Participant {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
        }
    }

    record Message(String from, String to, String label, boolean dashed) {
        public Message {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(label, "label");
        }
    }

    record Sequence(String source, List<Participant> participants, List<Message> messages) implements Diagram {
        public Sequence {
            Objects.requireNonNull(source, "source");
            participants = List.copyOf(participants);
            messages = List.copyOf(messages);
        }
    }

    /** A local, explicit fallback; the exact original source remains available to copy. */
    record Unsupported(String source, String diagnostic) implements Diagram {
        public Unsupported {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(diagnostic, "diagnostic");
        }
    }
}
