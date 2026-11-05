package dev.tuink.tui.flow;

public enum ConfirmChoice implements ConfirmationAction {
    SAVE,
    DISCARD,
    CANCEL;

    public ConfirmChoice next() {
        return switch (this) {
            case SAVE -> DISCARD;
            case DISCARD -> CANCEL;
            case CANCEL -> SAVE;
        };
    }

    public ConfirmChoice previous() {
        return switch (this) {
            case SAVE -> CANCEL;
            case DISCARD -> SAVE;
            case CANCEL -> DISCARD;
        };
    }
}
