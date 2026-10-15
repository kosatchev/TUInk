package dev.tuiframe.jline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

class KeyDecoderFragmentationTest {
    @Test
    void delayedArrowPrefixProducesOneArrowWithoutFirstEmittingEscape() throws IOException {
        for (TerminalKeyMap keys : new TerminalKeyMap[] {TerminalKeyMap.EMPTY, linuxKeys()}) {
            Fixture fixture = new Fixture(keys);
            fixture.reader.add("\u001B");
            fixture.idle();
            fixture.clock.set(300_000_000L);
            fixture.idleRepeatedly();
            fixture.reader.add("[Cx");

            fixture.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            fixture.event(new TextInput("x"));
        }
    }

    @Test
    void recognizedCsiAndSs3SurviveLongGapsAndRepeatedIdlePolls() throws IOException {
        for (String prefix : new String[] {"\u001B[", "\u001BO"}) {
            Fixture fixture = new Fixture(TerminalKeyMap.EMPTY);
            fixture.reader.add(prefix);
            fixture.idle();
            fixture.clock.set(10_000_000_000L);
            fixture.idleRepeatedly();
            fixture.reader.add("Cx");

            fixture.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
            fixture.event(new TextInput("x"));
        }
    }

    @Test
    void incompleteTerminfoPrefixKeepsItsMeaningAfterTheEscapeWindow() throws IOException {
        Fixture fixture = new Fixture(linuxKeys());
        fixture.reader.add("\u001B[[");
        fixture.idle();
        fixture.clock.set(10_000_000_000L);
        fixture.idleRepeatedly();
        fixture.reader.add("Ax");

        fixture.event(KeyStroke.of(KeyStroke.Key.F1));
        fixture.event(new TextInput("x"));
    }

    @Test
    void altChordsWithinTheEscapeWindowAreDeliveredImmediately() throws IOException {
        Fixture fixture = new Fixture(TerminalKeyMap.EMPTY);
        fixture.reader.add("\u001B");
        fixture.idle();
        fixture.clock.set(300_000_000L);
        fixture.reader.add("b");

        fixture.event(KeyStroke.of(KeyStroke.Key.B, KeyStroke.Modifier.ALT));
    }

    @Test
    void expiredStandaloneEscapeDoesNotConsumeTheFollowingLiteralBracket() throws IOException {
        Fixture fixture = new Fixture(linuxKeys());
        fixture.reader.add("\u001B");
        fixture.idle();
        fixture.clock.set(500_000_000L);
        fixture.reader.add("[C");

        fixture.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
        fixture.event(new TextInput("["));
        fixture.event(new TextInput("C"));
    }

    @Test
    void rawEscapeBeforeAnotherTerminalKeyDoesNotExposeThatKeysBytesAsText() throws IOException {
        for (TerminalKeyMap keys : new TerminalKeyMap[] {TerminalKeyMap.EMPTY, linuxKeys()}) {
            for (String sequence : new String[] {"\u001B[C", "\u001BOC", "\u001B[27u"}) {
                Fixture fixture = new Fixture(keys);
                fixture.reader.add("\u001B");
                fixture.idle();
                fixture.clock.set(100_000_000L);
                fixture.reader.add(sequence + "x");

                fixture.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
                fixture.event(KeyStroke.of(sequence.endsWith("u") ? KeyStroke.Key.ESCAPE : KeyStroke.Key.ARROW_RIGHT));
                fixture.event(new TextInput("x"));
            }
        }
    }

    @Test
    void consecutiveRawEscapesRemainTwoSeparateEscapeKeys() throws IOException {
        for (TerminalKeyMap keys : new TerminalKeyMap[] {TerminalKeyMap.EMPTY, linuxKeys()}) {
            Fixture fixture = new Fixture(keys);
            fixture.reader.add("\u001B");
            fixture.idle();
            fixture.clock.set(100_000_000L);
            fixture.reader.add("\u001B");
            fixture.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            fixture.idleRepeatedly();
            fixture.clock.set(599_000_000L);
            fixture.idle();
            fixture.clock.set(600_000_000L);
            fixture.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
        }
    }

