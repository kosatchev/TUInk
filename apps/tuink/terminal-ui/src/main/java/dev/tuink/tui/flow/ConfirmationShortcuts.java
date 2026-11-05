package dev.tuink.tui.flow;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Shared application shortcuts for confirmation input and its visible instructions. */
public enum ConfirmationShortcuts {
    STANDARD(Map.of(Key.YES, ConfirmChoice.SAVE, Key.NO, ConfirmChoice.CANCEL,
        Key.ESCAPE, ConfirmChoice.CANCEL, Key.QUIT, ConfirmChoice.CANCEL)),
    SAVE(Map.of(Key.YES, ConfirmChoice.SAVE, Key.SAVE, ConfirmChoice.SAVE,
        Key.NO, ConfirmChoice.CANCEL, Key.ESCAPE, ConfirmChoice.CANCEL, Key.QUIT, ConfirmChoice.CANCEL)),
    SAVE_CHANGES(Map.of(Key.YES, ConfirmChoice.SAVE, Key.SAVE, ConfirmChoice.SAVE,
        Key.NO, ConfirmChoice.DISCARD, Key.ESCAPE, ConfirmChoice.CANCEL, Key.QUIT, ConfirmChoice.CANCEL)),
    QUIT(Map.of(Key.YES, ConfirmChoice.SAVE, Key.SAVE, ConfirmChoice.SAVE,
        Key.NO, ConfirmChoice.DISCARD, Key.ESCAPE, ConfirmChoice.CANCEL, Key.QUIT, ConfirmChoice.DISCARD)),
    RECOVERY(Map.of(Key.YES, ConfirmChoice.SAVE, Key.DELETE, ConfirmChoice.DISCARD,
        Key.NO, ConfirmChoice.CANCEL, Key.ESCAPE, ConfirmChoice.CANCEL, Key.QUIT, ConfirmationAction.Command.QUIT));

    /** Semantic input keys; ordering also defines the stable order of displayed aliases. */
    public enum Key {
        YES("Y"), SAVE("Ctrl+S"), NO("N"), DELETE("D"), ESCAPE("Esc"), QUIT("Ctrl+Q");

        private final String label;
        private final int textCodePoint;
        Key(String label) {
            this.label = label;
            textCodePoint = label.codePointCount(0, label.length()) == 1
                ? Character.toLowerCase(label.codePointAt(0)) : -1;
        }
    }

    private final Map<Key, ConfirmationAction> decisions;

    ConfirmationShortcuts(Map<Key, ConfirmationAction> decisions) {
        this.decisions = Map.copyOf(decisions);
    }

    /** Null denotes an unbound key; callers must leave it without a decision. */
    public ConfirmationAction decision(Key key) { return decisions.get(Objects.requireNonNull(key)); }

    public ConfirmationAction textDecision(String text) {
        if (text == null || text.codePointCount(0, text.length()) != 1) return null;
        int codePoint = Character.toLowerCase(text.codePointAt(0));
        for (var key : Key.values()) if (key.textCodePoint == codePoint) return decision(key);
        return null;
    }

    public String keysFor(ConfirmationAction choice) {
        Objects.requireNonNull(choice);
        return Arrays.stream(Key.values()).filter(key -> decisions.get(key) == choice)
            .map(key -> key.label).collect(Collectors.joining("/"));
    }
}
