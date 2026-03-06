package dev.tuink.core;

/**
 * Strategy for Tab key insertion and indent-unit deletion in leading whitespace.
 */
public enum TabInsertMode {
    AUTO("Auto"),
    ALWAYS_TAB("\\t"),
    ALWAYS_SPACES_4("4 spaces"),
    ALWAYS_SPACES_2("2 spaces");

    private final String label;

    TabInsertMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public TabInsertMode next() {
        return switch (this) {
            case AUTO -> ALWAYS_TAB;
            case ALWAYS_TAB -> ALWAYS_SPACES_4;
            case ALWAYS_SPACES_4 -> ALWAYS_SPACES_2;
            case ALWAYS_SPACES_2 -> AUTO;
        };
    }
}
