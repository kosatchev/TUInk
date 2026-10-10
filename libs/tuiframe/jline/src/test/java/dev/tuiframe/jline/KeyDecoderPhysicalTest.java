package dev.tuiframe.jline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

class KeyDecoderPhysicalTest {
    @Test
    void decodesAllControlLettersWithoutAssigningCommands() throws IOException {
        for (int code = 1; code <= 26; code++) {
            KeyDecoder decoder = new KeyDecoder(new QueueReader(code));
            KeyStroke stroke = assertInstanceOf(KeyStroke.class, event(decoder));
            if (code == '\t') {
                assertEquals(KeyStroke.of(KeyStroke.Key.TAB), stroke);
            } else if (code == '\n' || code == '\r') {
                assertEquals(KeyStroke.of(KeyStroke.Key.ENTER), stroke);
            } else {
                KeyStroke.Key key = KeyStroke.Key.valueOf(String.valueOf((char) ('A' + code - 1)));
                assertEquals(KeyStroke.of(key, KeyStroke.Modifier.CTRL), stroke);
            }
        }
    }

    @Test
    void decodesRemainingC0AndEditingKeysPhysically() throws IOException {
        assertEquals(KeyStroke.of(KeyStroke.Key.SPACE, KeyStroke.Modifier.CTRL), event(new KeyDecoder(new QueueReader(0))));
        assertEquals(KeyStroke.of(KeyStroke.Key.BACKSLASH, KeyStroke.Modifier.CTRL), event(new KeyDecoder(new QueueReader(28))));
        assertEquals(KeyStroke.of(KeyStroke.Key.RIGHT_BRACKET, KeyStroke.Modifier.CTRL), event(new KeyDecoder(new QueueReader(29))));
        assertEquals(
            KeyStroke.of(KeyStroke.Key.DIGIT_6, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT),
            event(new KeyDecoder(new QueueReader(30)))
        );
        assertEquals(KeyStroke.of(KeyStroke.Key.SLASH, KeyStroke.Modifier.CTRL), event(new KeyDecoder(new QueueReader(31))));
        assertEquals(KeyStroke.of(KeyStroke.Key.BACKSPACE), event(new KeyDecoder(new QueueReader(127))));
        assertEquals(KeyStroke.of(KeyStroke.Key.UNKNOWN), event(new KeyDecoder(new QueueReader(0x9B))));
    }

    @Test
    void distinguishesCtrlHFromBackspaceUsingTheSessionConvention() throws IOException {
        KeyDecoder ctrlHBackspace = new KeyDecoder(new QueueReader('\b', 127), true);
        assertEquals(KeyStroke.of(KeyStroke.Key.BACKSPACE), event(ctrlHBackspace));
        assertEquals(KeyStroke.of(KeyStroke.Key.BACKSPACE), event(ctrlHBackspace));

        KeyDecoder delBackspace = new KeyDecoder(new QueueReader('\b', 127), false);
        assertEquals(KeyStroke.of(KeyStroke.Key.H, KeyStroke.Modifier.CTRL), event(delBackspace));
        assertEquals(KeyStroke.of(KeyStroke.Key.BACKSPACE), event(delBackspace));
    }

    @Test
    void ctrlHBackspaceConventionAlsoHandlesAltBackspaceButKeepsExplicitCtrlH() throws IOException {
        QueueReader reader = new QueueReader(27, '\b');
        reader.add("\u001B[104;5u");
        KeyDecoder decoder = new KeyDecoder(reader, true);

        assertEquals(KeyStroke.of(KeyStroke.Key.BACKSPACE, KeyStroke.Modifier.ALT), event(decoder));
        assertEquals(KeyStroke.of(KeyStroke.Key.H, KeyStroke.Modifier.CTRL), event(decoder));
    }

    @Test
    void readResultEnforcesStatusAndEventInvariant() {
        assertThrows(IllegalArgumentException.class, () -> new KeyDecoder.ReadResult(null, null));
        assertThrows(
            IllegalArgumentException.class,
            () -> new KeyDecoder.ReadResult(KeyDecoder.ReadStatus.EVENT, null)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new KeyDecoder.ReadResult(KeyDecoder.ReadStatus.IDLE, new TextInput("x"))
        );
        assertThrows(IllegalArgumentException.class, () -> new KeyDecoder(null));
    }

