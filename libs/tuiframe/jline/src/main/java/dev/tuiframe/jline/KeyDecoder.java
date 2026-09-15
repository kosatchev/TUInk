package dev.tuiframe.jline;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;
import org.jline.utils.NonBlockingReader;

/**
 * Decodes terminal input into physical events.
 *
 * <p>Legacy CSI-u support is intentionally limited to the unnegotiated
 * {@code CSI code-point[;modifier]u} form. Extended Kitty fields are reported
 * as {@link KeyStroke.Key#UNKNOWN} until the input model can preserve their
 * additional key, event-type and text data without loss.</p>
 */
public final class KeyDecoder {
    private static final int REPLACEMENT_CHARACTER = 0xFFFD;
    private static final long SEQUENCE_TIMEOUT_MS = 5L;
    private static final int MAX_CSI_LENGTH = 64;
    private static final String BRACKETED_PASTE_END = "\u001B[201~";
    static final int DEFAULT_MAX_PASTE_CODE_UNITS = 1_048_576;

    private final NonBlockingReader reader;
    private final int maxPasteCodeUnits;
    private final StringBuilder csiSequence = new StringBuilder();
    private final StringBuilder pasteBuffer = new StringBuilder();
    private final StringBuilder pasteEndWindow = new StringBuilder(BRACKETED_PASTE_END.length());
    private boolean lastWasCarriageReturn;
    private boolean bracketedPaste;
    private boolean endPending;
    private boolean sequenceExpiryPending;
    private long pasteCodeUnitsSeen;
    private int pendingCodeUnit = -1;
    private TuiInputEvent pendingEvent;
    private EscapeState escapeState = EscapeState.NONE;

    public KeyDecoder(NonBlockingReader reader) {
        this(reader, DEFAULT_MAX_PASTE_CODE_UNITS);
    }

    KeyDecoder(NonBlockingReader reader, int maxPasteCodeUnits) {
        if (reader == null) {
            throw new IllegalArgumentException("reader must not be null");
        }
        if (maxPasteCodeUnits <= 0) {
            throw new IllegalArgumentException("maxPasteCodeUnits must be positive");
        }
        this.reader = reader;
        this.maxPasteCodeUnits = maxPasteCodeUnits;
    }

    public ReadResult read(long timeoutMs) throws IOException {
        if (pendingEvent != null) {
            TuiInputEvent event = pendingEvent;
            pendingEvent = null;
            return ReadResult.event(event);
        }
        if (endPending) {
            endPending = false;
            return ReadResult.end();
        }
        if (bracketedPaste) {
            return readBracketedPaste(timeoutMs);
        }
        if (escapeState != EscapeState.NONE) {
            TuiInputEvent event = continueEscapeSequence(sequenceContinuationTimeout(timeoutMs));
            if (event != null) {
                return ReadResult.event(event);
            }
            if (bracketedPaste) {
                return readBracketedPaste(SEQUENCE_TIMEOUT_MS);
            }
            return ReadResult.idle();
        }

        int ch = readCodeUnit(timeoutMs);
        if (ch == NonBlockingReader.READ_EXPIRED) {
            return ReadResult.idle();
        }
        if (ch < 0) {
            return ReadResult.end();
        }

        TuiInputEvent event;
        if (ch == 27) {
            beginEscapeSequence();
            event = continueEscapeSequence(SEQUENCE_TIMEOUT_MS);
        } else {
            event = decodeCodeUnit(ch);
        }
        if (event != null) {
            return ReadResult.event(event);
        }
        if (bracketedPaste) {
            return readBracketedPaste(SEQUENCE_TIMEOUT_MS);
        }
        return ReadResult.idle();
    }

