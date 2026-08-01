package dev.tuink.tui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tuink.terminal.input.KeyStroke;
import dev.tuink.terminal.input.TextInput;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

class KeyDecoderUnicodeTest {
    @Test
    void combinesSurrogatePairIntoOneUnicodeScalarEvent() throws IOException {
        KeyDecoder decoder = new KeyDecoder(new QueueReader('\uD83D', '\uDE00'));

        TextInput input = assertInstanceOf(TextInput.class, decoder.read(1).event());

        assertEquals("😀", input.text());
    }

    @Test
    void isolatedSurrogatesBecomeReplacementCharacters() throws IOException {
        KeyDecoder high = new KeyDecoder(new QueueReader('\uD83D'));
        KeyDecoder low = new KeyDecoder(new QueueReader('\uDE00'));

        assertEquals("�", assertInstanceOf(TextInput.class, high.read(1).event()).text());
        assertEquals("�", assertInstanceOf(TextInput.class, low.read(1).event()).text());
    }

    @Test
    void escapeBeforeUnmappableUnicodeInputDoesNotConsumeThatInput() throws IOException {
        KeyDecoder decoder = new KeyDecoder(new QueueReader(27, '中'));

        KeyStroke escape = assertInstanceOf(KeyStroke.class, decoder.read(1).event());
        TextInput input = assertInstanceOf(TextInput.class, decoder.read(1).event());

        assertEquals(KeyStroke.of(KeyStroke.Key.ESCAPE), escape);
        assertEquals("中", input.text());
    }

    @Test
    void ctrlSlashRemainsAPhysicalChord() throws IOException {
        KeyDecoder decoder = new KeyDecoder(new QueueReader(31));

        KeyStroke event = assertInstanceOf(KeyStroke.class, decoder.read(1).event());

        assertEquals(KeyStroke.of(KeyStroke.Key.SLASH, KeyStroke.Modifier.CTRL), event);
    }

    @Test
    void enhancedKeyboardCtrlSlashRemainsAPhysicalChord() throws IOException {
        KeyDecoder decoder = new KeyDecoder(new QueueReader(27, '[', '4', '7', ';', '5', 'u'));

        KeyStroke event = assertInstanceOf(KeyStroke.class, decoder.read(1).event());

        assertEquals(KeyStroke.of(KeyStroke.Key.SLASH, KeyStroke.Modifier.CTRL), event);
    }

    @Test
    void distinguishesIdleReadsFromEndOfInput() throws IOException {
        KeyDecoder idleDecoder = new KeyDecoder(new QueueReader());
        KeyDecoder endDecoder = new KeyDecoder(new QueueReader(-1));

        KeyDecoder.ReadResult idle = idleDecoder.read(1);
        KeyDecoder.ReadResult end = endDecoder.read(1);

        assertEquals(KeyDecoder.ReadStatus.IDLE, idle.status());
        assertNull(idle.event());
        assertEquals(KeyDecoder.ReadStatus.END, end.status());
        assertNull(end.event());
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
