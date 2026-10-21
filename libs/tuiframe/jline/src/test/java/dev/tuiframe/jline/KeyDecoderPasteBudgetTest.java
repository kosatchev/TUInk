package dev.tuiframe.jline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.TextInput;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

class KeyDecoderPasteBudgetTest {
    private static final String PASTE_START = "\u001B[200~";
    private static final String PASTE_END = "\u001B[201~";

    @Test
    void continuousPasteYieldsWithoutPublishingPartialTextOrLosingUnicode() throws IOException {
        String text = "x".repeat(65_535) + "😀\nΩ";
        ContinuousReader reader = new ContinuousReader(PASTE_START + text + PASTE_END + "z");
        KeyDecoder decoder = new KeyDecoder(reader, false, TerminalKeyMap.EMPTY, () -> 0L);

        KeyDecoder.ReadResult first = decoder.read(1);

        assertEquals(KeyDecoder.ReadStatus.IDLE, first.status());
        assertNull(first.event());
        assertTrue(reader.position < PASTE_START.length() + text.length());
        PasteEvent paste = awaitPaste(decoder);
        assertEquals(text, paste.text());
        assertFalse(paste.truncated());
        assertEquals(new TextInput("z"), decoder.read(1).event());
        assertEquals(KeyDecoder.ReadStatus.IDLE, decoder.read(1).status());
    }

    @Test
    void continuousOversizedPasteYieldsWhileDrainingAndDoesNotRetainHalfASurrogate() throws IOException {
        String text = "abcd😀" + "x".repeat(65_536);
        ContinuousReader reader = new ContinuousReader(PASTE_START + text + PASTE_END + "z");
        KeyDecoder decoder = new KeyDecoder(reader, 5);

        assertEquals(KeyDecoder.ReadStatus.IDLE, decoder.read(1).status());

        PasteEvent paste = awaitPaste(decoder);
        assertEquals("abcd", paste.text());
        assertTrue(paste.truncated());
        assertEquals(new TextInput("z"), decoder.read(1).event());
    }

    @Test
    void aContinuousSlowReaderYieldsAtTheDeadlineAndPreservesSplitTerminator() throws IOException {
        AtomicLong clock = new AtomicLong();
        String text = "a😀b\nz";
        ContinuousReader reader = new ContinuousReader(PASTE_START + text + PASTE_END + "z", clock);
        KeyDecoder decoder = new KeyDecoder(reader, false, TerminalKeyMap.EMPTY, clock::get);

        assertEquals(KeyDecoder.ReadStatus.IDLE, decoder.read(1).status());
        int idlePolls = 0;
        PasteEvent paste = null;
        for (int polls = 0; polls < 32 && paste == null; polls++) {
            long before = clock.get();
            KeyDecoder.ReadResult result = decoder.read(1);
            assertTrue(clock.get() - before <= 1_000_000L);
            if (result.status() == KeyDecoder.ReadStatus.IDLE) {
                assertNull(result.event());
                idlePolls++;
            } else {
                paste = assertInstanceOf(PasteEvent.class, result.event());
            }
        }

        assertTrue(idlePolls >= PASTE_END.length() - 1);
        assertEquals(new PasteEvent(text), paste);
        assertEquals(new TextInput("z"), decoder.read(1).event());
    }

    @Test
    void eofAfterSeveralSlicesFlushesOnePasteBeforeEndOfInput() throws IOException {
        String text = "x".repeat(65_535) + "😀";
        ContinuousReader reader = new ContinuousReader(PASTE_START + text);
        reader.endOfInput = true;
        KeyDecoder decoder = new KeyDecoder(reader, false, TerminalKeyMap.EMPTY, () -> 0L);

        assertEquals(KeyDecoder.ReadStatus.IDLE, decoder.read(1).status());

        assertEquals(new PasteEvent(text), awaitPaste(decoder));
        assertEquals(KeyDecoder.ReadStatus.END, decoder.read(1).status());
    }

    private static PasteEvent awaitPaste(KeyDecoder decoder) throws IOException {
        for (int polls = 0; polls < 1000; polls++) {
            KeyDecoder.ReadResult result = decoder.read(1);
            if (result.status() != KeyDecoder.ReadStatus.IDLE) {
                return assertInstanceOf(PasteEvent.class, result.event());
            }
            assertNull(result.event());
        }
        throw new AssertionError("Paste did not finish");
    }

    /** Has no idle gaps while text remains; optionally advances time for every read. */
    private static final class ContinuousReader extends NonBlockingReader {
        private final String input;
        private final AtomicLong clock;
        private int position;
        private boolean endOfInput;

        private ContinuousReader(String input) {
            this(input, null);
        }

        private ContinuousReader(String input, AtomicLong clock) {
            this.input = input;
            this.clock = clock;
        }

        @Override
        protected int read(long timeout, boolean isPeek) {
            assertTrue(timeout > 0L && timeout <= 5L);
            if (position == input.length()) {
                return endOfInput ? -1 : READ_EXPIRED;
            }
            int value = input.charAt(position);
            if (!isPeek) {
                position++;
                if (clock != null) clock.addAndGet(1_000_000L);
            }
            return value;
        }

        @Override
        public int readBuffered(char[] buffer, int offset, int length, long timeout) {
            if (length <= 0) return 0;
            int count = Math.min(length, input.length() - position);
            if (count == 0) return endOfInput ? -1 : READ_EXPIRED;
            input.getChars(position, position + count, buffer, offset);
            position += count;
            return count;
        }

        @Override
        public void close() {
        }
    }
}
