package dev.tuink.editor.format;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import java.util.Objects;

/** Small immutable status projection; hosts cache it by immutable metadata identity. */
public record DocumentFormatSummary(DocumentEncoding encoding, String charsetName, BomKind bomKind,
                                    String defaultLineSeparator, LineEnding lineEnding, boolean finalNewline) {
    public enum LineEnding { NONE, LF, CRLF, CR, MIXED }

    public DocumentFormatSummary {
        Objects.requireNonNull(charsetName, "charsetName");
        Objects.requireNonNull(bomKind, "bomKind");
        Objects.requireNonNull(defaultLineSeparator, "defaultLineSeparator");
        Objects.requireNonNull(lineEnding, "lineEnding");
    }

    public static DocumentFormatSummary from(DocumentFileMetadata metadata, boolean finalNewline) {
        LineEnding kind = LineEnding.NONE;
        String first = null;
        for (String separator : metadata.lineSeparators()) {
            if (first == null) {
                first = separator;
                kind = switch (separator) {
                    case "\n" -> LineEnding.LF;
                    case "\r\n" -> LineEnding.CRLF;
                    case "\r" -> LineEnding.CR;
                    default -> throw new IllegalArgumentException("Invalid line separator");
                };
            } else if (!first.equals(separator)) {
                kind = LineEnding.MIXED;
                break;
            }
        }
        return new DocumentFormatSummary(DocumentEncoding.from(metadata), metadata.charset().name(),
            metadata.bomKind(), metadata.defaultLineSeparator(), kind, finalNewline);
    }
}
