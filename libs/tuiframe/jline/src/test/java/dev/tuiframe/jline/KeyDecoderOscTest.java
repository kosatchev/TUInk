package dev.tuiframe.jline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

class KeyDecoderOscTest {
    @Test
    void consumesBelAndStRepliesWithoutTurningThemIntoInput() throws IOException {
        Fixture fixture = new Fixture();
        fixture.reader.add("a\u001B]10;rgb:ffff/aaaa/0000\u0007\u001B]4;7;rgb:1111/2222/3333\u001B\\z");

        fixture.event(new TextInput("a"));
        fixture.idle();
        fixture.idle();
        fixture.event(new TextInput("z"));
        assertEquals(List.of("10;rgb:ffff/aaaa/0000", "4;7;rgb:1111/2222/3333"), fixture.responses);
    }

    @Test
    void fragmentedBatchedPaletteReplySurvivesLongGapsAndYieldsBoundedWork() throws IOException {
        Fixture fixture = new Fixture();
        StringBuilder payload = new StringBuilder("4");
        for (int color = 0; color < 16; color++) payload.append(';').append(color).append(";rgb:1111/2222/3333");
        fixture.reader.add("\u001B]4;");
        fixture.idle();
        fixture.clock.set(10_000_000_000L);
        fixture.idle();
        fixture.reader.add(payload.substring(2) + "\u001B");
        while (!fixture.reader.input.isEmpty()) {
            int beforeReads = fixture.reader.readCount;
            fixture.idle();
            assertTrue(fixture.reader.readCount - beforeReads <= 128);
        }
        fixture.clock.set(10_100_000_000L);
        fixture.idle();
        fixture.reader.add("\\");
        fixture.idle();

        assertEquals(List.of(payload.toString()), fixture.responses);
    }

    @Test
    void acceptedReplyCanArriveOneCodeUnitAtATime() throws IOException {
        Fixture fixture = new Fixture();
        for (char codeUnit : "\u001B]10;rgb:ab/cd/ef\u0007".toCharArray()) {
            fixture.reader.add(String.valueOf(codeUnit));
            fixture.idle();
        }
        assertEquals(List.of("10;rgb:ab/cd/ef"), fixture.responses);
    }

    @Test
    void standaloneDecoderPreservesAltRightBracketImmediately() throws IOException {
        QueueReader reader = new QueueReader();
        reader.add("\u001B]10;x");
        KeyDecoder decoder = new KeyDecoder(reader, false, TerminalKeyMap.EMPTY);

        assertEquals(KeyStroke.of(KeyStroke.Key.RIGHT_BRACKET, KeyStroke.Modifier.ALT), decoder.read(50).event());
        assertEquals(new TextInput("1"), decoder.read(50).event());
    }

    @Test
    void unrecognizedOrExpiredPrefixesReplayOrdinaryAltInput() throws IOException {
        Fixture unrecognized = new Fixture();
        unrecognized.reader.add("\u001B]xy");
        unrecognized.event(KeyStroke.of(KeyStroke.Key.RIGHT_BRACKET, KeyStroke.Modifier.ALT));
        unrecognized.event(new TextInput("x"));
        unrecognized.event(new TextInput("y"));

        Fixture expired = new Fixture();
        expired.reader.add("\u001B]1");
        expired.idle();
        expired.clock.set(500_000_000L);
        expired.event(KeyStroke.of(KeyStroke.Key.RIGHT_BRACKET, KeyStroke.Modifier.ALT));
        expired.event(new TextInput("1"));
        assertTrue(expired.responses.isEmpty());
    }

    @Test
    void eofReplaysUnclaimedPrefixButDiscardsAcceptedReply() throws IOException {
        Fixture prefix = new Fixture();
        prefix.reader.add("\u001B]1");
        prefix.reader.input.addLast(-1);
        prefix.event(KeyStroke.of(KeyStroke.Key.RIGHT_BRACKET, KeyStroke.Modifier.ALT));
        prefix.event(new TextInput("1"));
        assertEquals(KeyDecoder.ReadStatus.END, prefix.decoder.read(50).status());

        Fixture accepted = new Fixture();
        accepted.reader.add("\u001B]10;rgb:ab");
        accepted.reader.input.addLast(-1);
        accepted.idle();
        assertEquals(KeyDecoder.ReadStatus.END, accepted.decoder.read(50).status());
        assertTrue(accepted.responses.isEmpty());
    }