    private static long sequenceContinuationTimeout(long requestedTimeoutMs) {
        // JLine treats zero as an unbounded read. Once ESC has already been
        // observed, keep disambiguation short even when the application poll
        // deadline is longer or has just expired.
        if (requestedTimeoutMs <= 0L) {
            return SEQUENCE_TIMEOUT_MS;
        }
        return Math.min(requestedTimeoutMs, SEQUENCE_TIMEOUT_MS);
    }

    private TuiInputEvent decodeCodeUnit(int ch) throws IOException {
        if (ch == '\r') {
            lastWasCarriageReturn = true;
            return KeyStroke.of(KeyStroke.Key.ENTER);
        }
        if (ch == '\n') {
            if (lastWasCarriageReturn) {
                lastWasCarriageReturn = false;
                return null;
            }
            return KeyStroke.of(KeyStroke.Key.ENTER);
        }
        lastWasCarriageReturn = false;

        if (ch >= 1 && ch <= 26) {
            if (ch == '\t') {
                return KeyStroke.of(KeyStroke.Key.TAB);
            }
            return KeyStroke.of(letterKey((char) ('A' + ch - 1)), KeyStroke.Modifier.CTRL);
        }
        return switch (ch) {
            case 0 -> KeyStroke.of(KeyStroke.Key.SPACE, KeyStroke.Modifier.CTRL);
            case 28 -> KeyStroke.of(KeyStroke.Key.BACKSLASH, KeyStroke.Modifier.CTRL);
            case 29 -> KeyStroke.of(KeyStroke.Key.RIGHT_BRACKET, KeyStroke.Modifier.CTRL);
            case 30 -> KeyStroke.of(
                KeyStroke.Key.DIGIT_6,
                KeyStroke.Modifier.CTRL,
                KeyStroke.Modifier.SHIFT
            );
            case 31 -> KeyStroke.of(KeyStroke.Key.SLASH, KeyStroke.Modifier.CTRL);
            case 127 -> KeyStroke.of(KeyStroke.Key.BACKSPACE);
            default -> isTextCodeUnit(ch)
                ? decodePrintable(ch)
                : KeyStroke.of(KeyStroke.Key.UNKNOWN);
        };
    }

    private int readCodeUnit(long timeoutMs) throws IOException {
        if (pendingCodeUnit >= 0) {
            int pending = pendingCodeUnit;
            pendingCodeUnit = -1;
            return pending;
        }
        return reader.read(timeoutMs);
    }

    private TextInput decodePrintable(int ch) throws IOException {
        if (ch > Character.MAX_VALUE) {
            return new TextInput(new String(Character.toChars(ch)));
        }
        char first = (char) ch;
        if (Character.isLowSurrogate(first)) {
            return replacementText();
        }
        if (!Character.isHighSurrogate(first)) {
            return new TextInput(String.valueOf(first));
        }

        int second = reader.read(SEQUENCE_TIMEOUT_MS);
        if (second == NonBlockingReader.READ_EXPIRED) {
            return replacementText();
        }
        if (second < 0) {
            endPending = true;
            return replacementText();
        }
        if (second <= Character.MAX_VALUE && Character.isLowSurrogate((char) second)) {
            return new TextInput(new String(new char[] {first, (char) second}));
        }
        pendingCodeUnit = second;
        return replacementText();
    }

    private void beginEscapeSequence() {
        lastWasCarriageReturn = false;
        escapeState = EscapeState.AFTER_ESCAPE;
        sequenceExpiryPending = false;
        csiSequence.setLength(0);
    }

