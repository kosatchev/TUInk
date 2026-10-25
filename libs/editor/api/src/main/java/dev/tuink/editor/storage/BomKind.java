package dev.tuink.editor.storage;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Byte-order marker detected for a document file. */
public enum BomKind {
    NONE(new byte[0]),
    UTF_8(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}),
    UTF_16_LE(new byte[] {(byte) 0xFF, (byte) 0xFE}),
    UTF_16_BE(new byte[] {(byte) 0xFE, (byte) 0xFF});

    private final byte[] bytes;

    BomKind(byte[] bytes) {
        this.bytes = bytes;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public boolean isCompatibleWith(Charset charset) {
        if (charset == null) {
            return false;
        }
        return switch (this) {
            case UTF_8 -> StandardCharsets.UTF_8.equals(charset);
            case UTF_16_LE -> StandardCharsets.UTF_16LE.equals(charset);
            case UTF_16_BE -> StandardCharsets.UTF_16BE.equals(charset);
            case NONE -> !StandardCharsets.UTF_16LE.equals(charset)
                && !StandardCharsets.UTF_16BE.equals(charset);
        };
    }
}
