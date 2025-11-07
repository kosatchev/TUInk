package dev.tuink.tui;

import org.jline.utils.NonBlockingReader;
import java.io.IOException;

/**
 * Minimal key decoder for raw terminal input.
 */
public class KeyDecoder {
    private final NonBlockingReader reader;

    public KeyDecoder(NonBlockingReader reader) {
        this.reader = reader;
    }

    public KeyEvent readKey(long timeoutMs) throws IOException {
        int ch = reader.read(timeoutMs);
        if (ch == NonBlockingReader.READ_EXPIRED || ch < 0) {
            return null;
        }

        if (ch == 27) {
            return decodeEscapeSequence();
        }

        return switch (ch) {
            case 17 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_Q);
            case 19 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_S);
            case 6 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_F);
            case 7 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_G);
            case 127, 8 -> new KeyEvent.ActionEvent(KeyEvent.Action.BACKSPACE);
            case 10, 13 -> new KeyEvent.ActionEvent(KeyEvent.Action.ENTER);
            default -> {
                if (ch >= 32 && ch <= 126) {
                    yield new KeyEvent.CharInput((char) ch);
                }
                yield new KeyEvent.ActionEvent(KeyEvent.Action.UNKNOWN);
            }
        };
    }

    private KeyEvent decodeEscapeSequence() throws IOException {
        int second = reader.read(5);
        if (second != '[') {
            return new KeyEvent.ActionEvent(KeyEvent.Action.UNKNOWN);
        }

        int third = reader.read(5);
        return switch (third) {
            case 'A' -> new KeyEvent.ActionEvent(KeyEvent.Action.ARROW_UP);
            case 'B' -> new KeyEvent.ActionEvent(KeyEvent.Action.ARROW_DOWN);
            case 'C' -> new KeyEvent.ActionEvent(KeyEvent.Action.ARROW_RIGHT);
            case 'D' -> new KeyEvent.ActionEvent(KeyEvent.Action.ARROW_LEFT);
            case 'H' -> new KeyEvent.ActionEvent(KeyEvent.Action.HOME);
            case 'F' -> new KeyEvent.ActionEvent(KeyEvent.Action.END);
            case '1', '3', '4', '5', '6', '7', '8' -> decodeTildeSequence((char) third);
            default -> new KeyEvent.ActionEvent(KeyEvent.Action.UNKNOWN);
        };
    }

    private KeyEvent decodeTildeSequence(char first) throws IOException {
        char code = first;
        int next = reader.read(5);
        if (Character.isDigit(next)) {
            code = (char) next;
            next = reader.read(5);
        }
        if (next != '~') {
            return new KeyEvent.ActionEvent(KeyEvent.Action.UNKNOWN);
        }

        return switch (code) {
            case '1', '7' -> new KeyEvent.ActionEvent(KeyEvent.Action.HOME);
            case '4', '8' -> new KeyEvent.ActionEvent(KeyEvent.Action.END);
            case '3' -> new KeyEvent.ActionEvent(KeyEvent.Action.DELETE);
            case '5' -> new KeyEvent.ActionEvent(KeyEvent.Action.PAGE_UP);
            case '6' -> new KeyEvent.ActionEvent(KeyEvent.Action.PAGE_DOWN);
            default -> new KeyEvent.ActionEvent(KeyEvent.Action.UNKNOWN);
        };
    }
}