    @Test
    void explicitAltEscapeRetainsItsModifier() throws IOException {
        Fixture fixture = new Fixture(linuxKeys());
        fixture.reader.add("\u001B[27;3u");

        fixture.event(KeyStroke.of(KeyStroke.Key.ESCAPE, KeyStroke.Modifier.ALT));
    }

    @Test
    void controlsCancelIncompleteSequencesAndKeepTheirOrdinaryPhysicalMeaning() throws IOException {
        TerminalKeyMap keys = TerminalKeyMap.fromCapabilities(capability -> switch (capability) {
            case key_f1 -> "\\E[[A";
            case key_f2 -> "\\Ebxyz";
            default -> null;
        });
        for (String prefix : new String[] {"\u001B[1;", "\u001BO", "\u001B[[", "\u001Bbx",
            "\u001B[" + "1".repeat(70)}) {
            for (int control = 0; control <= 0x7F; control++) {
                if (control == 27 || control > 0x1F && control != 0x7F) continue;
                Fixture ordinary = new Fixture(TerminalKeyMap.EMPTY);
                ordinary.reader.input.addLast(control);
                KeyDecoder.ReadResult expected = ordinary.decoder.read(50);
                assertEquals(KeyDecoder.ReadStatus.EVENT, expected.status());

                Fixture fixture = new Fixture(keys);
                fixture.reader.add(prefix);
                fixture.idle();
                fixture.clock.set(10_000_000_000L);
                fixture.reader.input.addLast(control);
                fixture.reader.add("x");

                fixture.event(expected.event());
                fixture.event(new TextInput("x"));
            }
        }
    }

    @Test
    void escapeCancelsIncompleteCsiSs3AndTerminfoBeforeStartingANewKey() throws IOException {
        for (String prefix : new String[] {"\u001B[1;", "\u001BO", "\u001B[["}) {
            Fixture fixture = new Fixture(linuxKeys());
            fixture.reader.add(prefix);
            fixture.idle();
            fixture.clock.set(10_000_000_000L);
            fixture.reader.add("\u001B[Ax");

            fixture.event(KeyStroke.of(KeyStroke.Key.ARROW_UP));
            fixture.event(new TextInput("x"));
        }
    }

    @Test
    void unfinishedCsiAtEndOfInputDoesNotTurnItsTailIntoDocumentText() throws IOException {
        Fixture fixture = new Fixture(TerminalKeyMap.EMPTY);
        fixture.reader.add("\u001B[12;");
        fixture.idle();
        fixture.reader.input.addLast(-1);

        fixture.event(KeyStroke.of(KeyStroke.Key.UNKNOWN));
        assertEquals(KeyDecoder.ReadStatus.END, fixture.decoder.read(50).status());
    }

    @Test
    void delayedEofStillReplaysAnUnmatchedTerminfoPrefixThroughTheGenericDecoder() throws IOException {
        TerminalKeyMap keys = TerminalKeyMap.fromCapabilities(capability -> capability == Capability.key_f1
            ? "\\Ebxyz" : null);
        Fixture fixture = new Fixture(keys);
        fixture.reader.add("\u001Bbx");
        fixture.idle();
        fixture.clock.set(10_000_000_000L);
        fixture.idleRepeatedly();
        fixture.reader.input.addLast(-1);

        fixture.event(KeyStroke.of(KeyStroke.Key.B, KeyStroke.Modifier.ALT));
        fixture.event(new TextInput("x"));
        assertEquals(KeyDecoder.ReadStatus.END, fixture.decoder.read(50).status());
    }

