package dev.tuiframe.jline;

import dev.tuiframe.input.KeyStroke;
import java.io.IOError;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jline.utils.Curses;
import org.jline.utils.InfoCmp.Capability;

/** Immutable physical key sequences advertised by this terminal's terminfo entry. */
final class TerminalKeyMap {
    static final TerminalKeyMap EMPTY = new TerminalKeyMap(Map.of());
    private static final int MAX_SEQUENCE_LENGTH = 66;
    private final Map<String, KeyStroke> sequences;
    private final Set<String> prefixes;

    private TerminalKeyMap(Map<String, KeyStroke> sequences) {
        this.sequences = Map.copyOf(sequences);
        Set<String> prefixes = new HashSet<>();
        for (String sequence : sequences.keySet()) {
            for (int length = 1; length < sequence.length(); length++) {
                prefixes.add(sequence.substring(0, length));
            }
        }
        this.prefixes = Set.copyOf(prefixes);
    }

    static TerminalKeyMap fromCapabilities(Function<Capability, String> capabilities) {
        Map<String, KeyStroke> result = new HashMap<>();
        bind(result, capabilities, Capability.key_up, KeyStroke.Key.ARROW_UP);
        bind(result, capabilities, Capability.key_down, KeyStroke.Key.ARROW_DOWN);
        bind(result, capabilities, Capability.key_left, KeyStroke.Key.ARROW_LEFT);
        bind(result, capabilities, Capability.key_right, KeyStroke.Key.ARROW_RIGHT);
        bind(result, capabilities, Capability.key_home, KeyStroke.Key.HOME);
        bind(result, capabilities, Capability.key_end, KeyStroke.Key.END);
        bind(result, capabilities, Capability.key_ic, KeyStroke.Key.INSERT);
        bind(result, capabilities, Capability.key_dc, KeyStroke.Key.DELETE);
        bind(result, capabilities, Capability.key_ppage, KeyStroke.Key.PAGE_UP);
        bind(result, capabilities, Capability.key_npage, KeyStroke.Key.PAGE_DOWN);
        bind(result, capabilities, Capability.key_enter, KeyStroke.Key.ENTER);
        bind(result, capabilities, Capability.key_backspace, KeyStroke.Key.BACKSPACE);
        bind(result, capabilities, Capability.key_btab, KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT);
        bind(result, capabilities, Capability.key_shome, KeyStroke.Key.HOME, KeyStroke.Modifier.SHIFT);
        bind(result, capabilities, Capability.key_send, KeyStroke.Key.END, KeyStroke.Modifier.SHIFT);
        bind(result, capabilities, Capability.key_sleft, KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.SHIFT);
        bind(result, capabilities, Capability.key_sright, KeyStroke.Key.ARROW_RIGHT, KeyStroke.Modifier.SHIFT);
        bind(result, capabilities, Capability.key_sic, KeyStroke.Key.INSERT, KeyStroke.Modifier.SHIFT);
        bind(result, capabilities, Capability.key_sdc, KeyStroke.Key.DELETE, KeyStroke.Modifier.SHIFT);
        for (int number = 1; number <= 12; number++) {
            bind(result, capabilities, Capability.valueOf("key_f" + number),
                KeyStroke.Key.valueOf("F" + number));
        }
        return new TerminalKeyMap(result);
    }

    KeyStroke key(String sequence) {
        return sequences.get(sequence);
    }

    boolean hasLongerSequence(String prefix) {
        return prefixes.contains(prefix);
    }

    private static void bind(
        Map<String, KeyStroke> result,
        Function<Capability, String> capabilities,
        Capability capability,
        KeyStroke.Key key,
        KeyStroke.Modifier... modifiers
    ) {
        String encoded = capabilities.apply(capability);
        if (encoded == null || encoded.isEmpty()) return;
        String sequence;
        try {
            sequence = Curses.tputs(encoded);
        } catch (IOError | IllegalArgumentException malformedCapability) {
            return;
        }
        // Plain text and legacy single-byte controls retain their decoder rules.
        // A corrupt terminfo entry must not grow the incremental input buffer.
        if (sequence.length() < 2 || sequence.length() > MAX_SEQUENCE_LENGTH
            || sequence.charAt(0) != '\u001B') return;
        result.putIfAbsent(sequence, KeyStroke.of(key, modifiers));
    }
}