    @Test
    void collapsesCrLfButKeepsStandaloneLfAsEnter() throws IOException {
        KeyDecoder crlf = decoder("\r\na");
        KeyDecoder lf = decoder("\n");

        assertEquals(KeyStroke.of(KeyStroke.Key.ENTER), event(crlf));
        assertEquals(KeyDecoder.ReadStatus.IDLE, crlf.read(1).status());
        assertEquals(new TextInput("a"), event(crlf));
        assertEquals(KeyStroke.of(KeyStroke.Key.ENTER), event(lf));
    }

    @Test
    void delaysStandaloneEscapeForOneIdlePoll() throws IOException {
        KeyDecoder decoder = new KeyDecoder(new QueueReader(27));

        KeyDecoder.ReadResult first = decoder.read(1);

        assertEquals(KeyDecoder.ReadStatus.IDLE, first.status());
        assertNull(first.event());
        assertEquals(KeyStroke.of(KeyStroke.Key.ESCAPE), event(decoder));
    }

    @Test
    void capsPendingEscapeWaitInsteadOfInheritingTheApplicationPollTimeout() throws IOException {
        QueueReader reader = new QueueReader(27);
        KeyDecoder decoder = new KeyDecoder(reader);

        assertEquals(KeyDecoder.ReadStatus.IDLE, decoder.read(50).status());
        assertEquals(KeyStroke.of(KeyStroke.Key.ESCAPE), decoder.read(50).event());

        assertEquals(List.of(50L, 5L, 5L), reader.requestedTimeouts());

        QueueReader expiredDeadlineReader = new QueueReader(27);
        KeyDecoder expiredDeadlineDecoder = new KeyDecoder(expiredDeadlineReader);
        assertEquals(KeyDecoder.ReadStatus.IDLE, expiredDeadlineDecoder.read(50).status());
        assertEquals(KeyStroke.of(KeyStroke.Key.ESCAPE), expiredDeadlineDecoder.read(0).event());
        assertEquals(List.of(50L, 5L, 5L), expiredDeadlineReader.requestedTimeouts());
    }

    @Test
    void resumesCsiAfterAnIdleReadWithoutEmittingStandaloneEscape() throws IOException {
        QueueReader reader = new QueueReader(27);
        KeyDecoder decoder = new KeyDecoder(reader);

        assertEquals(KeyDecoder.ReadStatus.IDLE, decoder.read(50).status());
        reader.add("[A");

        assertEquals(KeyStroke.of(KeyStroke.Key.ARROW_UP), event(decoder));
    }

    @Test
    void decodesSs3FunctionAndNavigationKeysPhysically() throws IOException {
        assertSequence("\u001BOP", KeyStroke.of(KeyStroke.Key.F1));
        assertSequence("\u001BOQ", KeyStroke.of(KeyStroke.Key.F2));
        assertSequence("\u001BOR", KeyStroke.of(KeyStroke.Key.F3));
        assertSequence("\u001BOS", KeyStroke.of(KeyStroke.Key.F4));
        assertSequence("\u001BOA", KeyStroke.of(KeyStroke.Key.ARROW_UP));
        assertSequence("\u001BOF", KeyStroke.of(KeyStroke.Key.END));
    }

    @Test
    void decodesCsiFunctionKeysAsF1ThroughF12() throws IOException {
        List<String> parameters = List.of("11", "12", "13", "14", "15", "17", "18", "19", "20", "21", "23", "24");
        List<KeyStroke.Key> keys = List.of(
            KeyStroke.Key.F1,
            KeyStroke.Key.F2,
            KeyStroke.Key.F3,
            KeyStroke.Key.F4,
            KeyStroke.Key.F5,
            KeyStroke.Key.F6,
            KeyStroke.Key.F7,
            KeyStroke.Key.F8,
            KeyStroke.Key.F9,
            KeyStroke.Key.F10,
            KeyStroke.Key.F11,
            KeyStroke.Key.F12
        );

        for (int i = 0; i < keys.size(); i++) {
            assertSequence("\u001B[" + parameters.get(i) + "~", KeyStroke.of(keys.get(i)));
        }
    }