    @Test
    void overlongCsiIsDrainedWithBoundedPollWorkInsteadOfLeakingItsTail() throws IOException {
        Fixture fixture = new Fixture(TerminalKeyMap.EMPTY);
        fixture.reader.add("\u001B[" + "1".repeat(1000) + "Ax");

        fixture.idle();
        assertTrue(fixture.reader.readCount <= 129, "a malformed sequence must yield to the UI");
        int idlePolls = 0;
        KeyDecoder.ReadResult result;
        do {
            int previousReads = fixture.reader.readCount;
            result = fixture.decoder.read(50);
            assertTrue(fixture.reader.readCount - previousReads <= 128);
            assertTrue(++idlePolls < 20, "the final byte must end discard mode");
        } while (result.status() == KeyDecoder.ReadStatus.IDLE);
        assertEquals(KeyStroke.of(KeyStroke.Key.UNKNOWN), result.event());
        fixture.event(new TextInput("x"));
    }

    @Test
    void escapeCanCancelAnOverlongCsiThatHasNoFinalByte() throws IOException {
        Fixture fixture = new Fixture(TerminalKeyMap.EMPTY);
        fixture.reader.add("\u001B[" + "1".repeat(70));
        fixture.idle();
        fixture.reader.add("\u001B[Cx");

        fixture.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
        fixture.event(new TextInput("x"));
    }

    @Test
    void eofEndsOverlongCsiDiscardWithoutReplayingTheDiscardedBytes() throws IOException {
        Fixture fixture = new Fixture(TerminalKeyMap.EMPTY);
        fixture.reader.add("\u001B[" + "1".repeat(70));
        fixture.idle();
        fixture.clock.set(10_000_000_000L);
        fixture.idleRepeatedly();
        fixture.reader.input.addLast(-1);

        fixture.event(KeyStroke.of(KeyStroke.Key.UNKNOWN));
        assertEquals(KeyDecoder.ReadStatus.END, fixture.decoder.read(50).status());
    }

    @Test
    void pasteAndMouseProtocolsAlsoSurviveFragmentedCsiPrefixes() throws IOException {
        Fixture paste = new Fixture(linuxKeys());
        paste.reader.add("\u001B[20");
        paste.idle();
        paste.clock.set(10_000_000_000L);
        paste.idleRepeatedly();
        paste.reader.add("0~payload\u001B[201~x");
        paste.event(new PasteEvent("payload"));
        paste.event(new TextInput("x"));

        Fixture mouse = new Fixture(linuxKeys());
        mouse.reader.add("\u001B[<0;");
        mouse.idle();
        mouse.clock.set(10_000_000_000L);
        mouse.idleRepeatedly();
        mouse.reader.add("3;2Mx");
        mouse.event(new PointerEvent(1, 2, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT, Set.of()));
        mouse.event(new TextInput("x"));
    }

    private static TerminalKeyMap linuxKeys() {
        return TerminalKeyMap.fromCapabilities(capability -> capability == Capability.key_f1 ? "\\E[[A" : null);
    }

    private static final class Fixture {
        private final QueueReader reader = new QueueReader();
        private final AtomicLong clock = new AtomicLong();
        private final KeyDecoder decoder;

        private Fixture(TerminalKeyMap keys) {
            decoder = new KeyDecoder(reader, false, keys, clock::get);
        }

        private void idle() throws IOException {
            assertEquals(KeyDecoder.ReadStatus.IDLE, decoder.read(50).status());
        }

        private void idleRepeatedly() throws IOException {
            for (int poll = 0; poll < 100; poll++) idle();
        }

        private void event(dev.tuiframe.input.TuiInputEvent expected) throws IOException {
            KeyDecoder.ReadResult result = decoder.read(50);
            assertEquals(KeyDecoder.ReadStatus.EVENT, result.status());
            assertEquals(expected, result.event());
        }
    }

    private static final class QueueReader extends NonBlockingReader {
        private final ArrayDeque<Integer> input = new ArrayDeque<>();
        private int readCount;

        private void add(String text) {
            text.chars().forEach(input::addLast);
        }

        @Override
        protected int read(long timeout, boolean isPeek) {
            readCount++;
            assertTrue(timeout > 0 && timeout <= 50, "terminal polling must remain bounded");
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
