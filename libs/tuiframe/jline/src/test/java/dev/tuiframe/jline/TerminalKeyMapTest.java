package dev.tuiframe.jline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

class TerminalKeyMapTest {
    @Test
    void linuxConsoleFunctionKeysAndBackTabConsumeTheirEntireSequences() throws Exception {
        QueueReader reader = new QueueReader();
        KeyDecoder decoder = new KeyDecoder(reader, false, linuxKeys());
        for (int number = 1; number <= 5; number++) {
            reader.add("\u001B[[" + (char) ('A' + number - 1) + "x");
            assertEquals(KeyStroke.of(KeyStroke.Key.valueOf("F" + number)), event(decoder));
            assertEquals(new TextInput("x"), event(decoder));
        }
        reader.add("\u001B\tx");
        assertEquals(KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT), event(decoder));
        assertEquals(new TextInput("x"), event(decoder));
    }

    @Test
    void terminalKeyPrefixesSurviveAnIdleReadAtEveryBoundary() throws Exception {
        for (String sequence : new String[] {"\u001B[[A", "\u001B\t"}) {
            for (int split = 1; split < sequence.length(); split++) {
                QueueReader reader = new QueueReader();
                KeyDecoder decoder = new KeyDecoder(reader, false, linuxKeys());
                reader.add(sequence.substring(0, split));
                assertEquals(KeyDecoder.ReadStatus.IDLE, decoder.read(1).status());
                reader.add(sequence.substring(split) + "x");
                assertEquals(sequence.endsWith("A") ? KeyStroke.of(KeyStroke.Key.F1)
                    : KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT), event(decoder));
                assertEquals(new TextInput("x"), event(decoder));
            }
        }
    }

    @Test
    void genericProtocolsRemainAvailableAlongsideTerminfoKeys() throws Exception {
        QueueReader reader = new QueueReader();
        KeyDecoder decoder = new KeyDecoder(reader, false, linuxKeys());
        reader.add("\u001B[A\u001BOP\u001B[1;5D\u001B[83;6u\u001Bb\u001B[200~paste\u001B[[A\u001B[201~"
            + "\u001B[<0;3;2Mx");
        assertEquals(KeyStroke.of(KeyStroke.Key.ARROW_UP), event(decoder));
        assertEquals(KeyStroke.of(KeyStroke.Key.F1), event(decoder));
        assertEquals(KeyStroke.of(KeyStroke.Key.ARROW_LEFT, KeyStroke.Modifier.CTRL), event(decoder));
        assertEquals(KeyStroke.of(KeyStroke.Key.S, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT), event(decoder));
        assertEquals(KeyStroke.of(KeyStroke.Key.B, KeyStroke.Modifier.ALT), event(decoder));
        assertEquals(new PasteEvent("paste\u001B[[A"), event(decoder));
        assertEquals(new PointerEvent(1, 2, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of()), event(decoder));
        assertEquals(new TextInput("x"), event(decoder));
    }

    @Test
    void customTerminfoSequenceWinsOverGenericCsiFinalByteRules() throws Exception {
        QueueReader reader = new QueueReader();
        TerminalKeyMap keys = TerminalKeyMap.fromCapabilities(cap -> cap == Capability.key_home ? "\\E[Hxyz" : null);
        KeyDecoder decoder = new KeyDecoder(reader, false, keys);
        reader.add("\u001B[Hxyz!");
        assertEquals(KeyStroke.of(KeyStroke.Key.HOME), event(decoder));
        assertEquals(new TextInput("!"), event(decoder));
    }

    @Test
    void mismatchingTerminfoPrefixReplaysAllBytesThroughTheGenericDecoder() throws Exception {
        QueueReader reader = new QueueReader();
        TerminalKeyMap keys = TerminalKeyMap.fromCapabilities(cap -> cap == Capability.key_f1 ? "\\Ebx" : null);
        KeyDecoder decoder = new KeyDecoder(reader, false, keys);
        reader.add("\u001Bbyz");
        assertEquals(KeyStroke.of(KeyStroke.Key.B, KeyStroke.Modifier.ALT), event(decoder));
        assertEquals(new TextInput("y"), event(decoder));
        assertEquals(new TextInput("z"), event(decoder));
    }

    @Test
    void longestMatchFallsBackToTheCompleteKeyWithoutConsumingFollowingInput() throws Exception {
        TerminalKeyMap keys = TerminalKeyMap.fromCapabilities(cap -> switch (cap) {
            case key_f1 -> "\\Ebx";
            case key_f2 -> "\\Ebxyz";
            default -> null;
        });
        QueueReader reader = new QueueReader();
        AtomicLong clock = new AtomicLong();
        KeyDecoder decoder = new KeyDecoder(reader, false, keys, clock::get);
        reader.add("\u001Bbxyz\u001Bbx!");
        assertEquals(KeyStroke.of(KeyStroke.Key.F2), event(decoder));
        assertEquals(KeyStroke.of(KeyStroke.Key.F1), event(decoder));
        assertEquals(new TextInput("!"), event(decoder));
        reader.add("\u001Bbx");
        assertEquals(KeyDecoder.ReadStatus.IDLE, decoder.read(1).status());
        assertEquals(KeyDecoder.ReadStatus.IDLE, decoder.read(1).status());
        clock.set(500_000_000L);
        assertEquals(KeyStroke.of(KeyStroke.Key.F1), event(decoder));
    }

    @Test
    void incompletePrefixAtEndOfInputPreservesBothDecodedInputAndEnd() throws Exception {
        QueueReader reader = new QueueReader();
        TerminalKeyMap keys = TerminalKeyMap.fromCapabilities(cap -> cap == Capability.key_f1 ? "\\Ebxyz" : null);
        KeyDecoder decoder = new KeyDecoder(reader, false, keys);
        reader.add("\u001Bbx");
        reader.input.add(-1);
        assertEquals(KeyStroke.of(KeyStroke.Key.B, KeyStroke.Modifier.ALT), event(decoder));
        assertEquals(new TextInput("x"), event(decoder));
        assertEquals(KeyDecoder.ReadStatus.END, decoder.read(1).status());
    }

    @Test
    void nextEscapeCompletesAShorterKeyBeforeStartingTheFollowingSequence() throws Exception {
        TerminalKeyMap keys = TerminalKeyMap.fromCapabilities(cap -> switch (cap) {
            case key_f1 -> "\\Ebx";
            case key_f2 -> "\\Ebxyz";
            default -> null;
        });
        QueueReader reader = new QueueReader();
        KeyDecoder decoder = new KeyDecoder(reader, false, keys);
        reader.add("\u001Bbx\u001B[Ax");

        assertEquals(KeyStroke.of(KeyStroke.Key.F1), event(decoder));
        assertEquals(KeyStroke.of(KeyStroke.Key.ARROW_UP), event(decoder));
        assertEquals(new TextInput("x"), event(decoder));
    }

    @Test
    void controlCompletesAShorterKeyBeforeBeingDecodedNormally() throws Exception {
        TerminalKeyMap keys = TerminalKeyMap.fromCapabilities(cap -> switch (cap) {
            case key_f1 -> "\\Ebx";
            case key_f2 -> "\\Ebxyz";
            default -> null;
        });
        for (int control : new int[] {17, 19, 127}) {
            QueueReader reader = new QueueReader();
            KeyDecoder decoder = new KeyDecoder(reader, false, keys);
            reader.add("\u001Bbx");
            reader.input.addLast(control);
            reader.add("x");

            assertEquals(KeyStroke.of(KeyStroke.Key.F1), event(decoder));
            assertEquals(control == 127 ? KeyStroke.of(KeyStroke.Key.BACKSPACE)
                : KeyStroke.of(control == 17 ? KeyStroke.Key.Q : KeyStroke.Key.S, KeyStroke.Modifier.CTRL),
                event(decoder));
            assertEquals(new TextInput("x"), event(decoder));
        }
    }

    @Test
    void invalidAndPlainTextCapabilitiesCannotCaptureDocumentInput() {
        TerminalKeyMap keys = TerminalKeyMap.fromCapabilities(cap -> switch (cap) {
            case key_f1 -> "plain";
            case key_f2 -> "\\E" + "x".repeat(1000);
            case key_f3 -> "\\E";
            default -> null;
        });
        assertNull(keys.key("plain"));
        assertNull(keys.key("\u001B"));
        assertFalse(keys.hasLongerSequence("\u001B"));
    }

    @Test
    void unmatchedAltUnicodeRetainsTheOriginalCodePoint() throws Exception {
        QueueReader reader = new QueueReader();
        KeyDecoder decoder = new KeyDecoder(reader, false, linuxKeys());
        reader.add("\u001B");
        reader.input.add(0x1F642);
        assertEquals(KeyStroke.of(KeyStroke.Key.ESCAPE), event(decoder));
        assertEquals(new TextInput("🙂"), event(decoder));
    }

    private static TerminalKeyMap linuxKeys() {
        Map<Capability, String> sequences = new EnumMap<>(Capability.class);
        for (int number = 1; number <= 5; number++) {
            sequences.put(Capability.valueOf("key_f" + number), "\\E[[" + (char) ('A' + number - 1));
        }
        sequences.put(Capability.key_btab, "\\E^I");
        return TerminalKeyMap.fromCapabilities(sequences::get);
    }

    private static TuiInputEvent event(KeyDecoder decoder) throws IOException {
        KeyDecoder.ReadResult result = decoder.read(1);
        assertEquals(KeyDecoder.ReadStatus.EVENT, result.status());
        return result.event();
    }

    private static final class QueueReader extends NonBlockingReader {
        private final ArrayDeque<Integer> input = new ArrayDeque<>();

        private void add(String text) {
            text.chars().forEach(input::addLast);
        }

        @Override
        protected int read(long timeout, boolean isPeek) {
            Integer value = isPeek ? input.peekFirst() : input.pollFirst();
            return value == null ? READ_EXPIRED : value;
        }

        @Override
        public int readBuffered(char[] buffer, int offset, int length, long timeout) {
            return READ_EXPIRED;
        }

        @Override
        public void close() {}
    }
}
