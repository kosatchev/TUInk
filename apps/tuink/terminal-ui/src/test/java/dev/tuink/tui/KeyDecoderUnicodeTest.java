package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

class KeyDecoderUnicodeTest {
    @Test
    void combinesSurrogatePairIntoOneUnicodeScalarEvent() throws IOException {
        KeyDecoder decoder = new KeyDecoder(new QueueReader('\uD83D', '\uDE00'));

        KeyEvent.CharInput input = assertInstanceOf(KeyEvent.CharInput.class, decoder.readKey(1));

        assertEquals(0x1F600, input.codePoint());
        assertEquals("😀", input.text());
        assertEquals('\0', input.asciiCharOrZero());
    }

    @Test
    void isolatedSurrogatesBecomeReplacementCharacters() throws IOException {
        KeyDecoder high = new KeyDecoder(new QueueReader('\uD83D'));
        KeyDecoder low = new KeyDecoder(new QueueReader('\uDE00'));

        assertEquals("�", assertInstanceOf(KeyEvent.CharInput.class, high.readKey(1)).text());
        assertEquals("�", assertInstanceOf(KeyEvent.CharInput.class, low.readKey(1)).text());
    }

    @Test
    void escapeBeforeUnknownPrintableInputDoesNotConsumeThatInput() throws IOException {
        KeyDecoder decoder = new KeyDecoder(new QueueReader(27, 'z'));

        KeyEvent.ActionEvent escape = assertInstanceOf(KeyEvent.ActionEvent.class, decoder.readKey(1));
        KeyEvent.CharInput input = assertInstanceOf(KeyEvent.CharInput.class, decoder.readKey(1));

        assertEquals(KeyEvent.Action.ESCAPE, escape.action());
        assertEquals("z", input.text());
    }

    @Test
    void charInputRejectsControlsAndSurrogateCodePoints() {
        assertThrows(IllegalArgumentException.class, () -> new KeyEvent.CharInput(0x1B));
        assertThrows(IllegalArgumentException.class, () -> new KeyEvent.CharInput(0x9B));
        assertThrows(IllegalArgumentException.class, () -> new KeyEvent.CharInput(0xD83D));
    }

    private static final class QueueReader extends NonBlockingReader {
        private final Queue<Integer> values = new ArrayDeque<>();

        private QueueReader(int... values) {
            for (int value : values) {
                this.values.add(value);
            }
        }

        @Override
        protected int read(long timeout, boolean isPeek) {
            Integer value = values.peek();
            if (value == null) {
                return READ_EXPIRED;
            }
            if (!isPeek) {
                values.remove();
            }
            return value;
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