    private TuiInputEvent continueEscapeSequence(long timeoutMs) throws IOException {
        long nextTimeout = timeoutMs;
        while (true) {
            int next = reader.read(nextTimeout);
            nextTimeout = SEQUENCE_TIMEOUT_MS;
            if (next == NonBlockingReader.READ_EXPIRED) {
                return expireEscapeSequence();
            }
            if (next < 0) {
                return endEscapeSequence();
            }
            sequenceExpiryPending = false;

            if (escapeState == EscapeState.AFTER_ESCAPE) {
                if (next == 'O') {
                    escapeState = EscapeState.SS3;
                    continue;
                }
                if (next == '[') {
                    escapeState = EscapeState.CSI;
                    csiSequence.setLength(0);
                    continue;
                }
                clearEscapeSequence();
                return decodeAlt(next);
            }
            if (escapeState == EscapeState.SS3) {
                clearEscapeSequence();
                return decodeSs3Code(next);
            }

            char c = (char) next;
            if (c == 27) {
                // ECMA-48 ESC cancels an incomplete CSI and starts a new sequence.
                beginEscapeSequence();
                continue;
            }
            csiSequence.append(c);
            if (c >= 0x40 && c <= 0x7E) {
                return finishCsiSequence();
            }
            if (csiSequence.length() >= MAX_CSI_LENGTH) {
                clearEscapeSequence();
                return KeyStroke.of(KeyStroke.Key.UNKNOWN);
            }
        }
    }

    private TuiInputEvent expireEscapeSequence() {
        if (!sequenceExpiryPending) {
            sequenceExpiryPending = true;
            return null;
        }

        if (escapeState == EscapeState.AFTER_ESCAPE) {
            clearEscapeSequence();
            return KeyStroke.of(KeyStroke.Key.ESCAPE);
        }
        if (escapeState == EscapeState.CSI) {
            return finishCsiSequence();
        }
        clearEscapeSequence();
        return KeyStroke.of(KeyStroke.Key.UNKNOWN);
    }

    private TuiInputEvent endEscapeSequence() {
        endPending = true;
        if (escapeState == EscapeState.AFTER_ESCAPE) {
            clearEscapeSequence();
            return KeyStroke.of(KeyStroke.Key.ESCAPE);
        }
        if (escapeState == EscapeState.CSI) {
            return finishCsiSequence();
        }
        clearEscapeSequence();
        return KeyStroke.of(KeyStroke.Key.UNKNOWN);
    }

    private TuiInputEvent finishCsiSequence() {
        String sequence = csiSequence.toString();
        clearEscapeSequence();
        return decodeCsi(sequence);
    }

    private void clearEscapeSequence() {
        escapeState = EscapeState.NONE;
        sequenceExpiryPending = false;
        csiSequence.setLength(0);
    }

    private static TuiInputEvent decodeSs3Code(int code) {
        return switch (code) {
            case 'A' -> KeyStroke.of(KeyStroke.Key.ARROW_UP);
            case 'B' -> KeyStroke.of(KeyStroke.Key.ARROW_DOWN);
            case 'C' -> KeyStroke.of(KeyStroke.Key.ARROW_RIGHT);
            case 'D' -> KeyStroke.of(KeyStroke.Key.ARROW_LEFT);
            case 'H' -> KeyStroke.of(KeyStroke.Key.HOME);
            case 'F' -> KeyStroke.of(KeyStroke.Key.END);
            case 'P' -> KeyStroke.of(KeyStroke.Key.F1);
            case 'Q' -> KeyStroke.of(KeyStroke.Key.F2);
            case 'R' -> KeyStroke.of(KeyStroke.Key.F3);
            case 'S' -> KeyStroke.of(KeyStroke.Key.F4);
            default -> KeyStroke.of(KeyStroke.Key.UNKNOWN);
        };
    }

    private TuiInputEvent decodeAlt(int code) throws IOException {
        if (code == 27) {
            return KeyStroke.of(KeyStroke.Key.ESCAPE, KeyStroke.Modifier.ALT);
        }
        TuiInputEvent decoded = decodeCodeUnit(code);
        if (decoded instanceof KeyStroke stroke) {
            EnumSet<KeyStroke.Modifier> modifiers = copyModifiers(stroke.modifiers());
            modifiers.add(KeyStroke.Modifier.ALT);
            return new KeyStroke(stroke.key(), modifiers);
        }
        if (decoded instanceof TextInput text && text.text().codePointCount(0, text.text().length()) == 1) {
            PhysicalKey physical = physicalKey(text.text().codePointAt(0));
            if (physical != null) {
                EnumSet<KeyStroke.Modifier> modifiers = EnumSet.of(KeyStroke.Modifier.ALT);
                if (physical.shifted()) {
                    modifiers.add(KeyStroke.Modifier.SHIFT);
                }
                return new KeyStroke(physical.key(), modifiers);
            }
        }

        pendingEvent = decoded;
        return KeyStroke.of(KeyStroke.Key.ESCAPE);
    }

