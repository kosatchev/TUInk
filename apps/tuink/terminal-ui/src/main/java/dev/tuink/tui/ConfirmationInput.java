package dev.tuink.tui;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuink.tui.flow.ConfirmationAction;
import dev.tuink.tui.flow.ConfirmationShortcuts;
import java.util.Set;

/** Shared interpretation for modal preflight and the confirmation's actual input owner. */
final class ConfirmationInput {
    private static final Set<KeyStroke.Modifier> CTRL = Set.of(KeyStroke.Modifier.CTRL);
    private ConfirmationInput() {}

    static ConfirmationAction decision(ConfirmationShortcuts shortcuts, TuiInputEvent event) {
        if (event instanceof TextInput text) return shortcuts.textDecision(text.text());
        if (!(event instanceof KeyStroke stroke)) return null;
        ConfirmationShortcuts.Key key = null;
        if (stroke.modifiers().equals(CTRL)) {
            key = switch (stroke.key()) {
                case Q -> ConfirmationShortcuts.Key.QUIT;
                case S -> ConfirmationShortcuts.Key.SAVE;
                default -> null;
            };
        } else if (stroke.modifiers().isEmpty() && stroke.key() == KeyStroke.Key.ESCAPE) {
            key = ConfirmationShortcuts.Key.ESCAPE;
        }
        return key == null ? null : shortcuts.decision(key);
    }
}
