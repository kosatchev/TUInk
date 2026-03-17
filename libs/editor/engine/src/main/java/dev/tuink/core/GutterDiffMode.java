package dev.tuink.core;

/**
 * Rendering mode for gutter diff markers.
 */
public enum GutterDiffMode {
    DISABLED("Disabled"),
    HISTOGRAM("Histogram"),
    MYERS("Myers"),
    STABLE("Stable");

    private final String label;

    GutterDiffMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public GutterDiffMode next() {
        return switch (this) {
            case DISABLED -> HISTOGRAM;
            case HISTOGRAM -> MYERS;
            case MYERS -> STABLE;
            case STABLE -> DISABLED;
        };
    }
}