    private TuiInputEvent decodeCsi(String sequence) {
        if (sequence.startsWith("<")) {
            PointerEvent pointer = parseSgrPointer(sequence);
            return pointer == null ? KeyStroke.of(KeyStroke.Key.UNKNOWN) : pointer;
        }
        if ("200~".equals(sequence)) {
            beginBracketedPaste();
            return null;
        }
        if ("Z".equals(sequence)) {
            return KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT);
        }

        char suffix = sequence.isEmpty() ? '\0' : sequence.charAt(sequence.length() - 1);
        String parameters = sequence.isEmpty() ? "" : sequence.substring(0, sequence.length() - 1);
        return switch (suffix) {
            case 'A' -> modifiedKey(KeyStroke.Key.ARROW_UP, parameters);
            case 'B' -> modifiedKey(KeyStroke.Key.ARROW_DOWN, parameters);
            case 'C' -> modifiedKey(KeyStroke.Key.ARROW_RIGHT, parameters);
            case 'D' -> modifiedKey(KeyStroke.Key.ARROW_LEFT, parameters);
            case 'H' -> modifiedKey(KeyStroke.Key.HOME, parameters);
            case 'F' -> modifiedKey(KeyStroke.Key.END, parameters);
            case 'P' -> modifiedKey(KeyStroke.Key.F1, parameters);
            case 'Q' -> modifiedKey(KeyStroke.Key.F2, parameters);
            case 'R' -> modifiedKey(KeyStroke.Key.F3, parameters);
            case 'S' -> modifiedKey(KeyStroke.Key.F4, parameters);
            case 'I' -> parameters.startsWith("1;")
                ? new KeyStroke(KeyStroke.Key.TAB, modifiersFromParameters(parameters))
                : KeyStroke.of(KeyStroke.Key.UNKNOWN);
            case '~' -> decodeTildeSequence(parameters);
            case 'u' -> decodeUnicodeKey(parameters);
            default -> KeyStroke.of(KeyStroke.Key.UNKNOWN);
        };
    }

    private TuiInputEvent decodeTildeSequence(String parameters) {
        int[] values = parseParameters(parameters);
        if (values == null || values.length == 0) {
            return KeyStroke.of(KeyStroke.Key.UNKNOWN);
        }
        if (values[0] == 27) {
            if (values.length != 3 || !isSupportedModifier(values[1])) {
                return KeyStroke.of(KeyStroke.Key.UNKNOWN);
            }
            return decodeModifiedCodePoint(values[2], modifiersFromParameter(values[1]));
        }
        if (values.length > 2 || values.length == 2 && !isSupportedModifier(values[1])) {
            return KeyStroke.of(KeyStroke.Key.UNKNOWN);
        }

        KeyStroke.Key key = switch (values[0]) {
            case 1, 7 -> KeyStroke.Key.HOME;
            case 2 -> KeyStroke.Key.INSERT;
            case 3 -> KeyStroke.Key.DELETE;
            case 4, 8 -> KeyStroke.Key.END;
            case 5 -> KeyStroke.Key.PAGE_UP;
            case 6 -> KeyStroke.Key.PAGE_DOWN;
            case 11 -> KeyStroke.Key.F1;
            case 12 -> KeyStroke.Key.F2;
            case 13 -> KeyStroke.Key.F3;
            case 14 -> KeyStroke.Key.F4;
            case 15 -> KeyStroke.Key.F5;
            case 17 -> KeyStroke.Key.F6;
            case 18 -> KeyStroke.Key.F7;
            case 19 -> KeyStroke.Key.F8;
            case 20 -> KeyStroke.Key.F9;
            case 21 -> KeyStroke.Key.F10;
            case 23 -> KeyStroke.Key.F11;
            case 24 -> KeyStroke.Key.F12;
            default -> KeyStroke.Key.UNKNOWN;
        };
        Set<KeyStroke.Modifier> modifiers = values.length >= 2
            ? modifiersFromParameter(values[values.length - 1])
            : Set.of();
        return new KeyStroke(key, modifiers);
    }

    private TuiInputEvent decodeUnicodeKey(String parameters) {
        int[] values = parseParameters(parameters);
        if (values == null || values.length == 0 || values.length > 2) {
            return KeyStroke.of(KeyStroke.Key.UNKNOWN);
        }
        if (values.length == 2 && !isSupportedModifier(values[1])) {
            return KeyStroke.of(KeyStroke.Key.UNKNOWN);
        }
        Set<KeyStroke.Modifier> modifiers = values.length == 2
            ? modifiersFromParameter(values[1])
            : Set.of();
        return decodeModifiedCodePoint(values[0], modifiers);
    }

    private TuiInputEvent decodeModifiedCodePoint(int codePoint, Set<KeyStroke.Modifier> modifiers) {
        if (!Character.isValidCodePoint(codePoint)) {
            return new KeyStroke(KeyStroke.Key.UNKNOWN, modifiers);
        }
        if (modifiers.isEmpty() && isPrintable(codePoint)) {
            return new TextInput(new String(Character.toChars(codePoint)));
        }

        KeyStroke.Key special = switch (codePoint) {
            case 9 -> KeyStroke.Key.TAB;
            case 13 -> KeyStroke.Key.ENTER;
            case 27 -> KeyStroke.Key.ESCAPE;
            case 127 -> KeyStroke.Key.BACKSPACE;
            default -> null;
        };
        if (special != null) {
            return new KeyStroke(special, modifiers);
        }

        PhysicalKey physical = physicalKey(codePoint);
        if (physical == null) {
            return new KeyStroke(KeyStroke.Key.UNKNOWN, modifiers);
        }
        EnumSet<KeyStroke.Modifier> effective = copyModifiers(modifiers);
        if (physical.shifted()) {
            effective.add(KeyStroke.Modifier.SHIFT);
        }
        return new KeyStroke(physical.key(), effective);
    }

    private PointerEvent parseSgrPointer(String sequence) {
        char suffix = sequence.charAt(sequence.length() - 1);
        if (suffix != 'M' && suffix != 'm') {
            return null;
        }
        int[] values = parseParameters(sequence.substring(1, sequence.length() - 1));
        if (values == null || values.length != 3) {
            return null;
        }
        int encoded = values[0];
        if (encoded < 0 || values[1] <= 0 || values[2] <= 0) {
            return null;
        }
        int column = values[1] - 1;
        int row = values[2] - 1;

        EnumSet<KeyStroke.Modifier> modifiers = EnumSet.noneOf(KeyStroke.Modifier.class);
        if ((encoded & 4) != 0) {
            modifiers.add(KeyStroke.Modifier.SHIFT);
        }
        if ((encoded & 8) != 0) {
            modifiers.add(KeyStroke.Modifier.ALT);
        }
        if ((encoded & 16) != 0) {
            modifiers.add(KeyStroke.Modifier.CTRL);
        }

        boolean wheel = (encoded & 64) != 0;
        boolean motion = (encoded & 32) != 0;
        int baseButton = encoded & 3;
        if (encoded >= 128) {
            return new PointerEvent(
                row,
                column,
                PointerEvent.Kind.UNKNOWN,
                PointerEvent.Button.OTHER,
                modifiers
            );
        }
        if (wheel) {
            PointerEvent.Kind kind = switch (baseButton) {
                case 0 -> PointerEvent.Kind.SCROLL_UP;
                case 1 -> PointerEvent.Kind.SCROLL_DOWN;
                case 2 -> PointerEvent.Kind.SCROLL_LEFT;
                default -> PointerEvent.Kind.SCROLL_RIGHT;
            };
            return new PointerEvent(row, column, kind, PointerEvent.Button.NONE, modifiers);
        }

        PointerEvent.Button button = switch (baseButton) {
            case 0 -> PointerEvent.Button.LEFT;
            case 1 -> PointerEvent.Button.MIDDLE;
            case 2 -> PointerEvent.Button.RIGHT;
            default -> PointerEvent.Button.NONE;
        };
        PointerEvent.Kind kind;
        if (baseButton == 3 && !motion) {
            kind = PointerEvent.Kind.UNKNOWN;
            button = PointerEvent.Button.OTHER;
        } else if (suffix == 'm') {
            kind = PointerEvent.Kind.RELEASE;
        } else if (motion) {
            kind = button == PointerEvent.Button.NONE ? PointerEvent.Kind.MOVE : PointerEvent.Kind.DRAG;
        } else {
            kind = PointerEvent.Kind.PRESS;
        }
        return new PointerEvent(row, column, kind, button, modifiers);
    }

    private ReadResult readBracketedPaste(long timeoutMs) throws IOException {
        long nextTimeout = timeoutMs;
        while (true) {
            int next = reader.read(nextTimeout);
            nextTimeout = SEQUENCE_TIMEOUT_MS;
            if (next == NonBlockingReader.READ_EXPIRED) {
                return ReadResult.idle();
            }
            if (next < 0) {
                endPending = true;
                return ReadResult.event(finishBracketedPaste(false));
            }
            if (next <= Character.MAX_VALUE) {
                if (appendPasteCodeUnit((char) next)) {
                    return ReadResult.event(finishBracketedPaste(true));
                }
            } else if (Character.isValidCodePoint(next)) {
                for (char codeUnit : Character.toChars(next)) {
                    if (appendPasteCodeUnit(codeUnit)) {
                        return ReadResult.event(finishBracketedPaste(true));
                    }
                }
            } else {
                if (appendPasteCodeUnit((char) REPLACEMENT_CHARACTER)) {
                    return ReadResult.event(finishBracketedPaste(true));
                }
            }
        }
    }

    private void beginBracketedPaste() {
        bracketedPaste = true;
        pasteBuffer.setLength(0);
        pasteEndWindow.setLength(0);
        pasteCodeUnitsSeen = 0L;
    }

    private boolean appendPasteCodeUnit(char codeUnit) {
        if (pasteBuffer.length() < maxPasteCodeUnits) {
            pasteBuffer.append(codeUnit);
        }
        if (pasteCodeUnitsSeen < Long.MAX_VALUE) {
            pasteCodeUnitsSeen++;
        }

        pasteEndWindow.append(codeUnit);
        if (pasteEndWindow.length() > BRACKETED_PASTE_END.length()) {
            pasteEndWindow.deleteCharAt(0);
        }
        return endsWith(pasteEndWindow, BRACKETED_PASTE_END);
    }

    private PasteEvent finishBracketedPaste(boolean delimiterFound) {
        long contentCodeUnits = delimiterFound
            ? Math.max(0L, pasteCodeUnitsSeen - BRACKETED_PASTE_END.length())
            : pasteCodeUnitsSeen;
        int retainedLength = (int) Math.min((long) pasteBuffer.length(), contentCodeUnits);
        pasteBuffer.setLength(retainedLength);
        boolean truncated = contentCodeUnits > maxPasteCodeUnits;
        if (truncated
            && !pasteBuffer.isEmpty()
            && Character.isHighSurrogate(pasteBuffer.charAt(pasteBuffer.length() - 1))) {
            pasteBuffer.setLength(pasteBuffer.length() - 1);
        }

        PasteEvent event = new PasteEvent(pasteBuffer.toString(), truncated);
        bracketedPaste = false;
        pasteBuffer.setLength(0);
        pasteEndWindow.setLength(0);
        pasteCodeUnitsSeen = 0L;
        return event;
    }

    private static KeyStroke modifiedKey(KeyStroke.Key key, String parameters) {
        return new KeyStroke(key, modifiersFromParameters(parameters));
    }

    private static Set<KeyStroke.Modifier> modifiersFromParameters(String parameters) {
        int[] values = parseParameters(parameters);
        if (values == null || values.length < 2) {
            return Set.of();
        }
        return modifiersFromParameter(values[values.length - 1]);
    }

    private static Set<KeyStroke.Modifier> modifiersFromParameter(int encoded) {
        int bits = Math.max(0, encoded - 1);
        EnumSet<KeyStroke.Modifier> modifiers = EnumSet.noneOf(KeyStroke.Modifier.class);
        if ((bits & 1) != 0) {
            modifiers.add(KeyStroke.Modifier.SHIFT);
        }
        if ((bits & 2) != 0) {
            modifiers.add(KeyStroke.Modifier.ALT);
        }
        if ((bits & 4) != 0) {
            modifiers.add(KeyStroke.Modifier.CTRL);
        }
        if ((bits & 8) != 0) {
            modifiers.add(KeyStroke.Modifier.SUPER);
        }
        return Set.copyOf(modifiers);
    }

    private static boolean isSupportedModifier(int encoded) {
        return encoded >= 1 && encoded <= 16;
    }

    private static int[] parseParameters(String parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return new int[0];
        }
        String[] parts = parameters.split(";", -1);
        int[] values = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].isEmpty()) {
                    return null;
                }
                values[i] = Integer.parseInt(parts[i]);
            }
            return values;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static PhysicalKey physicalKey(int codePoint) {
        if (codePoint >= 'a' && codePoint <= 'z') {
            return new PhysicalKey(letterKey(Character.toUpperCase((char) codePoint)), false);
        }
        if (codePoint >= 'A' && codePoint <= 'Z') {
            return new PhysicalKey(letterKey((char) codePoint), true);
        }
        if (codePoint >= '0' && codePoint <= '9') {
            return new PhysicalKey(digitKey((char) codePoint), false);
        }
        return switch (codePoint) {
            case ' ' -> new PhysicalKey(KeyStroke.Key.SPACE, false);
            case '-' -> new PhysicalKey(KeyStroke.Key.MINUS, false);
            case '_' -> new PhysicalKey(KeyStroke.Key.MINUS, true);
            case '=' -> new PhysicalKey(KeyStroke.Key.EQUALS, false);
            case '+' -> new PhysicalKey(KeyStroke.Key.EQUALS, true);
            case '[' -> new PhysicalKey(KeyStroke.Key.LEFT_BRACKET, false);
            case '{' -> new PhysicalKey(KeyStroke.Key.LEFT_BRACKET, true);
            case ']' -> new PhysicalKey(KeyStroke.Key.RIGHT_BRACKET, false);
            case '}' -> new PhysicalKey(KeyStroke.Key.RIGHT_BRACKET, true);
            case '\\' -> new PhysicalKey(KeyStroke.Key.BACKSLASH, false);
            case '|' -> new PhysicalKey(KeyStroke.Key.BACKSLASH, true);
            case ';' -> new PhysicalKey(KeyStroke.Key.SEMICOLON, false);
            case ':' -> new PhysicalKey(KeyStroke.Key.SEMICOLON, true);
            case '\'' -> new PhysicalKey(KeyStroke.Key.APOSTROPHE, false);
            case '"' -> new PhysicalKey(KeyStroke.Key.APOSTROPHE, true);
            case '`' -> new PhysicalKey(KeyStroke.Key.GRAVE, false);
            case '~' -> new PhysicalKey(KeyStroke.Key.GRAVE, true);
            case ',' -> new PhysicalKey(KeyStroke.Key.COMMA, false);
            case '<' -> new PhysicalKey(KeyStroke.Key.COMMA, true);
            case '.' -> new PhysicalKey(KeyStroke.Key.PERIOD, false);
            case '>' -> new PhysicalKey(KeyStroke.Key.PERIOD, true);
            case '/' -> new PhysicalKey(KeyStroke.Key.SLASH, false);
            case '?' -> new PhysicalKey(KeyStroke.Key.SLASH, true);
            case '!' -> new PhysicalKey(KeyStroke.Key.DIGIT_1, true);
            case '@' -> new PhysicalKey(KeyStroke.Key.DIGIT_2, true);
            case '#' -> new PhysicalKey(KeyStroke.Key.DIGIT_3, true);
            case '$' -> new PhysicalKey(KeyStroke.Key.DIGIT_4, true);
            case '%' -> new PhysicalKey(KeyStroke.Key.DIGIT_5, true);
            case '^' -> new PhysicalKey(KeyStroke.Key.DIGIT_6, true);
            case '&' -> new PhysicalKey(KeyStroke.Key.DIGIT_7, true);
            case '*' -> new PhysicalKey(KeyStroke.Key.DIGIT_8, true);
            case '(' -> new PhysicalKey(KeyStroke.Key.DIGIT_9, true);
            case ')' -> new PhysicalKey(KeyStroke.Key.DIGIT_0, true);
            default -> null;
        };
    }

    private static KeyStroke.Key letterKey(char upperCase) {
        return KeyStroke.Key.valueOf(String.valueOf(upperCase));
    }

    private static KeyStroke.Key digitKey(char digit) {
        return KeyStroke.Key.valueOf("DIGIT_" + digit);
    }

    private static boolean isPrintable(int codePoint) {
        return Character.isValidCodePoint(codePoint)
            && (codePoint < Character.MIN_SURROGATE || codePoint > Character.MAX_SURROGATE)
            && !Character.isISOControl(codePoint);
    }

    private static boolean isTextCodeUnit(int value) {
        return value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE
            || isPrintable(value);
    }

    private static TextInput replacementText() {
        return new TextInput(new String(Character.toChars(REPLACEMENT_CHARACTER)));
    }

    private static EnumSet<KeyStroke.Modifier> copyModifiers(Set<KeyStroke.Modifier> source) {
        return source.isEmpty()
            ? EnumSet.noneOf(KeyStroke.Modifier.class)
            : EnumSet.copyOf(source);
    }

    private static boolean endsWith(StringBuilder value, String suffix) {
        if (value.length() < suffix.length()) {
            return false;
        }
        int offset = value.length() - suffix.length();
        for (int i = 0; i < suffix.length(); i++) {
            if (value.charAt(offset + i) != suffix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private enum EscapeState {
        NONE,
        AFTER_ESCAPE,
        SS3,
        CSI
    }

    private record PhysicalKey(KeyStroke.Key key, boolean shifted) {
    }

    public enum ReadStatus {
        EVENT,
        IDLE,
        END
    }

    public record ReadResult(ReadStatus status, TuiInputEvent event) {
        public ReadResult {
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
            if ((status == ReadStatus.EVENT) != (event != null)) {
                throw new IllegalArgumentException("Only EVENT results may contain an event");
            }
        }

        private static ReadResult event(TuiInputEvent event) {
            return new ReadResult(ReadStatus.EVENT, event);
        }

        private static ReadResult idle() {
            return new ReadResult(ReadStatus.IDLE, null);
        }

        private static ReadResult end() {
            return new ReadResult(ReadStatus.END, null);
        }
    }
}
