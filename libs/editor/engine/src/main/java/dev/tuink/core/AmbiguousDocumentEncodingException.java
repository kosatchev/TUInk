package dev.tuink.core;

import java.io.IOException;

/** The same bytes form both a UTF-32LE stream and a supported UTF-16LE document. */
public final class AmbiguousDocumentEncodingException extends IOException {
    private static final long serialVersionUID = 1L;

    public AmbiguousDocumentEncodingException() {
        super("Ambiguous UTF-16LE / UTF-32LE encoding. Use --open-as-utf16le for UTF-16LE, or convert UTF-32 to UTF-8.");
    }
}