    @Test
    void decodesNavigationEditingAndShiftTabKeys() throws IOException {
        assertSequence("\u001B[A", KeyStroke.of(KeyStroke.Key.ARROW_UP));
        assertSequence("\u001B[B", KeyStroke.of(KeyStroke.Key.ARROW_DOWN));
        assertSequence("\u001B[C", KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
        assertSequence("\u001B[D", KeyStroke.of(KeyStroke.Key.ARROW_LEFT));
        assertSequence("\u001B[H", KeyStroke.of(KeyStroke.Key.HOME));
        assertSequence("\u001B[F", KeyStroke.of(KeyStroke.Key.END));
        assertSequence("\u001B[2~", KeyStroke.of(KeyStroke.Key.INSERT));
        assertSequence("\u001B[3~", KeyStroke.of(KeyStroke.Key.DELETE));
        assertSequence("\u001B[5~", KeyStroke.of(KeyStroke.Key.PAGE_UP));
        assertSequence("\u001B[6~", KeyStroke.of(KeyStroke.Key.PAGE_DOWN));
        assertSequence("\u001B[Z", KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT));
    }

    @Test
    void decodesEveryXtermModifierBitWithoutCollapsingItToAnAction() throws IOException {
        for (int parameter = 1; parameter <= 16; parameter++) {
            int bits = parameter - 1;
            EnumSet<KeyStroke.Modifier> expected = EnumSet.noneOf(KeyStroke.Modifier.class);
            if ((bits & 1) != 0) {
                expected.add(KeyStroke.Modifier.SHIFT);
            }
            if ((bits & 2) != 0) {
                expected.add(KeyStroke.Modifier.ALT);
            }
            if ((bits & 4) != 0) {
                expected.add(KeyStroke.Modifier.CTRL);
            }
            if ((bits & 8) != 0) {
                expected.add(KeyStroke.Modifier.SUPER);
            }

            assertSequence(
                "\u001B[1;" + parameter + "D",
                new KeyStroke(KeyStroke.Key.ARROW_LEFT, expected)
            );
        }
    }

    @Test
    void decodesAltPrintableKeysAsPhysicalChords() throws IOException {
        assertSequence("\u001Bb", KeyStroke.of(KeyStroke.Key.B, KeyStroke.Modifier.ALT));
        assertSequence(
            "\u001BS",
            KeyStroke.of(KeyStroke.Key.S, KeyStroke.Modifier.ALT, KeyStroke.Modifier.SHIFT)
        );
        assertSequence(
            "\u001B?",
            KeyStroke.of(KeyStroke.Key.SLASH, KeyStroke.Modifier.ALT, KeyStroke.Modifier.SHIFT)
        );
    }