    @Test
    void pasteKeepsOscLookingTextAndDoesNotChangePalette() throws IOException {
        Fixture fixture = new Fixture();
        String payload = "\u001B]10;rgb:ffff/0000/0000\u0007";
        fixture.reader.add("\u001B[200~" + payload + "\u001B[201~");

        fixture.event(new PasteEvent(payload));
        assertTrue(fixture.responses.isEmpty());
    }

    @Test
    void oversizedResponseIsDiscardedWithBoundedPollWork() throws IOException {
        Fixture fixture = new Fixture();
        fixture.reader.add("\u001B]10;" + "x".repeat(4000) + "\u0007z");
        int polls = 0;
        while (fixture.reader.input.size() > 1) {
            int beforeReads = fixture.reader.readCount;
            fixture.idle();
            assertTrue(fixture.reader.readCount - beforeReads <= 129);
            assertTrue(++polls < 40);
        }
        fixture.event(new TextInput("z"));
        assertTrue(fixture.responses.isEmpty());
    }

    @Test
    void controlsAndFreshEscapeRecoverAnUnterminatedResponse() throws IOException {
        for (int payloadLength : new int[] {10, 1500}) {
            for (String recovery : List.of("\u0011z", "\u001B\u0011z")) {
                Fixture fixture = new Fixture();
                fixture.reader.add("\u001B]10;" + "x".repeat(payloadLength));
                while (!fixture.reader.input.isEmpty()) fixture.idle();
                fixture.reader.add(recovery);
                fixture.event(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL));
                fixture.event(new TextInput("z"));
                assertTrue(fixture.responses.isEmpty());
            }
        }
        Fixture escaped = new Fixture();
        escaped.reader.add("\u001B]4;7;malformed\u001B[Az");
        escaped.event(KeyStroke.of(KeyStroke.Key.ARROW_UP));
        escaped.event(new TextInput("z"));
        assertTrue(escaped.responses.isEmpty());
    }

    @Test
    void standaloneEscapeRecoversUnterminatedAndOversizedResponsesAfterNormalAmbiguityWindow() throws IOException {
        for (int payloadLength : new int[] {10, 1500}) {
            Fixture fixture = new Fixture();
            fixture.reader.add("\u001B]10;" + "x".repeat(payloadLength));
            while (!fixture.reader.input.isEmpty()) fixture.idle();
            fixture.clock.set(10_000_000_000L);
            fixture.idle();
            fixture.reader.add("\u001B");
            fixture.idle();
            fixture.clock.set(10_499_000_000L);
            fixture.idle();
            fixture.clock.set(10_500_000_000L);
            fixture.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            fixture.reader.add("z");
            fixture.event(new TextInput("z"));
            assertTrue(fixture.responses.isEmpty());
        }
    }

    @Test
    void recognizedMalformedPayloadIsOfferedForValidationWithoutLeakingText() throws IOException {
        Fixture fixture = new Fixture();
        fixture.reader.add("\u001B]10;not-a-color\u0007z");
        fixture.idle();
        fixture.event(new TextInput("z"));
        assertEquals(List.of("10;not-a-color"), fixture.responses);
    }

    private static final class Fixture {
        private final QueueReader reader = new QueueReader();
        private final AtomicLong clock = new AtomicLong();
        private final List<String> responses = new ArrayList<>();
        private final KeyDecoder decoder = new KeyDecoder(reader, false, TerminalKeyMap.EMPTY, clock::get, responses::add);

        private void idle() throws IOException {
            assertEquals(KeyDecoder.ReadStatus.IDLE, decoder.read(50).status());
        }

        private void event(TuiInputEvent expected) throws IOException {
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
            assertTrue(timeout > 0 && timeout <= 50);
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
