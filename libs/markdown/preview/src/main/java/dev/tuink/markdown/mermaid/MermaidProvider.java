package dev.tuink.markdown.mermaid;

/** Detached, bounded Mermaid interpretation; implementations never own a UI or executor. */
@FunctionalInterface
public interface MermaidProvider {
    Diagram parse(String source, Limits limits);

    default Diagram parse(String source) {
        return parse(source, Limits.DEFAULT);
    }

    record Limits(int maxSourceLength, int maxNodes, int maxEdges, int maxLabelLength) {
        public static final Limits DEFAULT = new Limits(65_536, 128, 256, 1_024);

        public Limits {
            if (maxSourceLength < 1 || maxSourceLength > 1_048_576
                    || maxNodes < 1 || maxNodes > 1_024
                    || maxEdges < 1 || maxEdges > 4_096
                    || maxLabelLength < 1 || maxLabelLength > 16_384) {
                throw new IllegalArgumentException("Invalid Mermaid limits");
            }
        }
    }
}
