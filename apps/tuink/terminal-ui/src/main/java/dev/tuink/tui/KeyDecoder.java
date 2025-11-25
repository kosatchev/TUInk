package dev.tuink.tui;

import org.jline.utils.NonBlockingReader;
import java.io.IOException;

/**
 * Minimal key decoder for raw terminal input.
 */
public class KeyDecoder {
    private final NonBlockingReader reader;
    private boolean lastWasCarriageReturn;

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
            case 3 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_C);
            case 5 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_E);
            case 17 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_Q);
            case 19 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_S);
            case 26 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_Z);
            case 25 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_Y);
            case 24 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_X);
            case 22 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_V);
            case 11 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_K);
            case 23 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_W);
            case 15 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_O);
            case 6 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_F);
            case 18 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_R);
            case 1 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_A);
            case 7 -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_G);
            case 9 -> new KeyEvent.ActionEvent(KeyEvent.Action.TAB);
            case 127, 8 -> new KeyEvent.ActionEvent(KeyEvent.Action.BACKSPACE);
            case 13 -> {
                lastWasCarriageReturn = true;
                yield new KeyEvent.ActionEvent(KeyEvent.Action.ENTER);
            }
            case 10 -> {
                if (lastWasCarriageReturn) {
                    lastWasCarriageReturn = false;
                    yield null;
                }
                yield new KeyEvent.ActionEvent(KeyEvent.Action.ENTER);
            }
            default -> {
                lastWasCarriageReturn = false;
                if (isPrintable(ch)) {
                    yield new KeyEvent.CharInput((char) ch);
                }
                yield new KeyEvent.ActionEvent(KeyEvent.Action.UNKNOWN);
            }
        };
    }

    private boolean isPrintable(int ch) {
        return ch >= 0 && !Character.isISOControl(ch);
    }

    private KeyEvent decodeEscapeSequence() throws IOException {
        int second = reader.read(5);
        if (second == NonBlockingReader.READ_EXPIRED || second < 0) {
            return new KeyEvent.ActionEvent(KeyEvent.Action.ESCAPE);
        }
        if (second == 'O') {
            int third = reader.read(5);
            if (third == 'Q') {
                return new KeyEvent.ActionEvent(KeyEvent.Action.F2);
            }
            return new KeyEvent.ActionEvent(KeyEvent.Action.UNKNOWN);
        }
        if (second != '[') {
            return new KeyEvent.ActionEvent(KeyEvent.Action.ESCAPE);
        }
        StringBuilder seq = new StringBuilder();
        while (true) {
            int next = reader.read(5);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                break;
            }
            char c = (char) next;
            seq.append(c);
            if ((c >= 'A' && c <= 'Z') || c == '~' || c == 'm') {
                break;
            }
            if (seq.length() > 32) {
                break;
            }
        }
        return mapCsi(seq.toString());
    }

    private KeyEvent mapCsi(String seq) {
        if (seq.startsWith("<")) {
            KeyEvent mouse = parseSgrMouse(seq);
            if (mouse != null) {
                return mouse;
            }
        }
        return switch (seq) {
            case "A" -> new KeyEvent.ActionEvent(KeyEvent.Action.ARROW_UP);
            case "B" -> new KeyEvent.ActionEvent(KeyEvent.Action.ARROW_DOWN);
            case "C" -> new KeyEvent.ActionEvent(KeyEvent.Action.ARROW_RIGHT);
            case "D" -> new KeyEvent.ActionEvent(KeyEvent.Action.ARROW_LEFT);
            case "H", "1~", "7~" -> new KeyEvent.ActionEvent(KeyEvent.Action.HOME);
            case "F", "4~", "8~" -> new KeyEvent.ActionEvent(KeyEvent.Action.END);
            case "3~" -> new KeyEvent.ActionEvent(KeyEvent.Action.DELETE);
            case "5~" -> new KeyEvent.ActionEvent(KeyEvent.Action.PAGE_UP);
            case "6~" -> new KeyEvent.ActionEvent(KeyEvent.Action.PAGE_DOWN);
            case "21~" -> new KeyEvent.ActionEvent(KeyEvent.Action.F10);
            case "12~" -> new KeyEvent.ActionEvent(KeyEvent.Action.F2);
            case "1;6S", "83;6u", "27;6;83~" -> new KeyEvent.ActionEvent(KeyEvent.Action.CTRL_SHIFT_S);
            default -> mapModifiedArrows(seq);
        };
    }

    private KeyEvent parseSgrMouse(String seq) {
        // CSI <Cb;Cx;Cy(M|m)
        if (seq.length() < 4) {
            return null;
        }
        char suffix = seq.charAt(seq.length() - 1);
        if (suffix != 'M' && suffix != 'm') {
            return null;
        }
        String payload = seq.substring(1, seq.length() - 1);
        String[] parts = payload.split(";");
        if (parts.length != 3) {
            return null;
        }
        try {
            int cb = Integer.parseInt(parts[0]);
            int col = Integer.parseInt(parts[1]) - 1;
            int row = Integer.parseInt(parts[2]) - 1;
            if (row < 0 || col < 0) {
                return null;
            }
            return mapMouse(cb, row, col, suffix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private KeyEvent mapMouse(int cb, int row, int col, char suffix) {
        boolean wheel = (cb & 64) != 0;
        boolean drag = (cb & 32) != 0;
        int base = cb & 3;

        if (wheel) {
            return new KeyEvent.MouseEvent(
                row,
                col,
                base == 0 ? KeyEvent.MouseKind.SCROLL_UP : KeyEvent.MouseKind.SCROLL_DOWN,
                KeyEvent.MouseButton.NONE
            );
        }

        KeyEvent.MouseButton button = switch (base) {
            case 0 -> KeyEvent.MouseButton.LEFT;
            case 1 -> KeyEvent.MouseButton.MIDDLE;
            case 2 -> KeyEvent.MouseButton.RIGHT;
            default -> KeyEvent.MouseButton.NONE;
        };
        KeyEvent.MouseKind kind = switch (suffix) {
            case 'm' -> KeyEvent.MouseKind.RELEASE;
            default -> drag ? KeyEvent.MouseKind.DRAG : KeyEvent.MouseKind.PRESS;
        };
        return new KeyEvent.MouseEvent(row, col, kind, button);
    }

    private KeyEvent mapModifiedArrows(String seq) {
        boolean shift = seq.contains(";2");
        if (seq.endsWith("A")) {
            return new KeyEvent.ActionEvent(shift ? KeyEvent.Action.SHIFT_ARROW_UP : KeyEvent.Action.ARROW_UP);
        }
        if (seq.endsWith("B")) {
            return new KeyEvent.ActionEvent(shift ? KeyEvent.Action.SHIFT_ARROW_DOWN : KeyEvent.Action.ARROW_DOWN);
        }
        if (seq.endsWith("C")) {
            return new KeyEvent.ActionEvent(shift ? KeyEvent.Action.SHIFT_ARROW_RIGHT : KeyEvent.Action.ARROW_RIGHT);
        }
        if (seq.endsWith("D")) {
            return new KeyEvent.ActionEvent(shift ? KeyEvent.Action.SHIFT_ARROW_LEFT : KeyEvent.Action.ARROW_LEFT);
        }
        if (seq.endsWith("H")) {
            return new KeyEvent.ActionEvent(KeyEvent.Action.HOME);
        }
        if (seq.endsWith("F")) {
            return new KeyEvent.ActionEvent(KeyEvent.Action.END);
        }
        return new KeyEvent.ActionEvent(KeyEvent.Action.UNKNOWN);
    }
}
