package dev.tuink.tui.flow;

/** Shared application shortcuts for confirmation input and its visible instructions. */
public enum ConfirmationShortcuts {
    STANDARD(false, ConfirmChoice.CANCEL, ConfirmChoice.CANCEL),
    SAVE(true, ConfirmChoice.CANCEL, ConfirmChoice.CANCEL),
    SAVE_CHANGES(true, ConfirmChoice.DISCARD, ConfirmChoice.CANCEL),
    QUIT(true, ConfirmChoice.DISCARD, ConfirmChoice.DISCARD);

    private final boolean saveShortcut;
    private final ConfirmChoice noDecision;
    private final ConfirmChoice ctrlQDecision;

    ConfirmationShortcuts(boolean saveShortcut, ConfirmChoice noDecision, ConfirmChoice ctrlQDecision) {
        this.saveShortcut = saveShortcut;
        this.noDecision = noDecision;
        this.ctrlQDecision = ctrlQDecision;
    }

    public boolean saveShortcut() { return saveShortcut; }
    public ConfirmChoice noDecision() { return noDecision; }
    public ConfirmChoice ctrlQDecision() { return ctrlQDecision; }
}