    @Test
    void decodesEnhancedAndModifyOtherKeysProtocols() throws IOException {
        KeyStroke ctrlShiftS = KeyStroke.of(
            KeyStroke.Key.S,
            KeyStroke.Modifier.CTRL,
            KeyStroke.Modifier.SHIFT
        );
        KeyStroke ctrlSlash = KeyStroke.of(KeyStroke.Key.SLASH, KeyStroke.Modifier.CTRL);

        assertSequence("\u001B[83;6u", ctrlShiftS);
        assertSequence("\u001B[27;6;83~", ctrlShiftS);
        assertSequence(
            "\u001B[81;6u",
            KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT)
        );
        assertSequence("\u001B[113;5u", KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL));
        assertSequence("\u001B[47;5u", ctrlSlash);
        assertSequence("\u001B[27;5;47~", ctrlSlash);
        assertSequence("\u001B[97u", new TextInput("a"));
        assertSequence("\u001B[5;5~", KeyStroke.of(KeyStroke.Key.PAGE_UP, KeyStroke.Modifier.CTRL));
        assertSequence("\u001B[6;5~", KeyStroke.of(KeyStroke.Key.PAGE_DOWN, KeyStroke.Modifier.CTRL));
        assertSequence("\u001B[1;5I", KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.CTRL));
    }

    @Test
    void limitsCsiUToTheLosslessUnnegotiatedSubset() throws IOException {
        assertSequence("\u001B[1078u", new TextInput("ж"));
        assertSequence(
            "\u001B[1078;3u",
            KeyStroke.of(KeyStroke.Key.UNKNOWN, KeyStroke.Modifier.ALT)
        );
        assertSequence("\u001B[97:65:97;6:1u", KeyStroke.of(KeyStroke.Key.UNKNOWN));
        assertSequence("\u001B[97;1;98u", KeyStroke.of(KeyStroke.Key.UNKNOWN));
        assertSequence("\u001B[115;17u", KeyStroke.of(KeyStroke.Key.UNKNOWN));
        assertSequence("\u001B[27;5;47;99~", KeyStroke.of(KeyStroke.Key.UNKNOWN));
        assertSequence("\u001B[5;5;99~", KeyStroke.of(KeyStroke.Key.UNKNOWN));
        assertSequence("\u001B[27;17;47~", KeyStroke.of(KeyStroke.Key.UNKNOWN));
    }

    @Test
    void decodesSgrPointerKindsButtonsCoordinatesAndModifiers() throws IOException {
        assertSequence(
            "\u001B[<0;4;2M",
            new PointerEvent(1, 3, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of())
        );
        assertSequence(
            "\u001B[<1;4;2m",
            new PointerEvent(1, 3, PointerEvent.Kind.RELEASE, PointerEvent.Button.MIDDLE, Set.of())
        );
        assertSequence(
            "\u001B[<50;4;2M",
            new PointerEvent(
                1,
                3,
                PointerEvent.Kind.DRAG,
                PointerEvent.Button.RIGHT,
                Set.of(KeyStroke.Modifier.CTRL)
            )
        );
        assertSequence(
            "\u001B[<35;4;2M",
            new PointerEvent(1, 3, PointerEvent.Kind.MOVE, PointerEvent.Button.NONE, Set.of())
        );
        assertSequence(
            "\u001B[<76;4;2M",
            new PointerEvent(
                1,
                3,
                PointerEvent.Kind.SCROLL_UP,
                PointerEvent.Button.NONE,
                Set.of(KeyStroke.Modifier.SHIFT, KeyStroke.Modifier.ALT)
            )
        );
        assertSequence(
            "\u001B[<65;4;2M",
            new PointerEvent(1, 3, PointerEvent.Kind.SCROLL_DOWN, PointerEvent.Button.NONE, Set.of())
        );
        assertSequence(
            "\u001B[<66;4;2M",
            new PointerEvent(1, 3, PointerEvent.Kind.SCROLL_LEFT, PointerEvent.Button.NONE, Set.of())
        );
        assertSequence(
            "\u001B[<67;4;2M",
            new PointerEvent(1, 3, PointerEvent.Kind.SCROLL_RIGHT, PointerEvent.Button.NONE, Set.of())
        );
        assertSequence(
            "\u001B[<128;4;2M",
            new PointerEvent(1, 3, PointerEvent.Kind.UNKNOWN, PointerEvent.Button.OTHER, Set.of())
        );
    }

    @Test
    void bracketedPastePreservesTextAndContinuesAcrossIdleReads() throws IOException {
        QueueReader reader = new QueueReader();
        reader.add("\u001B[200~first\n");
        KeyDecoder decoder = new KeyDecoder(reader);

        assertEquals(KeyDecoder.ReadStatus.IDLE, decoder.read(1).status());

        reader.add("second\u001B[201~");
        PasteEvent paste = assertInstanceOf(PasteEvent.class, event(decoder));
        assertEquals("first\nsecond", paste.text());
        assertFalse(paste.truncated());
    }

    @Test
    void bracketedPasteDelimitersCanBeSplitAcrossIdleReads() throws IOException {
        QueueReader reader = new QueueReader();
        KeyDecoder decoder = new KeyDecoder(reader);

        reader.add("\u001B");
        assertEquals(KeyDecoder.ReadStatus.IDLE, decoder.read(1).status());
        reader.add("[20");
        assertEquals(KeyDecoder.ReadStatus.IDLE, decoder.read(1).status());
        reader.add("0~payload\u001B[20");
        assertEquals(KeyDecoder.ReadStatus.IDLE, decoder.read(1).status());
        reader.add("1~");

        PasteEvent paste = assertInstanceOf(PasteEvent.class, event(decoder));
        assertEquals("payload", paste.text());
        assertFalse(paste.truncated());
    }

    @Test
    void oversizedPasteIsBoundedButDrainedThroughItsTerminator() throws IOException {
        QueueReader reader = new QueueReader();
        KeyDecoder decoder = new KeyDecoder(reader, 5);

        reader.add("\u001B[200~abcdefgh\u001B[20");
        assertEquals(KeyDecoder.ReadStatus.IDLE, decoder.read(1).status());
        reader.add("1~z");
        PasteEvent paste = assertInstanceOf(PasteEvent.class, event(decoder));

        assertEquals("abcde", paste.text());
        assertTrue(paste.truncated());
        assertEquals(new TextInput("z"), event(decoder));
    }

    @Test
    void pasteAtConfiguredLimitIsNotMarkedTruncated() throws IOException {
        KeyDecoder decoder = new KeyDecoder(
            new QueueReader("\u001B[200~abcde\u001B[201~".chars().toArray()),
            5
        );

        PasteEvent paste = assertInstanceOf(PasteEvent.class, event(decoder));

        assertEquals("abcde", paste.text());
        assertFalse(paste.truncated());
        assertThrows(IllegalArgumentException.class, () -> new KeyDecoder(new QueueReader(), 0));
    }

    @Test
    void bracketedPasteCanBeEmptyAndFlushesBeforeEndOfInput() throws IOException {
        assertSequence("\u001B[200~\u001B[201~", new PasteEvent(""));

        KeyDecoder decoder = new KeyDecoder(new QueueReader(concat(chars("\u001B[200~partial"), -1)));
        assertEquals(new PasteEvent("partial"), event(decoder));
        assertEquals(KeyDecoder.ReadStatus.END, decoder.read(1).status());
    }

    @Test
    void malformedSequencesBecomeUnknownPhysicalKeys() throws IOException {
        assertSequence("\u001B[not-a-sequence", KeyStroke.of(KeyStroke.Key.UNKNOWN));
        assertSequence("\u001B[<x;1;1M", KeyStroke.of(KeyStroke.Key.UNKNOWN));
        assertSequence("\u001BOx", KeyStroke.of(KeyStroke.Key.UNKNOWN));
    }

    @Test
    void highSurrogateFollowedByEndEmitsReplacementThenEnd() throws IOException {
        KeyDecoder decoder = new KeyDecoder(new QueueReader('\uD83D', -1));

        assertEquals(new TextInput("�"), event(decoder));
        assertEquals(KeyDecoder.ReadStatus.END, decoder.read(1).status());
    }

    private static void assertSequence(String sequence, TuiInputEvent expected) throws IOException {
        assertEquals(expected, event(decoder(sequence)), sequence);
    }

    private static TuiInputEvent event(KeyDecoder decoder) throws IOException {
        KeyDecoder.ReadResult result = decoder.read(1);
        assertEquals(KeyDecoder.ReadStatus.EVENT, result.status());
        return result.event();
    }

    private static KeyDecoder decoder(String sequence) {
        return new KeyDecoder(new QueueReader(chars(sequence)));
    }

    private static int[] chars(String value) {
        return value.chars().toArray();
    }

    private static int[] concat(int[] values, int last) {
        int[] result = new int[values.length + 1];
        System.arraycopy(values, 0, result, 0, values.length);
        result[values.length] = last;
        return result;
    }

    private static final class QueueReader extends NonBlockingReader {
        private final Queue<Integer> values = new ArrayDeque<>();
        private final List<Long> requestedTimeouts = new ArrayList<>();

        private QueueReader(int... values) {
            add(values);
        }

        private void add(String value) {
            add(chars(value));
        }

        private void add(int... added) {
            for (int value : added) {
                values.add(value);
            }
        }

        @Override
        protected int read(long timeout, boolean isPeek) {
            requestedTimeouts.add(timeout);
            Integer value = values.peek();
            if (value == null) {
                return READ_EXPIRED;
            }
            if (!isPeek) {
                values.remove();
            }
            return value;
        }

        private List<Long> requestedTimeouts() {
            return List.copyOf(requestedTimeouts);
        }

        @Override
        public int readBuffered(char[] buffer, int offset, int length, long timeout) {
            if (length <= 0 || values.isEmpty()) {
                return values.isEmpty() ? READ_EXPIRED : 0;
            }
            int count = 0;
            while (count < length && !values.isEmpty()) {
                buffer[offset + count] = (char) values.remove().intValue();
                count++;
            }
            return count;
        }

        @Override
        public void close() {
        }
    }
}
