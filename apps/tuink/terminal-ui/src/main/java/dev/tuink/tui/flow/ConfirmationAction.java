package dev.tuink.tui.flow;

/** A confirmation button choice or a window command offered by its shortcut policy. */
public sealed interface ConfirmationAction permits ConfirmChoice, ConfirmationAction.Command {
    enum Command implements ConfirmationAction {
        QUIT("Quit");

        private final String label;
        Command(String label) { this.label = label; }
        public String label() { return label; }
    }
}
