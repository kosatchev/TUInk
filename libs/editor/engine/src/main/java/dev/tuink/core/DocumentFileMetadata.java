package dev.tuink.core;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * File-level document properties that are not representable by a list of text lines.
 *
 * <p>{@code lineSeparators} contains the exact separators between logical lines.
 * A final file separator is represented canonically by a trailing empty logical line,
 * so the list size is always {@code lineCount - 1}.</p>
 */
public record DocumentFileMetadata(
    Charset charset,
    BomKind bomKind,
    String defaultLineSeparator,
    List<String> lineSeparators
) {
    public DocumentFileMetadata {
        charset = charset == null ? StandardCharsets.UTF_8 : charset;
        bomKind = bomKind == null ? BomKind.NONE : bomKind;
        if (encoderEmitsImplicitBom(charset)) {
            throw new IllegalArgumentException(
                "Charset " + charset.name()
                    + " emits an implicit BOM; use an explicit byte-order charset instead"
            );
        }
        if (!bomKind.isCompatibleWith(charset)) {
            throw new IllegalArgumentException("BOM " + bomKind + " is incompatible with " + charset.name());
        }
        defaultLineSeparator = defaultLineSeparator == null
            ? EditorState.LINE_SEPARATOR_LF
            : requireLineSeparator(defaultLineSeparator);
        lineSeparators = LineSeparatorSequence.copyOf(lineSeparators);
    }

    public static DocumentFileMetadata newDocument(int lineCount) {
        return canonical(
            StandardCharsets.UTF_8,
            BomKind.NONE,
            EditorState.LINE_SEPARATOR_LF,
            lineCount
        );
    }

    public static DocumentFileMetadata canonical(
        Charset charset,
        BomKind bomKind,
        String lineSeparator,
        int lineCount
    ) {
        int normalizedLineCount = Math.max(1, lineCount);
        int separatorCount = normalizedLineCount - 1;
        String normalizedSeparator = normalizeLineSeparator(lineSeparator);
        return new DocumentFileMetadata(
            charset,
            bomKind,
            normalizedSeparator,
            LineSeparatorSequence.repeated(normalizedSeparator, separatorCount)
        );
    }

    public boolean isCompatibleWithLineCount(int lineCount) {
        int normalizedLineCount = Math.max(1, lineCount);
        return lineSeparators.size() == normalizedLineCount - 1;
    }

    public DocumentFileMetadata withLineSeparators(List<String> separators) {
        return new DocumentFileMetadata(charset, bomKind, defaultLineSeparator, separators);
    }

    public DocumentFileMetadata insertLineSeparators(int index, int count) {
        return replaceLineSeparators(index, 0, LineSeparatorSequence.repeated(defaultLineSeparator, count));
    }

    public DocumentFileMetadata removeLineSeparators(int index, int count) {
        return replaceLineSeparators(index, count, List.of());
    }

    public DocumentFileMetadata replaceLineSeparators(int index, int removedCount, List<String> inserted) {
        LineSeparatorSequence current = (LineSeparatorSequence) lineSeparators;
        LineSeparatorSequence next = current.replace(index, removedCount, LineSeparatorSequence.copyOf(inserted));
        return next == current ? this : withLineSeparators(next);
    }

    public DocumentFileMetadata withLineSeparator(int index, String separator) {
        String checked = requireLineSeparator(separator);
        return checked.equals(lineSeparators.get(index)) ? this
            : replaceLineSeparators(index, 1, LineSeparatorSequence.repeated(checked, 1));
    }

    public DocumentFileMetadata canonicalized(Charset nextCharset, String lineSeparator, int lineCount) {
        Charset normalizedCharset = nextCharset == null ? StandardCharsets.UTF_8 : nextCharset;
        BomKind nextBom;
        if (StandardCharsets.UTF_16LE.equals(normalizedCharset)) {
            nextBom = BomKind.UTF_16_LE;
        } else if (StandardCharsets.UTF_16BE.equals(normalizedCharset)) {
            nextBom = BomKind.UTF_16_BE;
        } else {
            nextBom = bomKind.isCompatibleWith(normalizedCharset) ? bomKind : BomKind.NONE;
        }
        return canonical(normalizedCharset, nextBom, lineSeparator, lineCount);
    }

    static String normalizeLineSeparator(String separator) {
        if (EditorState.LINE_SEPARATOR_CRLF.equals(separator)) {
            return EditorState.LINE_SEPARATOR_CRLF;
        }
        if (EditorState.LINE_SEPARATOR_CR.equals(separator)) {
            return EditorState.LINE_SEPARATOR_CR;
        }
        return EditorState.LINE_SEPARATOR_LF;
    }

    static String requireLineSeparator(String separator) {
        if (EditorState.LINE_SEPARATOR_LF.equals(separator)
            || EditorState.LINE_SEPARATOR_CRLF.equals(separator)
            || EditorState.LINE_SEPARATOR_CR.equals(separator)) {
            return separator;
        }
        throw new IllegalArgumentException("Unknown line separator");
    }

    private static boolean encoderEmitsImplicitBom(Charset charset) {
        byte[] encoded = "A".getBytes(charset);
        return startsWith(encoded, 0xEF, 0xBB, 0xBF)
            || startsWith(encoded, 0xFE, 0xFF)
            || startsWith(encoded, 0xFF, 0xFE)
            || startsWith(encoded, 0x00, 0x00, 0xFE, 0xFF)
            || startsWith(encoded, 0xFF, 0xFE, 0x00, 0x00);
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((bytes[index] & 0xFF) != prefix[index]) {
                return false;
            }
        }
        return true;
    }
}
