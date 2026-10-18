package dev.tuink.core;

import java.io.IOException;

/** The same bytes form both a UTF-32LE stream and a supported UTF-16LE document. */
public final class AmbiguousDocumentEncodingException extends IOException {
    private static final long serialVersionUID = 1L;

    public AmbiguousDocumentEncodingException() {
        super("Ambiguous UTF-16LE / UTF-32LE encoding. Convert the file to UTF-8 before opening.");
    }
}
