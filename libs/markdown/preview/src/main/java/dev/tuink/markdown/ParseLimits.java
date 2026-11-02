package dev.tuink.markdown;

/** Admission budgets; they apply before allocating an external parser tree. */
public record ParseLimits(int maxCodeUnits, int maxNodes, int maxDepth, int maxTableCells) {
    public static final ParseLimits DEFAULT = new ParseLimits(2 * 1024 * 1024, 100_000, 96, 20_000);
    public ParseLimits {
        if (maxCodeUnits < 1 || maxNodes < 1 || maxDepth < 1 || maxDepth > 512 || maxTableCells < 1)
            throw new IllegalArgumentException("Invalid Markdown budgets");
    }
}
