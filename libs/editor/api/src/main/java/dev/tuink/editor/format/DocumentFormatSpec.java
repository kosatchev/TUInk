package dev.tuink.editor.format;

import dev.tuink.editor.storage.DocumentFileMetadata;
import java.util.Objects;

/** Output conversion; null encoding retains the current charset and BOM. */
public record DocumentFormatSpec(DocumentEncoding encoding, LineEndingPolicy lineEndings) {
    public DocumentFormatSpec {
        Objects.requireNonNull(lineEndings, "lineEndings");
    }

    public DocumentFileMetadata metadata(DocumentFileMetadata original, int lineCount) {
        Objects.requireNonNull(original, "original");
        if (!original.isCompatibleWithLineCount(lineCount)) throw new IllegalArgumentException("Incompatible metadata");
        var charset = encoding == null ? original.charset() : encoding.charset();
        var bom = encoding == null ? original.bomKind() : encoding.bomKind();
        return lineEndings == LineEndingPolicy.KEEP
            ? new DocumentFileMetadata(charset, bom,
                original.defaultLineSeparator(), original.lineSeparators())
            : DocumentFileMetadata.canonical(charset, bom, lineEndings.separator(), lineCount);
    }
}
