package dev.tuink.editor.storage;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Objects;

/** A document character cannot be represented in the requested output encoding. */
public final class DocumentEncodingException extends CharacterCodingException {
    private static final long serialVersionUID = 1L;

    private final String charsetName;
    private final int codePoint;

    public DocumentEncodingException(Charset charset, int codePoint, CharacterCodingException cause) {
        charsetName = Objects.requireNonNull(charset, "charset").name();
        if (!Character.isValidCodePoint(codePoint)) {
            throw new IllegalArgumentException("codePoint must be a valid Unicode code point");
        }
        this.codePoint = codePoint;
        initCause(Objects.requireNonNull(cause, "cause"));
    }

    public String charsetName() {
        return charsetName;
    }

    public int codePoint() {
        return codePoint;
    }

    @Override
    public String getMessage() {
        return String.format(Locale.ROOT, "Character U+%04X cannot be encoded in %s", codePoint, charsetName);
    }
}
